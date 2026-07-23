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
 * use the typed [StateKey] or [SignalKey] subclass everywhere.
 *
 * A key is a PURE IDENTIFIER: name, payload type, replay behavior, and
 * lifetime. The event stream itself lives inside [EventManager], keyed
 * by this object, so rebuilding the manager (tests, account switch)
 * drops every cached value instead of leaking state through
 * process-global key objects.
 *
 * [eventName] follows the convention "namespace.EventName": a lowercase
 * namespace naming the publishing manager, a dot, then a PascalCase
 * event description (e.g. "joke.StateChanged",
 * "network.ConnectivityChanged"). The namespace keeps trace logs
 * greppable and names collision-free as managers multiply.
 */
abstract class AnyEventKey(
    val eventName: String,
    val payloadType: KClass<*>?,
    val replays: Boolean,
    val lifetime: EventLifetime,
)

/**
 * A replayed state event, declared as an `object` next to the manager
 * that publishes it. The generic parameter makes the payload part of
 * the key's TYPE, so a mistyped publish or subscribe does not compile:
 *
 * ```
 * // Beside its manager:
 * object JokeStateChanged : StateKey<JokeState>(
 *     eventName = "joke.StateChanged",
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
 * Delivery semantics: state keys always replay. The most recent payload
 * is cached and delivered to late subscribers, latest-wins under
 * bursts; never use events as lossless data queues. For one-shot
 * notifications with no cached value, declare a [SignalKey] instead.
 *
 * [lifetime] defaults to [EventLifetime.SESSION]: the cached replay
 * value is cleared by [EventManager.resetSessionReplayCaches]. Keys
 * describing device or process facts (e.g. connectivity) opt into
 * [EventLifetime.APP] so their cache survives a session reset.
 */
abstract class StateKey<PayloadType : Any>(
    eventName: String,
    payloadType: KClass<PayloadType>,
    lifetime: EventLifetime = EventLifetime.SESSION,
) : AnyEventKey(eventName, payloadType, replays = true, lifetime)

/**
 * A payloadless one-shot signal (never replayed to late subscribers):
 *
 * ```
 * object LogsCleared : SignalKey("log.Cleared")
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
