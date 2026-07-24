package com.mattmooneyham.base.android.testkit

import com.mattmooneyham.base.android.api.JokeDto
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.Json

/**
 * Scripted stand-in for the Joke API, served through Ktor's
 * MockEngine (wired in by the app-test TestAppContext harness through
 * AppConfig.httpClientFactory; both live in :app's tests, which this
 * module cannot link to).
 * No test ever touches the network.
 *
 * Behavior: each request consumes the next enqueued [PlannedReply];
 * when the queue is empty, [DEFAULT_JOKE] is served successfully. To
 * observe in-flight states deterministically, [holdResponses] parks
 * every request until [releaseResponses].
 */
class FakeJokeApi {

    /** One scripted outcome for a single request. */
    sealed interface PlannedReply {
        data class Success(val joke: JokeDto) : PlannedReply
        data class HttpError(val statusCode: HttpStatusCode) : PlannedReply

        /** Simulates no connectivity: the request throws IOException. */
        data object ConnectionFailure : PlannedReply
    }

    private val repliesLock = Any()
    private val plannedReplies = ArrayDeque<PlannedReply>()
    private val servedRequestCount = AtomicInteger(0)

    @Volatile
    private var responseGate: CompletableDeferred<Unit>? = null

    private val payloadJson = Json

    /** Total requests the fake has begun serving. */
    val requestCount: Int
        get() = servedRequestCount.get()

    fun enqueueSuccess(joke: JokeDto = DEFAULT_JOKE) {
        synchronized(repliesLock) {
            plannedReplies += PlannedReply.Success(joke)
        }
    }

    fun enqueueHttpError(
        statusCode: HttpStatusCode = HttpStatusCode.InternalServerError,
    ) {
        synchronized(repliesLock) {
            plannedReplies += PlannedReply.HttpError(statusCode)
        }
    }

    fun enqueueConnectionFailure() {
        synchronized(repliesLock) {
            plannedReplies += PlannedReply.ConnectionFailure
        }
    }

    /**
     * Parks every subsequent request until [releaseResponses], so a
     * test can assert the in-flight state (e.g. REFRESHING) before the
     * outcome lands. An unreleased gate is safe: closing the component
     * cancels the waiting fetch.
     */
    fun holdResponses() {
        responseGate = CompletableDeferred()
    }

    /** Lets every parked and future request proceed. */
    fun releaseResponses() {
        responseGate?.complete(Unit)
    }

    /** The MockEngine handler; installed by the test harness
     * (TestAppContext in :app's tests). */
    suspend fun MockRequestHandleScope.serveRequest(
        @Suppress("UNUSED_PARAMETER") request: HttpRequestData,
    ): HttpResponseData {
        servedRequestCount.incrementAndGet()
        responseGate?.await()
        val plannedReply = synchronized(repliesLock) {
            plannedReplies.removeFirstOrNull()
        } ?: PlannedReply.Success(DEFAULT_JOKE)
        return when (plannedReply) {
            is PlannedReply.Success -> respond(
                content = payloadJson.encodeToString(
                    JokeDto.serializer(),
                    plannedReply.joke,
                ),
                status = HttpStatusCode.OK,
                headers = headersOf(
                    HttpHeaders.ContentType,
                    ContentType.Application.Json.toString(),
                ),
            )
            is PlannedReply.HttpError -> respond(
                content = "simulated server error",
                status = plannedReply.statusCode,
            )
            PlannedReply.ConnectionFailure ->
                throw IOException("simulated connection failure")
        }
    }

    companion object {
        /** Served whenever the reply queue is empty. */
        val DEFAULT_JOKE = JokeDto(
            id = 1,
            type = "general",
            setup = "Why did the template cross the road?",
            punchline = "To be reused on the other side.",
        )
    }
}
