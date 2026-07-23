package com.mattmooneyham.base.android.managers

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import com.mattmooneyham.base.android.constants.LogLevel
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.Clock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.format
import kotlinx.datetime.format.char
import kotlinx.datetime.toLocalDateTime
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.SYSTEM
import okio.buffer
import okio.use

// Signal: the log history was cleared. Does not replay, so late
// subscribers never observe a stale "cleared" notification.
object LogsCleared : SignalKey(eventName = "log.Cleared")

/**
 * Central logging entry point for the SDK and its host apps. Every line
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
 * the writer when determinism is needed, e.g. in tests).
 *
 * Provided as a singleton via
 * [com.mattmooneyham.base.android.di.AppComponent].
 */
@OptIn(ExperimentalAtomicApi::class)
class LogManager(
    private val logDirectoryPath: String?,
    private val logFileName: String = DEFAULT_LOG_FILE_NAME,
    private val minimumLogLevel: LogLevel = LogLevel.DEBUG,
    private val fileLoggingEnabled: Boolean = true,
    private val eventManager: EventManager? = null,
) {

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

    private val fileWriterScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO +
            CoroutineExceptionHandler { _, _ ->
                // The writer must never take the app down; individual
                // commands already guard their own IO with runCatching.
            },
    )

    private val droppedLineCount = AtomicInt(0)

    init {
        fileWriterScope.launch {
            for (command in fileCommands) {
                when (command) {
                    is LogFileCommand.Append -> {
                        val droppedLines = droppedLineCount.exchange(0)
                        if (droppedLines > 0) {
                            appendToLogFile(
                                "[LogManager] $droppedLines log line(s) " +
                                    "dropped under burst",
                            )
                        }
                        appendToLogFile(command.line)
                    }
                    is LogFileCommand.Clear -> {
                        val cleared = deleteLogFile()
                        // Announce only after the delete actually ran,
                        // preserving delete-then-announce order; trigger
                        // is thread-safe from the writer.
                        if (cleared) eventManager?.trigger(LogsCleared)
                    }
                    is LogFileCommand.Flush ->
                        command.acknowledgement.complete(Unit)
                }
            }
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

    /** Absolute path of the log file, or null while file logging is off. */
    val logFilePath: String?
        get() {
            if (!fileLoggingEnabled) return null
            val directory = logDirectoryPath ?: return null
            return "$directory/$logFileName"
        }

    /** Full contents of the log file; empty when missing or unreadable. */
    fun readLogContents(): String {
        val path = logFilePath ?: return ""
        return runCatching {
            val filePath = path.toPath()
            if (!FileSystem.SYSTEM.exists(filePath)) null
            else FileSystem.SYSTEM.read(filePath) { readUtf8() }
        }.getOrNull() ?: ""
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
            fileWriterScope.launch {
                runCatching { fileCommands.send(LogFileCommand.Clear) }
            }
        }
    }

    /**
     * Awaits every file command enqueued before this call. For callers
     * (tests, export flows) that need [readLogContents] to be exact.
     */
    suspend fun flush() {
        val acknowledgement = CompletableDeferred<Unit>()
        runCatching {
            fileCommands.send(LogFileCommand.Flush(acknowledgement))
            acknowledgement.await()
        }
    }

    /**
     * Stops the file writer for good. Buffered lines may be dropped;
     * callers that need them persisted (tests, export flows) should
     * [flush] first. A closed manager still writes to Logcat, never to
     * the file. Called by the AppComponent's close().
     */
    fun close() {
        fileCommands.close()
        fileWriterScope.cancel()
    }

    private fun log(
        level: LogLevel,
        message: String,
        explicitTag: String?,
        throwable: Throwable?,
    ) {
        if (level.priority < minimumLogLevel.priority) return

        // One capture serves both the location column and the default tag.
        val callSite = currentCallSite()
        val tag = explicitTag ?: callSite?.className ?: DEFAULT_TAG
        val logLine = buildLogLine(level, tag, message, callSite)
        // Guarded like the file write: "logging never throws" includes the
        // platform logger (absent, for example, on JVM host tests).
        runCatching { writePlatformLog(level, tag, logLine, throwable) }
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
        }
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

    private fun appendToLogFile(logLine: String) {
        val path = logFilePath ?: return
        runCatching {
            FileSystem.SYSTEM
                .appendingSink(path.toPath(), mustExist = false)
                .buffer()
                .use { sink -> sink.writeUtf8(logLine + "\n") }
        }
    }

    private fun deleteLogFile(): Boolean {
        val path = logFilePath ?: return true
        return runCatching {
            FileSystem.SYSTEM.delete(path.toPath(), mustExist = false)
            true
        }.getOrDefault(false)
    }

    private fun currentUtcTimestamp(): String = Clock.System.now()
        .toLocalDateTime(TimeZone.UTC)
        .format(LOG_TIMESTAMP_FORMAT)

    companion object {
        const val DEFAULT_LOG_FILE_NAME = "base-sdk.log"
        private const val DEFAULT_TAG = "App"
        private const val WRITER_BUFFER_CAPACITY = 512

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

/** Writes one line to Logcat. */
private fun writePlatformLog(
    level: LogLevel,
    tag: String,
    message: String,
    throwable: Throwable?,
) {
    when (level) {
        LogLevel.DEBUG -> Log.d(tag, message, throwable)
        LogLevel.INFO -> Log.i(tag, message, throwable)
        LogLevel.WARN -> Log.w(tag, message, throwable)
        LogLevel.ERROR -> Log.e(tag, message, throwable)
    }
}

private fun currentCallSite(): CallSite? {
    // Skip every frame belonging to the LogManager itself (including the
    // generated file classes for these functions). Matched by EXACT simple
    // name: a substring check would also skip callers merely named after
    // it, such as LogManagerTest or a consumer's LogManagerHelper.
    val callerFrame = Throwable().stackTrace.firstOrNull { element ->
        val simpleClassName = element.className
            .substringAfterLast('.').substringBefore('$')
        simpleClassName != "LogManager" &&
            simpleClassName != "LogManagerKt" &&
            simpleClassName != "LogManager_androidKt"
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

// A library module cannot read the app's BuildConfig.DEBUG; the reliable
// debuggability signal is the FLAG_DEBUGGABLE bit on the application the
// host already hands over as platformContext.
internal fun defaultMinimumLogLevel(platformContext: Any?): LogLevel {
    val applicationFlags = (platformContext as? Context)
        ?.applicationInfo?.flags ?: 0
    val isDebuggable =
        applicationFlags and ApplicationInfo.FLAG_DEBUGGABLE != 0
    return if (isDebuggable) LogLevel.DEBUG else LogLevel.INFO
}

