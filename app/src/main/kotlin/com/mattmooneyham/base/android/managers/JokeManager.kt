package com.mattmooneyham.base.android.managers

import com.mattmooneyham.base.android.api.ApiClient
import com.mattmooneyham.base.android.api.FetchFailure
import com.mattmooneyham.base.android.api.JokeDto
import com.mattmooneyham.base.android.api.toFetchFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

// State: the joke feature's whole story in one payload (see JokeState).
object JokeStateChanged : StateKey<JokeState>(
    eventName = "joke.StateChanged",
    payloadType = JokeState::class,
)

/** Phase of the joke fetch lifecycle carried by [JokeState]. */
enum class JokeStatus {
    REFRESHING,
    SUCCESS,
    FAILED,
}

/**
 * Payload of [JokeStateChanged]: the fetch status plus the most
 * recently fetched joke, which is retained through refreshes and
 * failures so listeners can keep showing it. On FAILED, [failure]
 * carries the typed reason so views can choose copy by kind.
 */
data class JokeState(
    val status: JokeStatus,
    val joke: JokeDto? = null,
    val failure: FetchFailure? = null,
)

/**
 * Owns the demo joke feature, following the app's event-driven manager
 * convention: the manager fetches and publishes; everything else just
 * listens. It loads the first joke eagerly at initialization, so the UI
 * never has to ask for data, only for refreshes.
 *
 * Publishes a single event, [JokeStateChanged], whose [JokeState]
 * payload carries the whole story: REFRESHING while a fetch is in
 * flight, then SUCCESS with the joke or FAILED with a detail message
 * (both keeping the last good joke available).
 *
 * CHOREOGRAPHY EXEMPLAR: this manager also demonstrates the canonical
 * cross-manager pattern by listening to [NetworkConnectivityChanged].
 * When the last fetch FAILED and connectivity transitions from false
 * to true, it auto-refreshes exactly once per failure. The pattern:
 * subscribe in init with the manager ITSELF as owner (the subscription
 * then lives exactly as long as the manager), and hop the reaction
 * onto the manager's own confinement before touching mutable state,
 * because listener callbacks are delivered on Main. Managers stay
 * peers: each reacts to the other's published events, and neither
 * holds a reference to the other.
 */
class JokeManager(
    private val apiClient: ApiClient,
    private val logManager: LogManager,
    private val eventManager: EventManager,
) : ConfinedManager(
    managerName = "JokeManager",
    failureLogManager = logManager,
) {

    // Both fields are confined to the manager's serial dispatcher (see
    // ConfinedManager): refreshJoke is callable from any thread, so ALL
    // mutable-state access happens inside managerScope, making the
    // check-then-set guard atomic between coroutines.
    private var isFetchInFlight = false
    private var latestJoke: JokeDto? = null

    // Choreography state (confined like the fields above): the retry
    // ticket is armed by a FAILED fetch and consumed by the first
    // false-to-true connectivity transition, so each failure earns at
    // most one automatic refresh. The last observed connectivity
    // starts null so the replayed subscription seed sets a baseline
    // without ever counting as a transition.
    private var shouldRetryOnReconnect = false
    private var lastObservedConnectivity: Boolean? = null

    init {
        refreshJoke()
        // Canonical cross-manager subscription (see the class KDoc):
        // owner = this ties the subscription to the manager's lifetime;
        // the callback arrives on Main, so the reaction hops onto the
        // manager's own confinement before touching state.
        eventManager.listenTo(
            NetworkConnectivityChanged,
            owner = this,
        ) { isConnected ->
            managerScope.launch { reactToConnectivity(isConnected) }
        }
    }

    /**
     * Runs on the manager's confinement. Refreshes once when
     * connectivity comes back after a failed fetch; every other
     * combination only updates the baseline.
     */
    private fun reactToConnectivity(isConnected: Boolean) {
        val cameBackOnline =
            lastObservedConnectivity == false && isConnected
        lastObservedConnectivity = isConnected
        if (cameBackOnline && shouldRetryOnReconnect) {
            logManager.info(
                "Connectivity restored; retrying failed joke fetch",
            )
            // refreshJoke consumes the ticket; if a fetch is already in
            // flight, its own terminal state decides whether to re-arm.
            refreshJoke()
        }
    }

    /**
     * Fetches a fresh joke, publishing the lifecycle as
     * [JokeStateChanged]. Fire-and-forget and non-suspending from ANY
     * thread; ignored while a fetch is already in flight.
     */
    fun refreshJoke() {
        managerScope.launch {
            if (isFetchInFlight) return@launch
            isFetchInFlight = true
            // Any accepted refresh supersedes a pending reconnect
            // retry; a new failure below re-arms it.
            shouldRetryOnReconnect = false
            publishState(JokeState(status = JokeStatus.REFRESHING))
            val terminalState = try {
                logManager.debug("Fetching a random joke")
                val joke = apiClient.get<JokeDto>("random_joke")
                logManager.info("Fetched joke #${joke.id} (${joke.type})")
                latestJoke = joke
                JokeState(status = JokeStatus.SUCCESS)
            } catch (cancellation: CancellationException) {
                isFetchInFlight = false
                throw cancellation
            } catch (exception: Exception) {
                val failure = exception.toFetchFailure()
                logManager.warn(
                    "Joke fetch failed [${failure.kind}]: ${failure.detail}",
                )
                JokeState(status = JokeStatus.FAILED, failure = failure)
            }
            // A failure arms the reconnect retry (see class KDoc);
            // written before the terminal publish like the flag below.
            if (terminalState.status == JokeStatus.FAILED) {
                shouldRetryOnReconnect = true
            }
            // The flag clears BEFORE the terminal publish: the moment a
            // listener hears SUCCESS or FAILED, a new refresh must be
            // accepted (publishing first would open a window where an
            // immediate reaction to the terminal state is silently
            // dropped: caught as a race by CI).
            isFetchInFlight = false
            publishState(terminalState)
        }
    }

    /** Publishes [state] with the retained [latestJoke] attached. */
    private fun publishState(state: JokeState) {
        eventManager.trigger(JokeStateChanged, state.copy(joke = latestJoke))
    }

}
