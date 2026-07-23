package com.mattmooneyham.base.android.managers

import kotlin.reflect.KClass
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * Type-erased base of every event key, used by the untyped APIs
 * ([EventManager.currentValue], [EventManager.eventsOf]). Code should
 * use the typed [EventKey] subclass everywhere.
 */
abstract class AnyEventKey(
    val eventName: String,
    val payloadType: KClass<*>?,
    val replays: Boolean,
) {
    // The key OWNS its stream: Kotlin object initialization is
    // thread-safe and lazy, so each flow is created safely on first
    // touch with zero locking, preserving trigger's synchronous
    // any-thread contract.
    internal val flow = MutableSharedFlow<Any?>(
        replay = if (replays) 1 else 0,
        extraBufferCapacity =
            if (replays) STATE_BUFFER_CAPACITY else SIGNAL_BUFFER_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    private companion object {
        const val STATE_BUFFER_CAPACITY = 16
        const val SIGNAL_BUFFER_CAPACITY = 64
    }
}

/**
 * A single app event, declared as an `object` next to the manager that
 * publishes it. The generic parameter makes the payload part of the
 * key's TYPE, so a mistyped
 * publish or subscribe no longer compiles on the Kotlin side:
 *
 * ```
 * // Beside its manager:
 * object JokeStateChanged : EventKey<JokeState>(
 *     eventName = "Joke State Changed",
 *     payloadType = JokeState::class,
 * )
 *
 * eventManager.trigger(JokeStateChanged, jokeState)   // compiles
 * eventManager.trigger(JokeStateChanged, "oops")      // does NOT compile
 * eventManager.listenTo(JokeStateChanged, owner) { state ->
 *     // state is a JokeState, guaranteed
 * }
 * ```
 *
 * [payloadType] repeats the generic parameter as a runtime [KClass]
 * deliberately, guarding the erased paths (the untyped flow) at trigger
 * time. The constructor is typed `KClass<PayloadType>`, so the pairing
 * itself is compiler-enforced: a key cannot declare a runtime type that
 * contradicts its generic parameter.
 *
 * Delivery semantics per key: state events ([replays] = true, the
 * default) cache the latest payload and replay it to late subscribers;
 * one-shot signals (see [SignalKey]) are seen only by listeners already
 * subscribed when the event fires. State events are latest-wins under
 * bursts; never use events as lossless data queues.
 */
abstract class EventKey<PayloadType : Any>(
    eventName: String,
    payloadType: KClass<PayloadType>,
    replays: Boolean = true,
) : AnyEventKey(eventName, payloadType, replays)

/**
 * A payloadless one-shot signal (never replayed to late subscribers):
 *
 * ```
 * object LogsCleared : SignalKey("Logs Cleared")
 * eventManager.trigger(LogsCleared)
 * eventManager.listenTo(LogsCleared, owner) { ... }
 * ```
 */
abstract class SignalKey(
    eventName: String,
) : AnyEventKey(eventName, payloadType = null, replays = false)
