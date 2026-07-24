package com.mattmooneyham.base.android.managers.eventManager

import com.mattmooneyham.base.android.managers.logManager.LogManager
import java.lang.ref.WeakReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlin.collections.iterator

/**
 * Central event hub:
 *
 * - One-shot pub/sub keyed by [StateKey] (typed payload, replayed) or
 *   [SignalKey] (payloadless, one-shot).
 * - For state keys, the most recent payload is cached and replayed to
 *   new listeners; a listener only receives a replay if the event has
 *   been triggered at least once. Signal keys never replay.
 * - The manager OWNS every event stream ([flowsByKey]); keys are pure
 *   identifiers. Rebuilding the manager therefore drops all cached
 *   state, and [resetSessionReplayCaches] clears the replay caches of
 *   session-lifetime keys in place (logout / account switch).
 * - Listeners are automatically removed when their owner is deallocated
 *   (weak owner tracking). The PRIMARY contract is still that a
 *   subscription lives exactly as long as its owner, which makes leaks
 *   a matter of object lifetime (visible in a memory profiler) rather
 *   than of forgotten remove calls scattered across screens. For
 *   deterministic teardown (session end, tests) [unsubscribeOwner]
 *   detaches an owner's subscriptions immediately. Dead registrations
 *   are additionally swept eagerly on every new registration and pruned
 *   on delivery.
 * - All listener callbacks are dispatched on the main queue. A listener
 *   that throws is logged loudly and KEPT ALIVE; one bad payload must
 *   not silently kill a screen's subscription.
 *
 * Ordering contract (the five rules every consumer may rely on):
 *  1. Delivery order ACROSS different keys is unspecified; never
 *     assume event A on one key lands before event B on another.
 *  2. Per-key delivery is in trigger order.
 *  3. UI listeners always run on the main dispatcher.
 *  4. A listener never observes a replay older than the latest
 *     trigger: the replay cache is updated synchronously inside
 *     trigger, before the call returns.
 *  5. Triggers are synchronous and never block; under overflow the
 *     OLDEST buffered event is dropped (latest wins), never the
 *     caller's thread.
 *
 * Publishers and subscribers are compile-time typed via [StateKey]'s
 * generic parameter; the runtime [AnyEventKey.payloadType] check guards
 * type-erased paths as a backstop.
 *
 * Example usage:
 * ```
 * // Trigger (typed; a wrong payload type does not compile)
 * eventManager.trigger(NetworkConnectivityChanged, true)
 *
 * // Anywhere (owner-based, auto-removed with the owner)
 * eventManager.listenTo(LogsCleared, owner = this) { ... }
 *
 * // Compose: val isOnline by
 * //     eventState(NetworkConnectivityChanged, initialValue = false)
 *
 * // Sync read (replaying keys only)
 * val isOnline =
 *     eventManager.currentValue(NetworkConnectivityChanged)
 *         as? Boolean ?: false
 * ```
 *
 * The managers PUBLISH here; everything else only listens. Compose
 * observes via the eventState/eventStateOrNull composables in
 * EventManagerCompose.kt.
 */
class EventManager {

