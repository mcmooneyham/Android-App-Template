package com.mattmooneyham.base.android.managers.logManager

import com.mattmooneyham.base.android.constants.LogLevel
import com.mattmooneyham.base.android.managers.ConfinedManager
import com.mattmooneyham.base.android.managers.eventManager.EventManager
import com.mattmooneyham.base.android.managers.eventManager.SignalKey
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.Clock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.format
import kotlinx.datetime.format.char
import kotlinx.datetime.toLocalDateTime
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.buffer
import okio.use

// Signal: the log history was cleared. Does not replay, so late
// subscribers never observe a stale "cleared" notification.
object LogsCleared : SignalKey(eventName = "log.Cleared")

/**
 * File-side policy for the [LogManager], grouped into one value so
 * the constructor stays within the composition root's five-parameter
 * budget with the telemetry seam aboard.
 */
data class LogFileSettings(
    val directoryPath: String?,
    val fileName: String = LogManager.DEFAULT_LOG_FILE_NAME,
    val maxFileSizeBytes: Long = LogManager.DEFAULT_MAX_LOG_FILE_BYTES,
    val fileLoggingEnabled: Boolean = true,
)

/**
 * Synthesized for ERROR lines logged without a Throwable, so the
 * crash backend still receives a countable non-fatal. Its one-frame
 * stack points at the LOGGING CALL SITE, keeping backend issue
 * grouping per call site instead of one bucket for every
 * message-only error.
 */
class LoggedError internal constructor(
    logLine: String,
) : RuntimeException(logLine)

/**
 * Central logging entry point for the whole app. Every line
 * carries the full context of the call, in fixed-width pipe-separated
 * columns:
 *
 * ```
 * 2026-07-21 | 21:15:42.123 | DEBUG | main | MainViewModel.markWelcomeSeen() (MainViewModel.kt:34) | MainViewModel: message
 * ```
 *
 * i.e. UTC timestamp, level, thread, call site (class, method, file,
 * line), tag, and message. Lines go to Logcat and, once a directory is
 * configured, to a log file. Logging never throws: failures are
 * swallowed so a logging problem can't take the app down.
 *
 * The short call style is the intended one: `logManager.info("message")`.
 * The tag is optional and defaults to the resolved call-site class name;
 * pass one explicitly only when the class name would mislead.
 *
 * File writes are serialized on a single background writer, so logging
 * never performs IO on the calling thread; [readLogContents] is
 * therefore eventually consistent with in-flight lines ([flush] awaits
 * the writer when determinism is needed, e.g. in tests). Two
 * exceptions to the async rule, both in service of crash forensics:
 * ERROR-level lines drain the write queue synchronously before the
 * logging call returns, BOUNDED at [ERROR_DRAIN_COMMAND_LIMIT]
 * commands so a full queue can never stall the calling thread (often
 * Main) into an ANR; with more queued ahead, the background writer
 * lands the line moments later instead. [flushForCrash] keeps the
 * UNBOUNDED drain for a dying process, which has no UI to freeze.
 *
 * TELEMETRY FUNNEL: the manager also holds the [CrashReporter] seam.
 * Every accepted ERROR line is forwarded to
 * [CrashReporter.recordNonFatal] (the attached throwable, or a
 * call-site-stamped [LoggedError]); every accepted WARN/ERROR line
 * and every [breadcrumb] joins the backend's bounded breadcrumb
 * ring, so production crash reports carry the recent history even
 * though release builds keep DEBUG traces out of the file.
 *
 * The log file rotates by size: when it reaches
 * [maxLogFileSizeBytes], it is renamed with a `.1` inserted before
 * the extension (base-app.log becomes base-app.1.log, replacing any
 * previous rotation), and a fresh file starts. At most one rotated
 * file is kept, capping total disk use at roughly twice the limit.
 *
 * Provided as a singleton via
 * [com.mattmooneyham.base.android.di.AppComponent].
 *
 * @param fileSettings file-side policy (directory, name, rotation
 *   cap, on/off switch), grouped so the wiring stays within budget.
 * @param clock source of wall time for line timestamps; injected so
 *   tests can pin time and assert exact lines.
 * @param sinks the non-file outputs: the platform log mirror (Logcat
 *   via the PlatformLogWriter port) and the crash-backend seam. Both
 *   default to no-ops.
 */
