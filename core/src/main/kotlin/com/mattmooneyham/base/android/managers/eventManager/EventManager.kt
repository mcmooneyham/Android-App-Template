package com.mattmooneyham.base.android.managers.eventManager

import com.mattmooneyham.base.android.managers.logManager.LogManager
import java.lang.ref.WeakReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
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
import kotlin.reflect.KClass

/**
 * Central event hub:
 *
 * - One-shot pub/sub keyed by [StateKey] (typed payload, replayed) or
 *   [SignalKey] (payloadless, one-shot).
 * - For state keys, the most recent payload is cached and replayed to
 *   new listeners; a listener only receives a replay if the event has
 *   been triggered at least once. Signal keys never replay.
 * - UNCHANGED STATE IS NOT RE-DELIVERED: triggering a state key with
 *   a payload equal to the cached value is suppressed (no delivery,
 *   no breadcrumb; a debug trace only). Subscribers already hold the
 *   current value, so a duplicate carries no information. Signals
 *   always deliver: firing twice means two facts.
 * - The manager OWNS every event stream ([flowsByKey]); keys are pure
 *   identifiers. Rebuilding the manager therefore drops all cached
 *   state, and [resetSessionReplayCaches] clears the replay caches of
 *   session-lifetime keys in place (logout / account switch).
 * - OWNER LIFECYCLE: the PRIMARY contract is deterministic teardown
 *   through [unsubscribeOwner] (ViewModels in onCleared, sessions at
 *   session end, tests); managers simply rely on the bus dying with
 *   the component. As a SAFETY NET, callbacks run WITH THE OWNER AS
 *   RECEIVER while the bus holds the owner weakly and resolves it per
 *   delivery: a callback that reaches the owner only through the
 *   receiver (the idiomatic shape; see JokeManager) is removed
 *   automatically once the owner is collected. A callback that
 *   CAPTURES the owner, or anything strongly holding it, pins the
 *   owner until [unsubscribeOwner]. Dead registrations are swept
 *   eagerly on every new registration and pruned on delivery.
 * - All listener callbacks are dispatched on the main queue. A listener
 *   that throws is logged loudly and KEPT ALIVE; one bad payload must
 *   not silently kill a screen's subscription.
 *
 * Ordering contract (the six rules every consumer may rely on):
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
 *  6. A signal subscription is LIVE before [listenTo] returns: a
 *     signal fired on the caller's next line is delivered. State
 *     subscriptions may attach asynchronously; replay makes that
 *     harmless.
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
     * Subscribes [owner] with a typed callback that runs WITH THE
     * OWNER AS RECEIVER: the bus holds the owner weakly and resolves
     * it per delivery, so a callback that reaches the owner only
     * through the receiver is removed automatically once the owner is
     * collected. A callback that CAPTURES the owner (or anything that
     * strongly holds it) pins the owner until [unsubscribeOwner];
     * deterministic teardown therefore always goes through
     * [unsubscribeOwner], the primary contract, with auto-removal as
     * the safety net.
     *
     * Lambda literals, UNBOUND member references (`Screen::handle`),
     * and two-parameter function references all adapt to
     * `OwnerType.(PayloadType) -> Unit` and capture no owner, so they
     * are safe. Bound references (`screen::handle`) and stored
     * `(PayloadType) -> Unit` values do not adapt to the receiver
     * type and are rejected at compile time; the pinning hazard is a
     * LAMBDA whose body captures the owner (or anything strongly
     * holding it), which stays reachable until [unsubscribeOwner].
     * When [owner] is not `this`, members of the enclosing class need
     * an explicit `this@Outer`, since the owner receiver shadows it.
     *
     * If a replaying key was previously triggered, the cached value
     * is replayed immediately. Callbacks run on the main queue and
     * survive their own exceptions.
     */
    fun <OwnerType : Any, PayloadType : Any> listenTo(
        key: StateKey<PayloadType>,
        owner: OwnerType,
        onEvent: OwnerType.(PayloadType) -> Unit,
    ) {
        subscribe(key, owner) { liveOwner, payload ->
            // Safe: publish() only emits validated payloads, and
            // subscribe() only resolves owners it registered.
            @Suppress("UNCHECKED_CAST")
            (liveOwner as OwnerType).onEvent(payload as PayloadType)
        }
    }

    /** Subscribes to a payloadless signal key; the callback runs with
     * the weakly held [owner] as receiver (see the typed overload). */
    fun <OwnerType : Any> listenTo(
        key: SignalKey,
        owner: OwnerType,
        onSignal: OwnerType.() -> Unit,
    ) {
        subscribe(key, owner) { liveOwner, _ ->
            @Suppress("UNCHECKED_CAST")
            (liveOwner as OwnerType).onSignal()
        }
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

    /**
     * Typed stream of [key] payloads. A non-null payload that is not
     * a [payloadType] is dropped AND logged at error level, matching
     * the publish-side backstop, so a key/type mismatch at a collect
     * site is never silent. Null payloads (signal deliveries) are
     * dropped silently: they carry no type to mismatch.
     */
    fun <PayloadType : Any> typedEvents(
        key: AnyEventKey,
        payloadType: KClass<PayloadType>,
    ): Flow<PayloadType> =
        eventsOf(key).mapNotNull { payload ->
            when {
                payload == null -> null
                payloadType.isInstance(payload) -> {
                    @Suppress("UNCHECKED_CAST")
                    payload as PayloadType
                }
                else -> {
                    logManager?.error(
                        "Dropped '${key.eventName}' at collect site: " +
                            "expected ${payloadType.simpleName}, got " +
                            "${payload::class.simpleName}",
                    )
                    null
                }
            }
        }

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
        // The duplicate check and the emit share ONE lock hold
        // (reentrant with flowFor's own) so a trigger racing
        // resetSessionReplayCaches cannot land a pre-reset payload in
        // a just-cleared replay cache. tryEmit never blocks under
        // DROP_OLDEST, so the hold stays trivial.
        val suppressedDuplicate = synchronized(flowsLock) {
            val flow = flowFor(key)
            // EARLY ABORT for unchanged state: a state payload equal
            // to the cached replay value carries no information (late
            // subscribers receive the current value on subscription),
            // so it is not delivered. Meaningful because payloads are
            // immutable data classes by doctrine. Signals NEVER
            // dedupe: firing twice means two facts. A session reset
            // clears the cache, so post-reset re-publication of the
            // same value still delivers.
            if (key.replays &&
                flow.replayCache.firstOrNull() == payload
            ) {
                true
            } else {
                flow.tryEmit(payload)
                false
            }
        }
        if (suppressedDuplicate) {
            // Debug-only trace (not a breadcrumb): suppression is a
            // non-event, but "why didn't my listener fire" deserves
            // an answer in debug builds.
            logManager?.debug(
                "Suppressed duplicate '${key.eventName}'" +
                    describePayload(payload),
            )
            return
        }
        // Breadcrumb, not debug: the trace always reaches the crash
        // report's bounded ring, so production post-mortems see the
        // recent bus history even though release builds keep DEBUG
        // lines out of the log file. describePayload never prints
        // string contents, so nothing sensitive rides along. (Runs
        // after the emit decision so suppressed duplicates never
        // pollute the crash ring; still synchronous within trigger.)
        logManager?.breadcrumb(
            "Triggered '${key.eventName}'${describePayload(payload)}",
        )
    }

    private fun subscribe(
        key: AnyEventKey,
        owner: Any,
        onEvent: (Any, Any?) -> Unit,
    ) {
        val ownerRef = createWeakOwnerRef(owner)
        // Signals have no replay to rescue a collector that attaches
        // late, so their collector starts UNDISPATCHED: the subscriber
        // slot is allocated synchronously, before listenTo returns,
        // and a signal fired on the caller's very next line is
        // delivered instead of silently lost (collect registers the
        // slot and then suspends; every delivery still resumes on
        // Main, so rule 3 holds). Replaying keys keep the default
        // start: replay makes a late attach harmless, and an
        // undispatched start would hand the cached value to the
        // callback synchronously on the subscribing thread.
        val collectorStart =
            if (key.replays) CoroutineStart.DEFAULT
            else CoroutineStart.UNDISPATCHED
        val job = listenerScope.launch(start = collectorStart) {
            flowFor(key).collect { payload ->
                // Resolve per delivery: a strong owner reference
                // exists only while the callback runs, which is what
                // keeps receiver-style callbacks from pinning their
                // owner between deliveries.
                val liveOwner = ownerReferent(ownerRef)
                if (liveOwner == null) {
                    currentCoroutineContext()[Job]?.cancel()
                    return@collect
                }
                try {
                    onEvent(liveOwner, payload)
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
        // Strings can carry user content, and the trace always
        // reaches the crash-report breadcrumb ring (plus the
        // exportable log file in DEBUG-level builds); describe the
        // shape, never the value.
        is String -> " (payload: String, ${payload.length} chars)"
        else -> " (payload: ${payload::class.simpleName})"
    }

    private companion object {
        const val STATE_BUFFER_CAPACITY = 16
        const val SIGNAL_BUFFER_CAPACITY = 64
    }
}

/** Typed event stream; non-null payloads that are not [PayloadType]
 * are dropped AND logged at error level (see
 * [EventManager.typedEvents]); null signal payloads drop silently. */
inline fun <reified PayloadType : Any> EventManager.typedEventsOf(
    key: AnyEventKey,
): Flow<PayloadType> =
    typedEvents(key, PayloadType::class)

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
