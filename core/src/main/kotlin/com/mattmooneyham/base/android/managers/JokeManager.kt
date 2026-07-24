package com.mattmooneyham.base.android.managers

import com.mattmooneyham.base.android.api.ApiClient
import com.mattmooneyham.base.android.api.FetchFailure
import com.mattmooneyham.base.android.api.JokeDto
import com.mattmooneyham.base.android.api.toFetchFailure
import com.mattmooneyham.base.android.managers.connectivityManager.NetworkConnectivityChanged
import com.mattmooneyham.base.android.managers.eventManager.EventManager
import com.mattmooneyham.base.android.managers.eventManager.StateKey
import com.mattmooneyham.base.android.managers.featureFlagManager.BooleanFlag
import com.mattmooneyham.base.android.managers.featureFlagManager.FeatureFlagManager
import com.mattmooneyham.base.android.managers.logManager.LogManager
import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

// State: the joke feature's whole story in one payload (see JokeState).
object JokeStateChanged : StateKey<JokeState>(
    eventName = "joke.StateChanged",
    payloadType = JokeState::class,
)

// Flag: gates the reconnect auto-refresh choreography below. Declared
// beside the consuming feature like an event key, and listed in
// AppFlags.all (the registry guard test enforces that). OFF by
// default: enable it remotely via a provider or locally from the
// Settings > Debug > Feature flags sheet.
object JokeAutoRetryOnReconnectFlag : BooleanFlag(
    flagKey = "joke.autoRetryOnReconnect",
    default = false,
    description = "Auto-refresh a failed joke when connectivity returns",
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
 * listens. It loads the first joke eagerly in [start] (never in init:
 * the init budget keeps network IO out of construction), so the UI
 * never has to ask for data, only for refreshes.
 *
 * Publishes a single event, [JokeStateChanged], whose [JokeState]
 * payload carries the whole story: REFRESHING while a fetch is in
 * flight, then SUCCESS with the joke or FAILED with a detail message
 * (both keeping the last good joke available).
 *
 * CHOREOGRAPHY EXEMPLAR: this manager also demonstrates the canonical
 * cross-manager pattern by listening to [NetworkConnectivityChanged].
 * When [JokeAutoRetryOnReconnectFlag] is enabled (off by default), the
 * last fetch FAILED, and connectivity transitions from false to true,
 * it auto-refreshes exactly once per failure. The pattern:
 * subscribe in init with the manager ITSELF as owner (the subscription
 * then lives exactly as long as the manager), and hop the reaction
 * onto the manager's own confinement before touching mutable state,
 * because listener callbacks are delivered on Main. Managers stay
 * peers: each reacts to the other's published events, and neither
 * holds a reference to the other.
 */
class JokeManager(
    httpClient: HttpClient,
    private val logManager: LogManager,
    private val eventManager: EventManager,
    private val featureFlagManager: FeatureFlagManager,
    // Test seam only: production wiring always takes the default, so
    // the endpoint stays this manager's own business. Direct manager
    // tests may point it at a scripted or local URL.
    apiBaseUrl: String = JOKE_API_BASE_URL,
) : ConfinedManager(
    managerName = "JokeManager",
    failureLogManager = logManager,
) {

    // The manager owns its API client: which endpoint to talk to is
    // this feature's business, not the composition root's. Only the
    // HttpClient (engine, pools, JSON setup, the test seam) is shared
    // and component-owned. Managers with their own endpoints each
    // build their own ApiClient exactly like this; clients are cheap
    // wrappers, so one per service is the intended shape.
    private val apiClient = ApiClient(
        httpClient = httpClient,
        baseUrl = apiBaseUrl,
    )

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
        // Construction subscribes but performs no IO; the first fetch
        // happens in start(), per the init budget (ConfinedManager).
        // Canonical cross-manager subscription (see the class KDoc):
        // owner = this ties the subscription to the manager's lifetime;
        // the callback arrives on Main, so the reaction hops onto the
        // manager's own confinement before touching state. The
        // load-bearing detail: inside the lambda, managerScope and
        // reactToConnectivity resolve against the RECEIVER (the weakly
        // held owner), not a captured this, so the callback itself
        // never pins the manager; a strong reference exists only while
        // a delivery runs.
        eventManager.listenTo(
            NetworkConnectivityChanged,
            owner = this,
        ) { isConnected ->
            managerScope.launch { reactToConnectivity(isConnected) }
        }
    }

    /** Eager first load; the UI still only ever asks for refreshes. */
    override fun start() {
        refreshJoke()
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
        // The flag is read at DECISION time (never cached at
        // construction), so provider updates and debug overrides
        // apply to the very next transition.
        if (cameBackOnline && shouldRetryOnReconnect &&
            featureFlagManager.isEnabled(JokeAutoRetryOnReconnectFlag)
        ) {
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

    companion object {
        // The Official Joke API: the template's keyless demo endpoint.
        // A feature manager declares its own endpoint beside itself,
        // like its event keys; endpoints multiply by adding managers
        // with their own ApiClients, never by widening a global URL.
        const val JOKE_API_BASE_URL =
            "https://official-joke-api.appspot.com/"
    }

}
