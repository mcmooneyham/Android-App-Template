package com.mattmooneyham.base.android.api

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get

/**
 * Thin wrapper around the configured Ktor [HttpClient]. Projects built on
 * this template call it from event-publishing managers; JokeManager is
 * the live example: it fetches with `apiClient.get<JokeDto>("random_joke")`
 * and publishes the result through the EventManager.
 */
class ApiClient(
    val httpClient: HttpClient,
    val baseUrl: String,
) {

    /** GETs [path] relative to [baseUrl] and decodes the JSON response. */
    suspend inline fun <reified ResponseType> get(
        path: String,
        noinline configureRequest: HttpRequestBuilder.() -> Unit = {},
    ): ResponseType =
        httpClient.get(resolveUrl(path), configureRequest).body()

    /** Joins [baseUrl] and [path] with exactly one slash between them. */
    fun resolveUrl(path: String): String =
        baseUrl.trimEnd('/') + "/" + path.trimStart('/')
}
