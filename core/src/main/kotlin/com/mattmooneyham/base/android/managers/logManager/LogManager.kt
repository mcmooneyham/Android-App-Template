package com.mattmooneyham.base.android.managers.logManager

import com.mattmooneyham.base.android.constants.AppNames
import com.mattmooneyham.base.android.constants.LogLevel
import com.mattmooneyham.base.android.managers.ConfinedManager
import com.mattmooneyham.base.android.managers.eventManager.EventManager
import com.mattmooneyham.base.android.managers.eventManager.SignalKey
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.Clock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.format
import kotlinx.datetime.format.char
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime
import okio.FileSystem
import okio.Path
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
    val maxDays: Int = LogManager.DEFAULT_MAX_LOG_DAYS,
    val maxTotalSizeBytes: Long = LogManager.DEFAULT_MAX_LOG_TOTAL_BYTES,
    /** Directory for the export zip; null disables the export. */
    val exportDirectoryPath: String? = null,
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
 * line), tag, and message. Lines go to the platform log mirror (the
 * PlatformLogWriter port: Logcat when :app wires AndroidLogWriter, a
 * no-op by default) and, when file logging is enabled and a directory
 * is configured, to a log file. Logging never throws: failures are
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
 * Log files roll daily and by size: each UTC day appends into its
 * own dated file (base_app-2026-01-01.log), and a file reaching
 * [maxLogFileSizeBytes] is renamed to the next numbered sibling
 * (base_app-2026-01-01.1.log) so appends continue into a fresh
 * file. Retention runs asynchronously on the manager's own scope,
 * never on an append or crash-drain path: files older than
 * [LogFileSettings.maxDays] days are deleted, then the oldest files
 * go until the total fits [LogFileSettings.maxTotalSizeBytes] (the
 * current day's live file is never deleted). [writeExportSnapshot]
 * zips the whole history for sharing.
 *
 * Provided as a singleton via AppComponent (in :app).
 *
 * @param fileSettings file-side policy (directory, name, roll and
 *   retention caps, on/off switch), grouped so the wiring stays
 *   within budget.
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
    private val fileLoggingEnabled = fileSettings.fileLoggingEnabled
    private val maxLogFileSizeBytes = fileSettings.maxFileSizeBytes
    private val maxLogDays = fileSettings.maxDays
    private val maxLogTotalSizeBytes = fileSettings.maxTotalSizeBytes
    private val exportDirectoryPath = fileSettings.exportDirectoryPath
    private val logFileStem = fileSettings.fileName.substringBeforeLast('.')
    private val logFileExtension =
        fileSettings.fileName.substringAfterLast('.', "")

    // Deletion (prune, clear) touches ONLY names matching this
    // pattern, so foreign files in the log directory are never removed.
    private val logFileNameRegex = Regex(
        "^${Regex.escape(logFileStem)}-\\d{4}-\\d{2}-\\d{2}" +
            "(\\.\\d+)?\\.${Regex.escape(logFileExtension)}$",
    )

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
    // never interleave and roll checks stay race-free.
    private val fileAccessLock = Any()

    // Serializes overlapping exports, which share one staging file and
    // one final zip path; the zip itself runs outside fileAccessLock
    // so logging never stalls behind it.
    private val exportMutex = Mutex()

    // Read and written only under fileAccessLock. Updated only after a
    // SUCCESSFUL prune, so a failed prune is retried by the next append.
    private var lastPruneDate: LocalDate? = null

    private val pruneInFlight = AtomicBoolean(false)

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
                    synchronized(fileAccessLock) { deleteLogFiles() }
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

    /**
     * Absolute path of the CURRENT UTC day's log file (which appends
     * create on demand), or null while file logging is off.
     */
    val logFilePath: String?
        get() {
            if (!fileLoggingEnabled) return null
            val directory = logDirectoryPath ?: return null
            return "$directory/$logFileStem-${currentUtcDate()}" +
                ".$logFileExtension"
        }

    /**
     * Full contents of the current day's log file; when nothing has
     * been appended today yet, the newest existing log file instead,
     * so callers polling across midnight never see a false empty.
     * Empty when there are no log files or the read fails. Older
     * files are not included.
     */
    fun readLogContents(): String {
        val path = logFilePath ?: return ""
        return runCatching {
            val todayPath = path.toPath()
            val sourcePath =
                if (FileSystem.SYSTEM.exists(todayPath)) todayPath
                else logFilesOldestFirst().lastOrNull()
            sourcePath?.let { existingPath ->
                FileSystem.SYSTEM.read(existingPath) { readUtf8() }
            }
        }.getOrNull() ?: ""
    }

    /**
     * Zips the FULL log history (every log file, oldest first) into a
     * dedicated export file in the configured export directory and
     * returns its absolute path. Flushes the writer first, so every
     * line logged before the call is included. The archive is
     * assembled beside the final name and atomically swapped in, and
     * overlapping calls are serialized, so a reader never observes a
     * partial zip. Returns null when file logging or the export
     * directory is off, nothing has been logged yet, or the write
     * fails. Overwritten on each call; deleted by [clearLogs] with
     * the rest of the history.
     */
    suspend fun writeExportSnapshot(): String? {
        if (logFilePath == null) return null
        val exportPath = exportZipFilePath ?: return null
        flush()
        return exportMutex.withLock {
            onIo { writeExportZip(exportPath) }
        }
    }

    /** Callers hold [exportMutex]; see [writeExportSnapshot]. */
    private fun writeExportZip(exportPath: String): String? {
        val stagingPath = "$exportPath.tmp"
        var sourcePaths = snapshotLogFilePaths()
        var attemptsLeft = EXPORT_ZIP_ATTEMPT_LIMIT
        while (sourcePaths.isNotEmpty()) {
            if (!zipFilesTo(stagingPath, sourcePaths)) return null
            attemptsLeft -= 1
            val currentPaths = snapshotLogFilePaths()
            // A roll mid-zip renames the live file away from the
            // snapshot, silently dropping the newest history; redo
            // until the name set is stable. The bounded final pass
            // ships whatever it saw.
            val fileSetStable = currentPaths.map(Path::name) ==
                sourcePaths.map(Path::name)
            if (fileSetStable || attemptsLeft == 0) {
                return runCatching {
                    synchronized(fileAccessLock) {
                        FileSystem.SYSTEM.atomicMove(
                            stagingPath.toPath(),
                            exportPath.toPath(),
                        )
                    }
                    exportPath
                }.getOrNull()
            }
            sourcePaths = currentPaths
        }
        // Nothing to archive, or a clear raced the zip: never hand
        // back an export the clear just deleted.
        runCatching {
            FileSystem.SYSTEM.delete(
                stagingPath.toPath(),
                mustExist = false,
            )
        }
        return null
    }

    private fun snapshotLogFilePaths(): List<Path> =
        synchronized(fileAccessLock) {
            runCatching { logFilesOldestFirst() }
                .getOrDefault(emptyList())
        }

    private fun zipFilesTo(
        zipPath: String,
        sourcePaths: List<Path>,
    ): Boolean = runCatching {
        ZipOutputStream(FileOutputStream(zipPath)).use { zipStream ->
            sourcePaths.forEach { sourcePath ->
                // Per-file guard: a source rolled or pruned away
                // mid-zip is skipped, not a failed export.
                runCatching {
                    zipStream.putNextEntry(ZipEntry(sourcePath.name))
                    FileSystem.SYSTEM.source(sourcePath)
                        .buffer()
                        .use { fileSource ->
                            fileSource.inputStream()
                                .copyTo(zipStream)
                        }
                    zipStream.closeEntry()
                }
            }
        }
    }.isSuccess

    private val exportZipFilePath: String?
        get() {
            if (!fileLoggingEnabled) return null
            val exportDirectory = exportDirectoryPath ?: return null
            return "$exportDirectory/$logFileStem-export.zip"
        }

    /**
     * Every log file in the directory, oldest first by the (date,
     * roll index) parsed from the name; the indexless live file sorts
     * after its day's rolls because a roll always predates it.
     */
    private fun logFilesOldestFirst(): List<Path> {
        val directory = logDirectoryPath ?: return emptyList()
        return FileSystem.SYSTEM.listOrNull(directory.toPath())
            .orEmpty()
            .filter { entry -> logFileNameRegex.matches(entry.name) }
            .sortedWith(
                compareBy(
                    { entry -> parsedLogFileDate(entry.name) },
                    { entry -> parsedLogRollIndex(entry.name) },
                ),
            )
    }

    private fun parsedLogFileDate(fileName: String): LocalDate =
        runCatching {
            LocalDate.parse(
                fileName.removePrefix("$logFileStem-")
                    .take(DATE_TEXT_LENGTH),
            )
        }.getOrDefault(LocalDate(year = 1970, month = 1, day = 1))

    private fun parsedLogRollIndex(fileName: String): Int =
        fileName.removePrefix("$logFileStem-")
            .drop(DATE_TEXT_LENGTH + 1)
            .substringBefore('.')
            .toIntOrNull() ?: Int.MAX_VALUE

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
     * one consumer and file access is locked. The bound is covered
     * behaviorally: LogManagerReportingSpec's burst test proves an
     * ERROR call stalls nothing and loses nothing.
     */
    private fun drainPendingFileCommands(
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
     * [flush] first. A closed manager still writes to the platform
     * mirror, never to
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
        // Gate on the directory, not logFilePath: the hot path skips
        // building the dated name entirely.
        if (fileLoggingEnabled && logDirectoryPath != null) {
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

    /** Callers hold [fileAccessLock]; see [executeFileCommand]. */
    private fun appendToLogFile(logLine: String) {
        val path = logFilePath ?: return
        runCatching {
            // Crash drains run this path: schedule retention instead
            // of pruning inline, so a dying process never scans here.
            val today = currentUtcDate()
            if (lastPruneDate != null && lastPruneDate != today) {
                schedulePrune()
            }
            logDirectoryPath?.let { directory ->
                FileSystem.SYSTEM.createDirectories(
                    directory.toPath(),
                    mustCreate = false,
                )
            }
            rollLogFileIfOversized(path)
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
     * work: the platform mirror immediately, and the crash backend ONCE per
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
     * Size-capped roll: once the live file reaches the cap it is
     * renamed to the day's next numbered sibling and appends continue
     * into a fresh live file. Callers hold [fileAccessLock].
     */
    private fun rollLogFileIfOversized(livePath: String) {
        val liveFilePath = livePath.toPath()
        val liveSize =
            FileSystem.SYSTEM.metadataOrNull(liveFilePath)?.size ?: 0L
        if (liveSize < maxLogFileSizeBytes) return
        runCatching {
            FileSystem.SYSTEM.atomicMove(
                liveFilePath,
                nextRolledFilePath(liveFilePath),
            )
        }
        schedulePrune()
    }

    /** "stem-date.log" rolls to "stem-date.<highest + 1>.log". */
    private fun nextRolledFilePath(liveFilePath: Path): Path {
        val rollBaseName =
            liveFilePath.name.removeSuffix(".$logFileExtension")
        val rollIndexRegex = Regex(
            "^${Regex.escape(rollBaseName)}\\.(\\d+)" +
                "\\.${Regex.escape(logFileExtension)}$",
        )
        val parentDirectory = liveFilePath.parent
        val highestRollIndex = parentDirectory
            ?.let { directory -> FileSystem.SYSTEM.listOrNull(directory) }
            .orEmpty()
            .mapNotNull { entry ->
                rollIndexRegex.matchEntire(entry.name)
                    ?.groupValues?.get(1)?.toIntOrNull()
            }
            .maxOrNull() ?: 0
        val rolledName =
            "$rollBaseName.${highestRollIndex + 1}.$logFileExtension"
        return parentDirectory?.resolve(rolledName)
            ?: rolledName.toPath()
    }

    /**
     * Deletes every log file plus the export zip (clearing history
     * means all of it); files not matching the log naming pattern are
     * left alone. Callers hold [fileAccessLock].
     */
    private fun deleteLogFiles(): Boolean {
        val directory = logDirectoryPath ?: return true
        return runCatching {
            exportZipFilePath?.let { exportPath ->
                FileSystem.SYSTEM.delete(
                    exportPath.toPath(),
                    mustExist = false,
                )
            }
            val directoryEntries =
                FileSystem.SYSTEM.listOrNull(directory.toPath())
                    ?: return@runCatching true
            directoryEntries
                .filter { entry ->
                    logFileNameRegex.matches(entry.name)
                }
                .forEach { logFile ->
                    FileSystem.SYSTEM.delete(logFile, mustExist = false)
                }
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

    /**
     * Runs the initial retention prune, deferred out of construction
     * per the init budget (no IO in the constructor).
     */
    override fun start() {
        schedulePrune()
    }

    /**
     * Schedules one retention prune on the manager scope; a prune
     * already in flight absorbs the request.
     */
    private fun schedulePrune() {
        if (!pruneInFlight.compareAndSet(
                expectedValue = false,
                newValue = true,
            )
        ) {
            return
        }
        managerScope.launch {
            try {
                onIo {
                    val today = currentUtcDate()
                    synchronized(fileAccessLock) {
                        pruneLogDirectory(today)
                    }
                }
            } finally {
                pruneInFlight.store(false)
            }
        }
    }

    /**
     * Retention: deletes log files older than [maxLogDays] days, then
     * the oldest files (by filesystem modification time) until the
     * total fits [maxLogTotalSizeBytes]. The current live file is
     * never deleted, so usage may transiently reach the total cap
     * plus one file. Callers hold [fileAccessLock].
     */
    private fun pruneLogDirectory(today: LocalDate) {
        if (logFilePath == null) return
        val pruneSucceeded = runCatching {
            val oldestRetainedDate =
                today.minus(maxLogDays - 1, DateTimeUnit.DAY)
            val retainedFiles = logFilesOldestFirst()
                .filterNot { logFile ->
                    val fileDate = parsedLogFileDate(logFile.name)
                    val expired = fileDate < oldestRetainedDate
                    if (expired) {
                        FileSystem.SYSTEM.delete(
                            logFile,
                            mustExist = false,
                        )
                    }
                    expired
                }
            enforceTotalSizeCap(retainedFiles)
        }.isSuccess
        if (pruneSucceeded) lastPruneDate = today
    }

    private fun enforceTotalSizeCap(retainedFiles: List<Path>) {
        val livePath = logFilePath?.toPath()
        var totalSizeBytes = retainedFiles.sumOf { logFile ->
            FileSystem.SYSTEM.metadataOrNull(logFile)?.size ?: 0L
        }
        val evictionCandidates = retainedFiles
            .filterNot { logFile -> logFile == livePath }
            .sortedBy { logFile ->
                FileSystem.SYSTEM.metadataOrNull(logFile)
                    ?.lastModifiedAtMillis ?: 0L
            }
        for (evictionCandidate in evictionCandidates) {
            if (totalSizeBytes <= maxLogTotalSizeBytes) break
            val candidateSizeBytes =
                FileSystem.SYSTEM.metadataOrNull(evictionCandidate)
                    ?.size ?: 0L
            FileSystem.SYSTEM.delete(
                evictionCandidate,
                mustExist = false,
            )
            totalSizeBytes -= candidateSizeBytes
        }
    }

    private fun currentUtcTimestamp(): String = clock.now()
        .toLocalDateTime(TimeZone.UTC)
        .format(LOG_TIMESTAMP_FORMAT)

    private fun currentUtcDate(): LocalDate = clock.now()
        .toLocalDateTime(TimeZone.UTC)
        .date

    companion object {
        const val DEFAULT_LOG_FILE_NAME = AppNames.LOG_FILE_NAME

        // Roll cap for a single file; the total-size cap below, not
        // this, is what bounds overall disk use.
        const val DEFAULT_MAX_LOG_FILE_BYTES = 10L * 1024L * 1024L

        /** Days of log history kept, the current day included. */
        const val DEFAULT_MAX_LOG_DAYS = 7

        /**
         * Retention cap across all log files. Pruning runs
         * asynchronously after a roll, so disk use may transiently
         * reach this cap plus one file of [DEFAULT_MAX_LOG_FILE_BYTES].
         */
        const val DEFAULT_MAX_LOG_TOTAL_BYTES = 250L * 1024L * 1024L

        private val DATE_TEXT_LENGTH = "yyyy-MM-dd".length
        private const val DEFAULT_TAG = "App"
        private const val WRITER_BUFFER_CAPACITY = 512

        // Two zip passes absorb the single roll that can plausibly
        // land mid-export; the final pass ships regardless.
        private const val EXPORT_ZIP_ATTEMPT_LIMIT = 2

        // Bounds the synchronous ERROR-level drain: ample for the
        // common near-empty queue, small enough that a full
        // 512-command queue can never stall the calling thread into
        // an ANR. flushForCrash keeps the unbounded drain.
        private const val ERROR_DRAIN_COMMAND_LIMIT = 64

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

