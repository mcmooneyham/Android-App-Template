package com.mattmooneyham.base.android.managers

import com.mattmooneyham.base.android.managers.logManager.LogManager
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext

/**
 * Base class giving every manager the same concurrency rails:
 *
 * - A NAMED SERIAL CONFINEMENT: one thread's worth of
 *   Dispatchers.Default, named after the manager so stack traces and
 *   coroutine dumps identify the owner. Because [managerScope] executes
 *   at most one coroutine at a time, plain `var` state is safe and
 *   check-then-set sequences are atomic between the manager's own
 *   coroutines, with no locks and no Main-dispatcher dependency.
 * - A SUPERVISED SCOPE WITH AN EXCEPTION HANDLER: an uncaught coroutine
 *   exception crashes an Android app, so the handler is load-bearing,
 *   not cosmetic. It logs through the injected LogManager; a failed
 *   child never cancels its siblings (SupervisorJob).
 * - OFFLOAD HELPERS [onIo] and [onCpu] for work that must leave the
 *   confinement. The rule when using them: READ confined state BEFORE
 *   offloading (capture it into locals), WRITE confined state AFTER
 *   returning. Never touch the manager's mutable fields inside the
 *   offloaded block, or the serial-confinement guarantee is lost.
 *
 * Subclasses launch everything on [managerScope] and get [close] for
 * free; override it when extra teardown (channels, platform callbacks)
 * must happen, calling `super.close()` last.
 *
 * @param managerName names the confinement for diagnostics.
 * @param failureLogManager receives uncaught-exception reports; null
 *   only for the LogManager itself, which cannot safely log through its
 *   own failing machinery.
 */
abstract class ConfinedManager(
    managerName: String,
    failureLogManager: LogManager?,
) {

    /**
     * The manager's serial home. All mutable-state access belongs here;
     * public entry points that are callable from any thread should
     * immediately launch onto this scope.
     */
    protected val managerScope = CoroutineScope(
        SupervisorJob() +
            Dispatchers.Default.limitedParallelism(1, managerName) +
            CoroutineExceptionHandler { _, throwable ->
                failureLogManager?.error(
                    "$managerName coroutine failed: ${throwable.message}",
                    throwable = throwable,
                )
            },
    )

    /**
     * Runs [block] on the IO pool for blocking work (files, sockets)
     * and returns to the confinement. Read confined state before
     * calling, write it after returning.
     */
    protected suspend fun <ResultType> onIo(
        block: suspend CoroutineScope.() -> ResultType,
    ): ResultType = withContext(Dispatchers.IO, block)

    /**
     * Runs [block] on the full Default pool for CPU-heavy work
     * (parsing, hashing) and returns to the confinement. Read confined
     * state before calling, write it after returning.
     */
    protected suspend fun <ResultType> onCpu(
        block: suspend CoroutineScope.() -> ResultType,
    ): ResultType = withContext(Dispatchers.Default, block)

    /**
     * Cancels every coroutine on [managerScope]. A closed manager
     * cannot be restarted; construct a new one. Called by the
     * AppComponent's close() in reverse construction order.
     */
    open fun close() {
        managerScope.cancel()
    }
}
