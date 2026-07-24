package com.mattmooneyham.base.android.api

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

/**
 * JSON configuration shared by the API layer: tolerant of unknown
 * fields so server-side additions never break deserialization, and
 * otherwise STRICT. Lenient parsing would silently coerce quoting and
 * primitive deviations, masking exactly the failures the
 * [FailureKind.DECODE] taxonomy exists to surface.
 */
fun createDefaultJson(): Json = Json {
    ignoreUnknownKeys = true
}

/** Overall per-call budget. Bounds even a trickling response body,
 * which per-read socket timeouts never would, so a stalled fetch can
 * never hold in-flight state forever. */
const val CALL_TIMEOUT_MILLIS = 30_000L

/** Fail fast when the server cannot be reached at all. */
const val CONNECT_TIMEOUT_MILLIS = 10_000L

/** Inactivity budget between socket reads/writes. */
const val SOCKET_TIMEOUT_MILLIS = 10_000L

/**
 * Builds the shared HTTP client. With no [engine], the engine is
 * auto-selected from the Ktor engine artifact on the classpath
 * (OkHttp), so calling code never names one. Tests pass a MockEngine:
 * the PRODUCTION configuration below (expectSuccess, negotiation,
 * timeouts) is then exactly what every JVM spec exercises, so it
 * cannot drift from a hand-mirrored copy.
 */
fun createHttpClient(
    json: Json,
    engine: HttpClientEngine? = null,
): HttpClient =
    if (engine == null) {
        HttpClient { applyBaseClientConfig(json) }
    } else {
        HttpClient(engine) { applyBaseClientConfig(json) }
    }

private fun HttpClientConfig<*>.applyBaseClientConfig(json: Json) {
    // Non-2xx responses throw ResponseException, which is what maps
    // them to FetchFailure(HTTP); without this a 500 would surface as
    // a deserialization problem instead.
    expectSuccess = true
    install(ContentNegotiation) {
        json(json)
    }
    // Engine defaults ship NO overall call budget (a slowly trickling
    // body can outlive per-read socket timeouts indefinitely). These
    // caps guarantee every request terminates, surfacing as
    // FailureKind.TIMEOUT through the failure taxonomy.
    install(HttpTimeout) {
        requestTimeoutMillis = CALL_TIMEOUT_MILLIS
        connectTimeoutMillis = CONNECT_TIMEOUT_MILLIS
        socketTimeoutMillis = SOCKET_TIMEOUT_MILLIS
    }
}
