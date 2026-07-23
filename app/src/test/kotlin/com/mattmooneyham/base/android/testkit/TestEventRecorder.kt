package com.mattmooneyham.base.android.testkit

import com.mattmooneyham.base.android.managers.AnyEventKey
import com.mattmooneyham.base.android.managers.EventManager
import com.mattmooneyham.base.android.managers.SignalKey
import com.mattmooneyham.base.android.managers.StateKey
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Event assertion kit: subscribes to declared keys through the REAL
 * EventManager API (the recorder itself is the weak owner, kept alive
 * by the test) and turns deliveries into suspending assertions.
 *
 * Every recorded delivery is queued per key for [expectEvent] /
 * [expectState] and appended to one ordered log for [assertOrder].
 * Timeouts are real time (the wait hops onto Dispatchers.Default), so
 * the kit works the same under runBlocking and virtual-time runners.
 */
class TestEventRecorder(private val eventManager: EventManager) {

    /** One delivery: which key fired and what it carried. */
    data class RecordedEvent(val key: AnyEventKey, val payload: Any)

    /** Marker payload recorded for payloadless signal deliveries. */
    object SignalFired

    private val recordedEvents =
        Collections.synchronizedList(mutableListOf<RecordedEvent>())
    private val pendingByKey =
        ConcurrentHashMap<AnyEventKey, Channel<Any>>()

    /** Starts recording [key]; replayed values are recorded too. */
    fun <PayloadType : Any> record(
        key: StateKey<PayloadType>,
    ): TestEventRecorder {
        eventManager.listenTo(key, owner = this) { payload ->
            deposit(key, payload)
        }
        return this
    }

    /** Starts recording a signal key (deposited as [SignalFired]). */
    fun record(key: SignalKey): TestEventRecorder {
        eventManager.listenTo(key, owner = this) {
            deposit(key, SignalFired)
        }
        return this
    }

    /**
     * Suspends until the next unconsumed delivery of [key] and returns
     * its payload; fails the test after [timeout].
     */
    suspend fun expectEvent(
        key: AnyEventKey,
        timeout: Duration = DEFAULT_TIMEOUT,
    ): Any = withContext(Dispatchers.Default) {
        withTimeoutOrNull(timeout) { pendingQueueFor(key).receive() }
    } ?: throw AssertionError(
        "Expected '${key.eventName}' within $timeout but nothing " +
            "arrived; recorded so far: ${describeRecordedEvents()}",
    )

    /** Typed [expectEvent] for state keys. */
    suspend fun <PayloadType : Any> expectState(
        key: StateKey<PayloadType>,
        timeout: Duration = DEFAULT_TIMEOUT,
    ): PayloadType {
        @Suppress("UNCHECKED_CAST")
        return expectEvent(key, timeout) as PayloadType
    }

    /**
     * Consumes deliveries of [key] until one satisfies [predicate] and
     * returns it; fails after [timeout]. Useful for draining an
     * unknown-length prefix (e.g. "wait until status == SUCCESS").
     */
    suspend fun <PayloadType : Any> expectStateMatching(
        key: StateKey<PayloadType>,
        timeout: Duration = DEFAULT_TIMEOUT,
        predicate: (PayloadType) -> Boolean,
    ): PayloadType {
        val deadlineMillis =
            System.currentTimeMillis() + timeout.inWholeMilliseconds
        while (true) {
            val remaining =
                (deadlineMillis - System.currentTimeMillis())
                    .milliseconds
            if (remaining.isNegative() || remaining == Duration.ZERO) {
                throw AssertionError(
                    "No '${key.eventName}' delivery matched within " +
                        "$timeout; recorded so far: " +
                        describeRecordedEvents(),
                )
            }
            val candidate = expectState(key, remaining)
            if (predicate(candidate)) return candidate
        }
    }

    /**
     * Asserts that no unconsumed delivery of [key] exists after
     * letting the pipeline settle for a real-time [grace] period.
     * Grace-based by necessity: absence has no completion signal.
     */
    fun assertNoEvent(
        key: AnyEventKey,
        grace: Duration = DEFAULT_GRACE,
    ) {
        Thread.sleep(grace.inWholeMilliseconds)
        val unexpected = pendingByKey[key]?.tryReceive()?.getOrNull()
        if (unexpected != null) {
            throw AssertionError(
                "Expected no '${key.eventName}' delivery, but got " +
                    "payload: $unexpected",
            )
        }
    }

    /**
     * Asserts the recorded log contains [expectedKeys] as a
     * subsequence: each key fired, in this relative order, with any
     * other deliveries allowed in between.
     */
    fun assertOrder(vararg expectedKeys: AnyEventKey) {
        val recordedKeys = synchronized(recordedEvents) {
            recordedEvents.map { recorded -> recorded.key }
        }
        var searchFrom = 0
        for (expectedKey in expectedKeys) {
            val foundAt = recordedKeys
                .drop(searchFrom)
                .indexOfFirst { recordedKey ->
                    recordedKey === expectedKey
                }
            if (foundAt < 0) {
                throw AssertionError(
                    "Expected order ${describeKeys(expectedKeys)} " +
                        "but '${expectedKey.eventName}' was missing " +
                        "after position $searchFrom; recorded: " +
                        describeRecordedEvents(),
                )
            }
            searchFrom += foundAt + 1
        }
    }

    /** All payloads recorded for [key] so far, in delivery order. */
    fun recordedPayloadsOf(key: AnyEventKey): List<Any> =
        synchronized(recordedEvents) {
            recordedEvents
                .filter { recorded -> recorded.key === key }
                .map { recorded -> recorded.payload }
        }

    private fun deposit(key: AnyEventKey, payload: Any) {
        recordedEvents.add(RecordedEvent(key, payload))
        pendingQueueFor(key).trySend(payload)
    }

    private fun pendingQueueFor(key: AnyEventKey): Channel<Any> =
        pendingByKey.getOrPut(key) { Channel(Channel.UNLIMITED) }

    private fun describeRecordedEvents(): String =
        synchronized(recordedEvents) {
            recordedEvents.map { recorded ->
                "${recorded.key.eventName}=${recorded.payload}"
            }
        }.toString()

    private fun describeKeys(keys: Array<out AnyEventKey>): String =
        keys.map { key -> key.eventName }.toString()

    private companion object {
        val DEFAULT_TIMEOUT = 5.seconds
        val DEFAULT_GRACE = 300.milliseconds
    }
}
