package com.mattmooneyham.base.android.managers

import java.lang.ref.WeakReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch

/**
 * Central event hub:
 *
 * - One-shot pub/sub keyed by [EventKey] with a typed payload.
 * - For replaying (state) keys, the most recent payload is cached and
 *   replayed to new listeners; a listener only receives a replay if the
 *   event has been triggered at least once. Signal keys
 *   ([AnyEventKey.replays] = false) never replay.
 * - Listeners are automatically removed when their owner is deallocated
 *   (weak owner tracking). There is DELIBERATELY no removal/unsubscribe
 *   API: the contract is that a subscription lives exactly as long as
 *   its owner, which makes leaks a matter of object lifetime (visible
 *   in a memory profiler) rather than of forgotten remove calls
 *   scattered across screens. Dead
 *   registrations are swept eagerly on every new registration and
 *   pruned on delivery.
 * - All listener callbacks are dispatched on the main queue. A listener
 *   that throws is logged loudly and KEPT ALIVE; one bad payload must
 *   not silently kill a screen's subscription.
 *
 * Publishers and subscribers are compile-time typed via [EventKey]'s
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

    private var logManager: LogManager? = null

    // Every live subscription, so dead owners can be swept eagerly on
    // the next registration instead of waiting for their event to fire.
    // Mutated ONLY inside listenerScope.launch (single-threaded main),
    // making it race-free against any-thread listenTo calls. Internal
    // for the lifecycle tests.
    internal val registrations = mutableListOf<Registration>()

    internal class Registration(val ownerRef: Any, val job: Job)

    /**
     * Hooks up trigger tracing and contract-violation reporting. Called
     * once by BaseSdk.initialize as soon as the LogManager exists (the
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
        key: EventKey<PayloadType>,
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
        key: EventKey<PayloadType>,
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

    /** Hot stream of [key] payloads; replays the last one if cached. */
    fun eventsOf(key: AnyEventKey): Flow<Any?> = key.flow.asSharedFlow()

    /**
     * Most recent payload for [key], or null if it never fired, fired
     * with a null payload, or is a non-replaying signal. Use [hasFired]
     * to test occurrence of null-payload replaying keys.
     */
    fun currentValue(key: AnyEventKey): Any? =
        key.flow.replayCache.firstOrNull()

    /**
     * Whether [key] fired at least once, regardless of payload. Always
     * false for non-replaying signals, which keep no history.
     */
    fun hasFired(key: AnyEventKey): Boolean =
        key.flow.replayCache.isNotEmpty()

    private fun publish(key: AnyEventKey, payload: Any?) {
        if (!isPayloadValid(key, payload)) return
        logManager?.debug(
            "Triggered '${key.eventName}'${describePayload(payload)}",
        )
        key.flow.tryEmit(payload)
    }

    private fun subscribe(
        key: AnyEventKey,
        owner: Any,
        onEvent: (Any?) -> Unit,
    ) {
        val ownerRef = createWeakOwnerRef(owner)
        val job = listenerScope.launch {
            key.flow.collect { payload ->
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
        // Eager sweep: every registration pays a tiny toll that cancels
        // collectors whose owners died, bounding leaks by registration
        // traffic instead of event traffic.
        listenerScope.launch {
            registrations.removeAll { registration ->
                val isStale = !isOwnerAlive(registration.ownerRef) ||
                    registration.job.isCompleted
                if (isStale) registration.job.cancel()
                isStale
            }
            registrations += Registration(ownerRef, job)
        }
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
}

/** Typed event stream; payloads that are not [PayloadType] are dropped. */
inline fun <reified PayloadType : Any> EventManager.typedEventsOf(
    key: AnyEventKey,
): Flow<PayloadType> =
    eventsOf(key).mapNotNull { payload -> payload as? PayloadType }

/**
 * Weak reference to a listener's owner, so subscriptions die with their
 * owner. The handle is opaque; only these two functions touch it.
 */
internal fun createWeakOwnerRef(owner: Any): Any =
    WeakReference(owner)

/** Whether the owner behind [ownerRef] is still alive. */
internal fun isOwnerAlive(ownerRef: Any): Boolean =
    (ownerRef as WeakReference<*>).get() != null