    // Backstop only: listener throws are caught and logged inside
    // collect; anything that still reaches the handler is logged too so
    // no failure is ever silent.
    private val listenerScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main +
            CoroutineExceptionHandler { _, throwable ->
                logManager?.error(
                    "Event listener coroutine failed: ${throwable.message}",
                    throwable = throwable,
                )
            },
    )

    // Serial bookkeeping scope: dead-owner sweeps run here, off the
    // subscribing caller's thread and off Main. The Main dispatcher is
    // reserved for DELIVERY (rule 3 above); registration housekeeping
    // no longer rides it.
    private val busScope = CoroutineScope(
        SupervisorJob() +
            Dispatchers.Default.limitedParallelism(1, "EventManagerBus") +
            CoroutineExceptionHandler { _, throwable ->
                logManager?.error(
                    "Event bookkeeping failed: ${throwable.message}",
                    throwable = throwable,
                )
            },
    )

    private var logManager: LogManager? = null

    // Streams live HERE, not on the key objects, so no replay cache can
    // outlive this manager instance. Guarded by a plain monitor lock
    // rather than a coroutine confinement: trigger() must stay
    // synchronous and callable from ANY thread, and the critical
    // section is only a map lookup/insert plus a never-blocking
    // tryEmit, so a lock is the simplest correct choice with no
    // dispatcher hop. Emits stay INSIDE the lock so an in-flight
    // trigger cannot re-commit a pre-reset payload after
    // resetSessionReplayCaches cleared its key.
    private val flowsLock = Any()
    private val flowsByKey =
        HashMap<AnyEventKey, MutableSharedFlow<Any?>>()

    // Every live subscription, so dead owners can be swept eagerly on
    // the next registration instead of waiting for their event to fire.
    // Guarded by a lock rather than confined to busScope because two
    // mutations must be SYNCHRONOUS: the append in listenTo (so an
    // immediate unsubscribeOwner cannot miss it) and unsubscribeOwner
    // itself (immediate detach is its contract). Sweeps, which have no
    // urgency, run on busScope. Internal for the lifecycle tests.
    private val registrationsLock = Any()
    internal val registrations = mutableListOf<Registration>()

    internal class Registration(val ownerRef: Any, val job: Job)

    /**
     * Hooks up trigger tracing and contract-violation reporting. Called
     * once during startup as soon as the LogManager exists (the
     * EventManager is necessarily constructed first).
     */
    fun attachLogManager(logManager: LogManager) {
        this.logManager = logManager
    }

    /**
     * Fires [key] with a compile-time-checked payload. All listeners
     * are invoked on the main queue; the runtime type check backstops
     * type-erased call paths.
     */
    fun <PayloadType : Any> trigger(
        key: StateKey<PayloadType>,
        payload: PayloadType,
    ) {
        publish(key, payload)
    }

    /** Fires a payloadless signal key. */
    fun trigger(key: SignalKey) {
        publish(key, null)
    }

    /**
     * Subscribes with a typed callback. The listener is automatically
     * removed after [owner] is deallocated. If a replaying key was
     * previously triggered, the cached value is replayed immediately.
     * Callbacks run on the main queue and survive their own exceptions.
     */
    fun <PayloadType : Any> listenTo(
        key: StateKey<PayloadType>,
        owner: Any,
        onEvent: (PayloadType) -> Unit,
    ) {
        subscribe(key, owner) { payload ->
            // Safe: publish() only emits validated payloads.
            @Suppress("UNCHECKED_CAST")
            onEvent(payload as PayloadType)
        }
    }

    /** Subscribes to a payloadless signal key. */
    fun listenTo(key: SignalKey, owner: Any, onSignal: () -> Unit) {
        subscribe(key, owner) { onSignal() }
    }

    /**
     * Immediately detaches every subscription registered with [owner]
     * (matched by identity). Unlike the weak-owner sweep, which waits
     * for garbage collection, this cancels the listeners right away; no
     * further callbacks are delivered. Use it for deterministic
     * teardown such as session end.
     */
    fun unsubscribeOwner(owner: Any) {
        val detachedJobs = mutableListOf<Job>()
        synchronized(registrationsLock) {
            registrations.removeAll { registration ->
                val ownsIt = ownerReferent(registration.ownerRef) === owner
                if (ownsIt) detachedJobs += registration.job
                ownsIt
            }
        }
        // Cancel outside the lock; Job.cancel is thread-safe.
        detachedJobs.forEach { job -> job.cancel() }
    }

    /**
     * Clears the cached replay value of every SESSION-lifetime key
     * (see [EventLifetime]), so a new login cannot observe the previous
     * user's state. APP-lifetime keys (device/process facts such as
     * connectivity) keep their cache. Subscriptions are untouched: only
     * replay caches are reset, which is also why [SignalKey] (no replay
     * cache) is fixed at APP lifetime.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun resetSessionReplayCaches() {
        synchronized(flowsLock) {
            for ((key, flow) in flowsByKey) {
                if (key.lifetime == EventLifetime.SESSION && key.replays) {
                    flow.resetReplayCache()
                }
            }
        }
    }

    /**
     * Cancels every listener and stops the bus for good. Called by the
     * AppComponent's close() as the LAST teardown step, so events that
     * earlier teardown steps trigger can still deliver. A closed
     * manager cannot be restarted; construct a new one.
     */
    fun close() {
        listenerScope.cancel()
        busScope.cancel()
        synchronized(registrationsLock) { registrations.clear() }
    }

    /** Hot stream of [key] payloads; replays the last one if cached. */
    fun eventsOf(key: AnyEventKey): Flow<Any?> =
        flowFor(key).asSharedFlow()

    /**
     * Most recent payload for [key], or null if it never fired, fired
     * with a null payload, or is a non-replaying signal. Use [hasFired]
     * to test occurrence of null-payload replaying keys.
     */
    fun currentValue(key: AnyEventKey): Any? =
        flowFor(key).replayCache.firstOrNull()

    /**
     * Whether [key] fired at least once, regardless of payload. Always
     * false for non-replaying signals, which keep no history.
     */
    fun hasFired(key: AnyEventKey): Boolean =
        flowFor(key).replayCache.isNotEmpty()

    /** The stream for [key], created on first touch. */
    private fun flowFor(key: AnyEventKey): MutableSharedFlow<Any?> =
        synchronized(flowsLock) {
            flowsByKey.getOrPut(key) {
                MutableSharedFlow(
                    replay = if (key.replays) 1 else 0,
                    extraBufferCapacity = if (key.replays) {
                        STATE_BUFFER_CAPACITY
                    } else {
                        SIGNAL_BUFFER_CAPACITY
                    },
                    onBufferOverflow = BufferOverflow.DROP_OLDEST,
                )
            }
        }

    private fun publish(key: AnyEventKey, payload: Any?) {
        if (!isPayloadValid(key, payload)) return
        logManager?.debug(
            "Triggered '${key.eventName}'${describePayload(payload)}",
        )
        // Emit under flowsLock (reentrant with flowFor's own hold) so a
        // trigger racing resetSessionReplayCaches cannot land a
        // pre-reset payload in a just-cleared replay cache. tryEmit
        // never blocks under DROP_OLDEST, so the hold stays trivial.
        synchronized(flowsLock) {
            flowFor(key).tryEmit(payload)
        }
    }

    private fun subscribe(
        key: AnyEventKey,
        owner: Any,
        onEvent: (Any?) -> Unit,
    ) {
        val ownerRef = createWeakOwnerRef(owner)
        val job = listenerScope.launch {
            flowFor(key).collect { payload ->
                if (!isOwnerAlive(ownerRef)) {
                    currentCoroutineContext()[Job]?.cancel()
                    return@collect
                }
                try {
                    onEvent(payload)
                } catch (cancellation: CancellationException) {
                    // Rethrow FIRST: the generic Exception catch below
                    // must never swallow coroutine cancellation.
                    throw cancellation
                } catch (listenerFailure: Exception) {
                    logManager?.error(
                        "Listener for '${key.eventName}' threw; " +
                            "subscription kept alive",
                        throwable = listenerFailure,
                    )
                }
            }
        }
        // The append is synchronous so an unsubscribeOwner issued right
        // after listenTo returns is guaranteed to see it.
        synchronized(registrationsLock) {
            registrations += Registration(ownerRef, job)
        }
        // Eager sweep: every registration schedules a serial busScope
        // pass that cancels collectors whose owners died, bounding
        // leaks by registration traffic instead of event traffic
        // without taxing the subscribing caller.
        busScope.launch { sweepDeadRegistrations() }
    }

    /** Drops registrations whose owner died or whose job finished. */
    private fun sweepDeadRegistrations() {
        val sweptJobs = mutableListOf<Job>()
        synchronized(registrationsLock) {
            registrations.removeAll { registration ->
                val isStale = !isOwnerAlive(registration.ownerRef) ||
                    registration.job.isCompleted
                if (isStale) sweptJobs += registration.job
                isStale
            }
        }
        // Cancel outside the lock; Job.cancel is thread-safe.
        sweptJobs.forEach { staleJob -> staleJob.cancel() }
    }

    private fun isPayloadValid(key: AnyEventKey, payload: Any?): Boolean {
        if (payload == null) return true
        val expectedType = key.payloadType
        if (expectedType == null) {
            logManager?.error(
                "Rejected '${key.eventName}': signal events carry no " +
                    "payload, got ${payload::class.simpleName}",
            )
            return false
        }
        if (!expectedType.isInstance(payload)) {
            logManager?.error(
                "Rejected '${key.eventName}': expected " +
                    "${expectedType.simpleName}, " +
                    "got ${payload::class.simpleName}",
            )
            return false
        }
        return true
    }

    private fun describePayload(payload: Any?): String = when (payload) {
        null -> ""
        is Boolean, is Int, is Long, is Double ->
            " (payload: $payload)"
        // Strings can carry user content and the trace lands in the
        // exportable log file; describe the shape, never the value.
        is String -> " (payload: String, ${payload.length} chars)"
        else -> " (payload: ${payload::class.simpleName})"
    }

    private companion object {
        const val STATE_BUFFER_CAPACITY = 16
        const val SIGNAL_BUFFER_CAPACITY = 64
    }
}

/** Typed event stream; payloads that are not [PayloadType] are dropped. */
inline fun <reified PayloadType : Any> EventManager.typedEventsOf(
    key: AnyEventKey,
): Flow<PayloadType> =
    eventsOf(key).mapNotNull { payload -> payload as? PayloadType }

/**
 * Weak reference to a listener's owner, so subscriptions die with their
 * owner. The handle is opaque; only these three functions touch it.
 */
internal fun createWeakOwnerRef(owner: Any): Any =
    WeakReference(owner)

/** Whether the owner behind [ownerRef] is still alive. */
internal fun isOwnerAlive(ownerRef: Any): Boolean =
    (ownerRef as WeakReference<*>).get() != null

/** The owner behind [ownerRef], or null if it was collected. */
internal fun ownerReferent(ownerRef: Any): Any? =
    (ownerRef as WeakReference<*>).get()
