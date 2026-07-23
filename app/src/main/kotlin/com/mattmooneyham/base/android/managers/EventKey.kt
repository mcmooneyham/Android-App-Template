package com.mattmooneyham.base.android.managers

import kotlin.reflect.KClass

/**
 * How long a key's cached replay value is allowed to live.
 *
 * - [APP]: the cached value describes the device or process (e.g.
 *   connectivity) and survives a session reset.
 * - [SESSION]: the cached value belongs to the signed-in user or the
 *   current app session; [EventManager.resetSessionReplayCaches] clears
 *   it on logout or account switch. This is the safe default.
 */
enum class EventLifetime { APP, SESSION }

/**
 * Type-erased base of every event key, used by the untyped APIs
 * ([EventManager.currentValue], [EventManager.eventsOf]). Code should
 * use the typed [EventKey] subclass everywhere.
 *
 * A key is a PURE IDENTIFIER: name, payload type, replay behavior, and
 * lifetime. The event stream itself lives inside [EventManager], keyed
 * by this object, so rebuilding the manager (tests, account switch)
 * drops every cached value instead of leaking state through
 * process-global key objects.
 */
abstract class AnyEventKey(
    val eventName: String,
    val payloadType: KClass<*>?,
    val replays: Boolean,
    val lifetime: EventLifetime = EventLifetime.SESSION,
)

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
 *
 * [lifetime] defaults to [EventLifetime.SESSION]: the cached replay
 * value is cleared by [EventManager.resetSessionReplayCaches]. Keys
 * describing device or process facts (e.g. connectivity) opt into
 * [EventLifetime.APP] so their cache survives a session reset.
 */
abstract class EventKey<PayloadType : Any>(
    eventName: String,
    payloadType: KClass<PayloadType>,
    replays: Boolean = true,
    lifetime: EventLifetime = EventLifetime.SESSION,
) : AnyEventKey(eventName, payloadType, replays, lifetime)

/**
 * A payloadless one-shot signal (never replayed to late subscribers):
 *
 * ```
 * object LogsCleared : SignalKey("Logs Cleared")
 * eventManager.trigger(LogsCleared)
 * eventManager.listenTo(LogsCleared, owner) { ... }
 * ```
 *
 * Fixed at [EventLifetime.APP]: a session reset only clears replay
 * caches, and signals keep no replay cache, so the tag is moot; APP
 * states that explicitly.
 */
abstract class SignalKey(
    eventName: String,
) : AnyEventKey(
    eventName,
    payloadType = null,
    replays = false,
    lifetime = EventLifetime.APP,
)