@OptIn(ExperimentalAtomicApi::class)
class LogManager(
    fileSettings: LogFileSettings,
    private val minimumLogLevel: LogLevel = LogLevel.DEBUG,
    private val eventManager: EventManager? = null,
    private val clock: Clock = Clock.System,
    sinks: LogSinks = LogSinks(),
) : ConfinedManager(
    managerName = "LogManager",
    // The logger cannot report its own rails failures through itself
    // without risking recursion; per-command IO is already guarded with
    // runCatching, so anything reaching the handler is dropped quietly.
    failureLogManager = null,
) {

    private val logDirectoryPath = fileSettings.directoryPath
    private val logFileName = fileSettings.fileName
    private val fileLoggingEnabled = fileSettings.fileLoggingEnabled
    private val maxLogFileSizeBytes = fileSettings.maxFileSizeBytes
    private val platformWriter = sinks.platformWriter
    private val crashReporter = sinks.crashReporter

    private sealed interface LogFileCommand {
        data class Append(val line: String) : LogFileCommand
        data object Clear : LogFileCommand
        data class Flush(
            val acknowledgement: CompletableDeferred<Unit>,
        ) : LogFileCommand
    }

    // Appends are shed via trySend when the buffer is full (drop-newest,
    // acceptable for log lines); Clear and Flush use send and are never
    // dropped.
    private val fileCommands =
        Channel<LogFileCommand>(capacity = WRITER_BUFFER_CAPACITY)

    private val droppedLineCount = AtomicInt(0)

    // Write-failure accounting: the count folds into an honest marker
    // on the next successful append; the once-per-process flag keeps a
    // full disk from spamming the crash backend (one non-fatal is
    // signal, five hundred are noise).
    private val fileWriteFailureCount = AtomicInt(0)
    private val hasReportedWriteFailure = AtomicBoolean(false)

    // Serializes all file access between the background writer and the
    // synchronous drain paths (ERROR flush, flushForCrash), so lines
    // never interleave and rotation checks stay race-free.
    private val fileAccessLock = Any()

    init {
        // The single writer loop lives on the manager's confinement;
        // each command's blocking file IO is offloaded with onIo, per
        // the ConfinedManager rule. No confined state is touched: the
        // command execution uses only the atomic drop counter and the
        // file lock, which is what lets the drain paths share it.
        managerScope.launch {
            for (command in fileCommands) {
                onIo { executeFileCommand(command) }
            }
        }
    }

    /**
     * Executes one file command, blocking the calling thread. Safe
     * from the writer AND from synchronous drains: the channel hands
     * each command to exactly one consumer, and [fileAccessLock]
     * serializes the file itself.
     */
    private fun executeFileCommand(command: LogFileCommand) {
        when (command) {
            is LogFileCommand.Append -> {
                val droppedLines = droppedLineCount.exchange(0)
                val failedWrites = fileWriteFailureCount.exchange(0)
                synchronized(fileAccessLock) {
                    if (droppedLines > 0) {
                        appendToLogFile(
                            "[LogManager] $droppedLines log " +
                                "line(s) dropped under burst",
                        )
                    }
                    // The export shows its own gaps: if this marker
                    // write fails too, the count re-accumulates.
                    if (failedWrites > 0) {
                        appendToLogFile(
                            "[LogManager] $failedWrites file " +
                                "write(s) failed since the last " +
                                "successful append",
                        )
                    }
                    appendToLogFile(command.line)
                }
            }
            is LogFileCommand.Clear -> {
                val cleared =
                    synchronized(fileAccessLock) { deleteLogFile() }
                // Announce only after the delete actually ran,
                // preserving delete-then-announce order; trigger is
                // thread-safe from any consumer.
                if (cleared) eventManager?.trigger(LogsCleared)
            }
            is LogFileCommand.Flush ->
                command.acknowledgement.complete(Unit)
        }
    }

    fun debug(
        message: String,
        tag: String? = null,
        throwable: Throwable? = null,
    ) = log(LogLevel.DEBUG, message, tag, throwable)

    fun info(
        message: String,
        tag: String? = null,
        throwable: Throwable? = null,
    ) = log(LogLevel.INFO, message, tag, throwable)

    fun warn(
        message: String,
        tag: String? = null,
        throwable: Throwable? = null,
    ) = log(LogLevel.WARN, message, tag, throwable)

    fun error(
        message: String,
        tag: String? = null,
        throwable: Throwable? = null,
    ) = log(LogLevel.ERROR, message, tag, throwable)

    /**
     * Logs a crash the CrashReporter has ALREADY recorded as fatal:
     * identical ERROR formatting and drain policy, but never
     * forwarded to recordNonFatal, so a crash is counted exactly once
     * upstream. Only the AppComponent's uncaught-exception handler
     * calls this.
     */
    fun logFatal(message: String, throwable: Throwable) = log(
        LogLevel.ERROR,
        message,
        explicitTag = null,
        throwable = throwable,
        forwardAsNonFatal = false,
    )

    /**
     * Crash-context trace: ALWAYS forwarded to the CrashReporter's
     * bounded breadcrumb ring, and also logged at DEBUG when the
     * minimum level admits it, so debug builds keep their full file
     * trace. Below the DEBUG threshold the forward is the ONLY work:
     * no stack walk, no line formatting. Callers pass pre-sanitized
     * messages (EventManager.describePayload never prints string
     * contents).
     */
    fun breadcrumb(message: String, tag: String? = null) {
        runCatching { crashReporter.recordBreadcrumb(message) }
        log(LogLevel.DEBUG, message, tag, throwable = null)
    }

    /** Absolute path of the log file, or null while file logging is off. */
    val logFilePath: String?
        get() {
            if (!fileLoggingEnabled) return null
            val directory = logDirectoryPath ?: return null
            return "$directory/$logFileName"
        }

    /**
     * Full contents of the LIVE log file; empty when missing or
     * unreadable. Rotated history (the ".1" file) is not included.
     */
    fun readLogContents(): String {
        val path = logFilePath ?: return ""
        return runCatching {
            val filePath = path.toPath()
            if (!FileSystem.SYSTEM.exists(filePath)) null
            else FileSystem.SYSTEM.read(filePath) { readUtf8() }
        }.getOrNull() ?: ""
    }

    /**
     * Writes a quiescent snapshot of the FULL log history (rotated
     * file first, then the live file: oldest lines first) to a
     * dedicated export file beside the live log and returns its
     * absolute path. Flushes the writer first, so every line logged
     * before the call is included; the copy runs under the file
     * lock, so it can never observe a half-rotated state. Returns
     * null when file logging is off or nothing has been logged yet.
     * Overwritten on each call; deleted by [clearLogs] with the rest
     * of the history.
     */
    suspend fun writeExportSnapshot(): String? {
        val livePath = logFilePath ?: return null
        flush()
        return onIo {
            synchronized(fileAccessLock) {
                runCatching {
                    val sourcePaths = historyFilePaths(livePath)
                    if (sourcePaths.isEmpty()) {
                        return@runCatching null
                    }
                    val exportPath = exportLogFilePath(livePath)
                    FileSystem.SYSTEM.write(exportPath.toPath()) {
                        sourcePaths.forEach { historyPath ->
                            FileSystem.SYSTEM.source(historyPath)
                                .use { historySource ->
                                    writeAll(historySource)
                                }
                        }
                    }
                    exportPath
                }.getOrNull()
            }
        }
    }

    /** Rotated-then-live, existing files only: oldest lines first. */
    private fun historyFilePaths(livePath: String) =
        listOfNotNull(rotatedLogFilePath, livePath)
            .map { pathString -> pathString.toPath() }
            .filter { path -> FileSystem.SYSTEM.exists(path) }

    /** "base-app.log" exports as "base-app-export.log". */
    private fun exportLogFilePath(livePath: String): String {
        val extension = livePath.substringAfterLast('.', "")
        return if (extension.isEmpty()) {
            "$livePath-export"
        } else {
            livePath.substringBeforeLast('.') + "-export." + extension
        }
    }

    /**
     * Deletes the log HISTORY. Fire-and-forget: the delete runs on the
     * writer, [LogsCleared] fires once it actually happened, and
     * subsequent logging (including the clear announcement itself)
     * starts a fresh file, so an exported log begins with why it is
     * fresh rather than being absent.
     */
    fun clearLogs() {
        // Enqueued synchronously so the caller's own surrounding log
        // lines keep their order relative to the clear (a launched send
        // would let a following append race ahead and die with the old
        // file). Falls back to a suspending send only when the buffer
        // is full: Clear must never be dropped.
        if (fileCommands.trySend(LogFileCommand.Clear).isFailure) {
            managerScope.launch {
                runCatching { fileCommands.send(LogFileCommand.Clear) }
            }
        }
    }

    /**
     * Awaits every file command enqueued before this call. For callers
     * (tests, export flows) that need [readLogContents] to be exact.
     *
     * Caveat, shared with [flushForCrash]: a command the background
     * writer has already CLAIMED from the channel but not yet executed
     * cannot be awaited, so if a synchronous drain (an ERROR-level
     * line, [flushForCrash]) steals this call's marker during that
     * window, the await can complete marginally early. Absent a
     * concurrent drain, completion is exact.
     */
    suspend fun flush() {
        val acknowledgement = CompletableDeferred<Unit>()
        runCatching {
            fileCommands.send(LogFileCommand.Flush(acknowledgement))
            acknowledgement.await()
        }
    }

    /**
     * Synchronous, best-effort persistence for a dying process: drains
     * every queued file command on the CALLING thread, from any
     * thread, suspending nothing. Called by the uncaught-exception
     * handler the AppComponent installs, after the crash has been
     * logged, so the log file ends with the crash. Best-effort: a line
     * the background writer has already claimed but not yet written
     * cannot be waited on here. Never throws.
     */
    fun flushForCrash() {
        drainPendingFileCommands()
    }

    /**
     * Steals queued commands from the channel and executes them
     * synchronously, up to [maxCommandCount]. Racing the background
     * writer is safe: the channel delivers each command to exactly
     * one consumer and file access is locked. Internal so the spec
     * can pin the bound directly.
     */
    internal fun drainPendingFileCommands(
        maxCommandCount: Int = Int.MAX_VALUE,
    ) {
        runCatching {
            var executedCommandCount = 0
            while (executedCommandCount < maxCommandCount) {
                val command =
                    fileCommands.tryReceive().getOrNull() ?: break
                executeFileCommand(command)
                executedCommandCount += 1
            }
        }
    }

    /**
     * Stops the file writer for good. Buffered lines may be dropped;
     * callers that need them persisted (tests, export flows) should
     * [flush] first. A closed manager still writes to Logcat, never to
     * the file. Called by the AppComponent's close().
     */
    override fun close() {
        fileCommands.close()
        super.close()
    }

    private fun log(
        level: LogLevel,
        message: String,
        explicitTag: String?,
        throwable: Throwable?,
        forwardAsNonFatal: Boolean = true,
    ) {
        if (level.priority < minimumLogLevel.priority) return

        // One capture serves both the location column and the default tag.
        val callSite = currentCallSite()
        val tag = explicitTag ?: callSite?.className ?: DEFAULT_TAG
        val logLine = buildLogLine(level, tag, message, callSite)
        // Guarded like the file write: "logging never throws" includes
        // the platform mirror (the Logcat adapter in :app; a no-op on
        // the JVM).
        runCatching { platformWriter.write(level, tag, logLine, throwable) }
        // Accepted WARN and ERROR lines join the crash report's
        // bounded breadcrumb ring: the warning trail leading up to a
        // crash. Guarded: a broken reporter must never make "logging
        // never throws" a lie.
        if (level.priority >= LogLevel.WARN.priority) {
            runCatching { crashReporter.recordBreadcrumb(logLine) }
        }
        // An ERROR line IS a non-fatal: the attached throwable when
        // present, a call-site-stamped LoggedError otherwise, so every
        // handled-but-serious failure is counted in the crash backend.
        // logFatal() opts out: its crash was already recorded as fatal.
        if (level == LogLevel.ERROR && forwardAsNonFatal) {
            runCatching {
                crashReporter.recordNonFatal(
                    throwable ?: buildLoggedError(logLine, callSite),
                )
            }
        }
        if (fileLoggingEnabled && logFilePath != null) {
            val enqueued = fileCommands.trySend(
                LogFileCommand.Append(
                    if (throwable == null) logLine
                    else "$logLine\n${throwable.stackTraceToString()}",
                ),
            )
            // Bursts beyond the buffer shed newest-first; count them so
            // the writer can leave an honest marker instead of a silent
            // gap.
            if (enqueued.isFailure) droppedLineCount.fetchAndAdd(1)
            // Flush policy: ERROR lines reach disk before this call
            // returns WHEN fewer than the limit of commands are queued
            // ahead (the common near-empty case: the fresh error line
            // is the tail, so it is usually reached); otherwise the
            // background writer lands it moments later. The bound
            // keeps a full 512-command queue from stalling the calling
            // thread, often Main, into an ANR; a genuine crash still
            // gets the unbounded drain via flushForCrash.
            if (level == LogLevel.ERROR) {
                drainPendingFileCommands(
                    maxCommandCount = ERROR_DRAIN_COMMAND_LIMIT,
                )
            }
        }
    }

    /** Builds the synthesized non-fatal for message-only ERROR lines;
     * see [LoggedError]. */
    private fun buildLoggedError(
        logLine: String,
        callSite: CallSite?,
    ): LoggedError {
        val loggedError = LoggedError(logLine)
        if (callSite != null) {
            loggedError.stackTrace = arrayOf(
                StackTraceElement(
                    callSite.className ?: "UnknownClass",
                    callSite.methodName ?: "unknownMethod",
                    callSite.fileName,
                    callSite.lineNumber ?: -1,
                ),
            )
        }
        return loggedError
    }

    /** Assembles the fixed-width, pipe-separated log columns. */
    private fun buildLogLine(
        level: LogLevel,
        tag: String,
        message: String,
        callSite: CallSite?,
    ): String {
        val timestamp = currentUtcTimestamp().padEnd(TIMESTAMP_WIDTH)
        val levelName = level.name.padEnd(LEVEL_WIDTH).take(LEVEL_WIDTH)
        val threadName = currentThreadName()
            .take(THREAD_WIDTH).padEnd(THREAD_WIDTH)
        val location = formatCallSite(callSite)
            .take(LOCATION_WIDTH).padEnd(LOCATION_WIDTH)
        return "$timestamp | $levelName | $threadName | $location | " +
            "$tag: $message"
    }

    /** "ClassName.methodName() (FileName.kt:42)", tolerating gaps. */
    private fun formatCallSite(callSite: CallSite?): String {
        if (callSite == null) return "unknown"
        val classPart = callSite.className?.plus(".") ?: ""
        val methodPart = callSite.methodName?.plus("()") ?: "unknown"
        val filePart = callSite.fileName ?: "unknown"
        val linePart = callSite.lineNumber?.toString() ?: "?"
        return "$classPart$methodPart ($filePart:$linePart)"
    }

    /** Path the live file rotates to, ".1" before the extension. */
    private val rotatedLogFilePath: String?
        get() {
            val path = logFilePath ?: return null
            val extension = path.substringAfterLast('.', "")
            return if (extension.isEmpty()) {
                "$path.1"
            } else {
                "${path.substringBeforeLast('.')}.1.$extension"
            }
        }

    /** Callers hold [fileAccessLock]; see [executeFileCommand]. */
    private fun appendToLogFile(logLine: String) {
        val path = logFilePath ?: return
        runCatching {
            rotateLogFileIfOversized(path)
            FileSystem.SYSTEM
                .appendingSink(path.toPath(), mustExist = false)
                .buffer()
                .use { sink -> sink.writeUtf8(logLine + "\n") }
        }.onFailure { writeFailure ->
            recordFileWriteFailure(writeFailure)
        }
    }

    /**
     * A failed file write reports through the channels that still
     * work: Logcat immediately, and the crash backend ONCE per
     * process (a full disk fails every append; one non-fatal is
     * signal). The running count becomes an honest marker line on
     * the next successful append (see [executeFileCommand]), so an
     * exported log shows its own gaps.
     */
    private fun recordFileWriteFailure(writeFailure: Throwable) {
        fileWriteFailureCount.fetchAndAdd(1)
        runCatching {
            platformWriter.write(
                LogLevel.ERROR,
                "LogManager",
                "Log file write failed: ${writeFailure.message}",
                writeFailure,
            )
        }
        if (hasReportedWriteFailure.compareAndSet(
                expectedValue = false,
                newValue = true,
            )
        ) {
            runCatching { crashReporter.recordNonFatal(writeFailure) }
        }
    }

    /**
     * Size-capped rotation: once the live file reaches the cap it
     * becomes the single ".1" history file (replacing any previous
     * one) and appends continue into a fresh live file.
     */
    private fun rotateLogFileIfOversized(livePath: String) {
        val rotatedPath = rotatedLogFilePath ?: return
        val liveFilePath = livePath.toPath()
        val liveSize =
            FileSystem.SYSTEM.metadataOrNull(liveFilePath)?.size ?: 0L
        if (liveSize < maxLogFileSizeBytes) return
        runCatching {
            FileSystem.SYSTEM.delete(
                rotatedPath.toPath(),
                mustExist = false,
            )
            FileSystem.SYSTEM.atomicMove(
                liveFilePath,
                rotatedPath.toPath(),
            )
        }
    }

    /**
     * Deletes the live AND rotated files (clearing history means all
     * of it). Callers hold [fileAccessLock].
     */
    private fun deleteLogFile(): Boolean {
        val path = logFilePath ?: return true
        return runCatching {
            rotatedLogFilePath?.let { rotatedPath ->
                FileSystem.SYSTEM.delete(
                    rotatedPath.toPath(),
                    mustExist = false,
                )
            }
            // "Clear logs" clears ALL of it, the export copy too.
            FileSystem.SYSTEM.delete(
                exportLogFilePath(path).toPath(),
                mustExist = false,
            )
            FileSystem.SYSTEM.delete(path.toPath(), mustExist = false)
            true
        }.getOrElse { deleteFailure ->
            // A failed clear must not be invisible: the file cannot
            // carry the report, so the platform mirror does.
            runCatching {
                platformWriter.write(
                    LogLevel.ERROR,
                    "LogManager",
                    "Log history delete failed: " +
                        "${deleteFailure.message}",
                    deleteFailure,
                )
            }
            false
        }
    }

    private fun currentUtcTimestamp(): String = clock.now()
        .toLocalDateTime(TimeZone.UTC)
        .format(LOG_TIMESTAMP_FORMAT)

    companion object {
        const val DEFAULT_LOG_FILE_NAME = "base-app.log"

        // Rotation cap: two files of this size is ample demo/support
        // history while staying invisible next to any app's cache use.
        const val DEFAULT_MAX_LOG_FILE_BYTES = 512L * 1024L
        private const val DEFAULT_TAG = "App"
        private const val WRITER_BUFFER_CAPACITY = 512

        // Bounds the synchronous ERROR-level drain: ample for the
        // common near-empty queue, small enough that a full
        // 512-command queue can never stall the calling thread into
        // an ANR. flushForCrash keeps the unbounded drain.
        internal const val ERROR_DRAIN_COMMAND_LIMIT = 64

        // Fixed widths keep the pipe-separated columns aligned
        // across lines, so the file scans like a table.
        private const val TIMESTAMP_WIDTH = 25
        private const val LEVEL_WIDTH = 5
        private const val THREAD_WIDTH = 30
        private const val LOCATION_WIDTH = 60

        // "yyyy-MM-dd | HH:mm:ss.SSS" (UTC)
        private val LOG_TIMESTAMP_FORMAT = LocalDateTime.Format {
            year(); char('-'); monthNumber(); char('-'); day()
            char(' '); char('|'); char(' ')
            hour(); char(':'); minute(); char(':'); second()
            char('.'); secondFraction(3)
        }
    }
}

