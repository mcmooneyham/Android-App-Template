package com.mattmooneyham.base.android.managers

import com.mattmooneyham.base.android.managers.eventManager.EventLifetime
import com.mattmooneyham.base.android.managers.eventManager.EventManager
import com.mattmooneyham.base.android.managers.eventManager.SignalKey
import com.mattmooneyham.base.android.managers.eventManager.StateKey
import com.mattmooneyham.base.android.testkit.awaitTrue
import java.lang.ref.Reference
import java.lang.ref.WeakReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

// Contract-test keys, declared exactly like production keys: a
// session-lifetime state (the default), an APP-lifetime state, and a
// signal. Keys are pure identifiers, so sharing the objects across
// tests is safe: each test's EventManager owns its own streams.
private object TestGreetingChanged : StateKey<String>(
    eventName = "test.GreetingChanged",
    payloadType = String::class,
)

private object TestCounterChanged : StateKey<Int>(
    eventName = "test.CounterChanged",
    payloadType = Int::class,
    lifetime = EventLifetime.APP,
)

private object TestPingFired : SignalKey(eventName = "test.PingFired")

/**
 * Contract tests for the event bus itself, on a bare [EventManager]
 * (no LogManager attached; tracing is optional by design). Main is an
 * unconfined test dispatcher, so listener delivery happens
 * synchronously within each trigger call and the assertions below can
 * be direct.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EventManagerContractSpec {

    private lateinit var eventManager: EventManager

    @Before
    fun setUp() {
        // Before construction: the manager captures Main when built.
        Dispatchers.setMain(UnconfinedTestDispatcher())
        eventManager = EventManager()
    }

    @After
    fun tearDown() {
        eventManager.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `a payload of the wrong runtime type is rejected, not delivered`() {
        val receivedPayloads = mutableListOf<String>()
        val listenerOwner = Any()
        eventManager.listenTo(TestGreetingChanged, listenerOwner) {
            receivedPayloads += it
        }

        // Defeat compile-time typing to exercise the runtime backstop
        // that guards type-erased call paths.
        @Suppress("UNCHECKED_CAST")
        val erasedKey = TestGreetingChanged as StateKey<Any>
        eventManager.trigger(erasedKey, 42)

        assertTrue(receivedPayloads.isEmpty())
        assertFalse(eventManager.hasFired(TestGreetingChanged))
        assertNull(eventManager.currentValue(TestGreetingChanged))

        // The stream is unharmed: a valid payload still goes through.
        eventManager.trigger(TestGreetingChanged, "hello")
        assertEquals(listOf("hello"), receivedPayloads)
        Reference.reachabilityFence(listenerOwner)
    }

    @Test
    fun `a state key replays only its latest value to a late subscriber`() {
        eventManager.trigger(TestGreetingChanged, "first")
        eventManager.trigger(TestGreetingChanged, "second")

        val receivedPayloads = mutableListOf<String>()
        val listenerOwner = Any()
        eventManager.listenTo(TestGreetingChanged, listenerOwner) {
            receivedPayloads += it
        }

        assertEquals(listOf("second"), receivedPayloads)
        assertEquals(
            "second",
            eventManager.currentValue(TestGreetingChanged),
        )
        Reference.reachabilityFence(listenerOwner)
    }

    @Test
    fun `a signal key never replays but reaches live listeners`() {
        // Fired before anyone listens: gone for good.
        eventManager.trigger(TestPingFired)

        var signalCount = 0
        val listenerOwner = Any()
        eventManager.listenTo(TestPingFired, listenerOwner) {
            signalCount += 1
        }
        assertEquals(0, signalCount)
        assertFalse(eventManager.hasFired(TestPingFired))

        // Fired while listening: delivered.
        eventManager.trigger(TestPingFired)
        assertEquals(1, signalCount)
        Reference.reachabilityFence(listenerOwner)
    }

    @Test
    fun `session reset clears session caches and keeps app caches`() {
        eventManager.trigger(TestGreetingChanged, "user data")
        eventManager.trigger(TestCounterChanged, 7)

        eventManager.resetSessionReplayCaches()

        // SESSION-lifetime cache is gone, as if never fired.
        assertFalse(eventManager.hasFired(TestGreetingChanged))
        assertNull(eventManager.currentValue(TestGreetingChanged))
        // APP-lifetime cache survives (device facts outlive sessions).
        assertEquals(7, eventManager.currentValue(TestCounterChanged))
    }

    @Test
    fun `session reset leaves live subscriptions attached`() {
        val receivedPayloads = mutableListOf<String>()
        val listenerOwner = Any()
        eventManager.listenTo(TestGreetingChanged, listenerOwner) {
            receivedPayloads += it
        }

        eventManager.resetSessionReplayCaches()
        eventManager.trigger(TestGreetingChanged, "after reset")

        assertEquals(listOf("after reset"), receivedPayloads)
        Reference.reachabilityFence(listenerOwner)
    }

    @Test
    fun `unsubscribeOwner detaches immediately, not on the next sweep`() {
        val receivedPayloads = mutableListOf<String>()
        val listenerOwner = Any()
        eventManager.listenTo(TestGreetingChanged, listenerOwner) {
            receivedPayloads += it
        }
        eventManager.trigger(TestGreetingChanged, "before")
        assertEquals(listOf("before"), receivedPayloads)

        eventManager.unsubscribeOwner(listenerOwner)

        // Bookkeeping is gone synchronously, and nothing is delivered
        // to the detached listener, even though its owner is alive.
        assertEquals(0, registrationCount())
        eventManager.trigger(TestGreetingChanged, "after")
        assertEquals(listOf("before"), receivedPayloads)
        Reference.reachabilityFence(listenerOwner)
    }

    @Test
    fun `a dead owner's subscription is swept on a later registration`() {
        val collectedOwnerRef = subscribeWithDisposableOwner()
        assertEquals(1, registrationCount())

        awaitTrue("the disposable owner is garbage collected") {
            System.gc()
            collectedOwnerRef.get() == null
        }

        // A fresh registration schedules the eager sweep on the bus's
        // bookkeeping scope; the dead entry disappears shortly after.
        val keptOwner = Any()
        eventManager.listenTo(TestPingFired, keptOwner) { }
        awaitTrue("the dead registration is swept") {
            registrationCount() == 1
        }
        Reference.reachabilityFence(keptOwner)
    }

    @Test
    fun `a receiver-style listener does not pin its owner`() {
        val ownerRef = subscribeWithReceiverOnlyOwner()

        // The callback reaches the owner only through the receiver,
        // so nothing strong survives in the closure and the safety
        // net actually fires.
        awaitTrue("the receiver-style owner is garbage collected") {
            System.gc()
            ownerRef.get() == null
        }
    }

    @Test
    fun `a capturing listener pins its owner until unsubscribed`() {
        val receivedPayloads = mutableListOf<String>()
        val ownerRef = subscribeWithCapturedOwner(receivedPayloads)

        // Honest documentation of the limit: a callback that CAPTURES
        // its owner keeps it strongly reachable, so auto-removal never
        // fires and deterministic teardown is required.
        repeat(3) { System.gc() }
        assertNotNull(ownerRef.get())

        eventManager.unsubscribeOwner(ownerRef.get()!!)
        awaitTrue("unsubscribeOwner releases the captured owner") {
            System.gc()
            ownerRef.get() == null
        }
    }

    /**
     * Registers a listener whose owner is unreachable once this method
     * returns; only a WeakReference escapes, so the JVM may collect
     * the owner. A separate method keeps no stale local alive in the
     * test frame.
     */
    private fun subscribeWithDisposableOwner(): WeakReference<Any> {
        val disposableOwner = Any()
        eventManager.listenTo(TestGreetingChanged, disposableOwner) { }
        return WeakReference(disposableOwner)
    }

    private class ReceiverOnlyOwner {
        var deliveryCount = 0
    }

    /** The idiomatic shape: the callback touches the owner ONLY via
     * the receiver, so the closure holds nothing strong. */
    private fun subscribeWithReceiverOnlyOwner():
        WeakReference<ReceiverOnlyOwner> {
        val receiverOwner = ReceiverOnlyOwner()
        eventManager.listenTo(TestGreetingChanged, receiverOwner) { _ ->
            deliveryCount += 1
        }
        return WeakReference(receiverOwner)
    }

    /** The hazardous shape: the closure names `capturedOwner`, which
     * pins it for the collector's lifetime. */
    private fun subscribeWithCapturedOwner(
        receivedPayloads: MutableList<String>,
    ): WeakReference<Any> {
        val capturedOwner = Any()
        eventManager.listenTo(TestGreetingChanged, capturedOwner) { greeting ->
            receivedPayloads += "$greeting seen by $capturedOwner"
        }
        return WeakReference(capturedOwner)
    }

    /** Racy size read; only ever used inside polling assertions. */
    private fun registrationCount(): Int = eventManager.registrations.size
}
