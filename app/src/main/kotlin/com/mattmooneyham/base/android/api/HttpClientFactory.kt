package com.mattmooneyham.base.android.api

import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

/**
 * JSON configuration shared by the API layer: tolerant of unknown fields so
 * server-side additions never break deserialization.
 */
fun createDefaultJson(): Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

/**
 * Builds the shared HTTP client. The engine is auto-selected from the
 * Ktor engine artifact on the classpath (OkHttp), so calling code never
 * names one.
 */
fun createHttpClient(json: Json): HttpClient = HttpClient {
    // Non-2xx responses throw ResponseException, which is what maps them
    // to FetchFailure(HTTP); without this a 500 would surface as a
    // deserialization problem instead.
    expectSuccess = true
    install(ContentNegotiation) {
        json(json)
    }
}