/** One resolved stack frame of the code that called the LogManager. */
internal data class CallSite(
    val fileName: String?,
    val className: String?,
    val methodName: String?,
    val lineNumber: Int?,
)

private fun currentCallSite(): CallSite? {
    // Skip every frame belonging to the LogManager itself (including
    // LogManagerKt, the generated class holding this file's top-level
    // functions). Matched by EXACT simple name: a substring check would
    // also skip callers merely named after it, such as LogManagerTest
    // or a consumer's LogManagerHelper.
    val callerFrame = Throwable().stackTrace.firstOrNull { element ->
        val simpleClassName = element.className
            .substringAfterLast('.').substringBefore('$')
        simpleClassName != "LogManager" &&
            simpleClassName != "LogManagerKt"
    } ?: return null

    // Demangle coroutine/lambda frames: a body launched inside
    // Class.method compiles to the inner class "Class${'$'}method${'$'}1" with
    // methodName "invokeSuspend"; report it as Class.method so tags and
    // the location column stay human.
    val mangledParts = callerFrame.className
        .substringAfterLast('.').split('$')
    val className = mangledParts.first()
    val methodName =
        if (mangledParts.size > 1 &&
            callerFrame.methodName in setOf("invoke", "invokeSuspend")
        ) {
            mangledParts[1]
        } else {
            callerFrame.methodName
        }

    return CallSite(
        fileName = callerFrame.fileName,
        className = className,
        methodName = methodName,
        lineNumber = callerFrame.lineNumber.takeIf { it >= 0 },
    )
}

private fun currentThreadName(): String =
    Thread.currentThread().name

