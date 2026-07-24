package com.mattmooneyham.base.android.api

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondOk
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.pluginOrNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the shared client and Json configuration with real teeth: the
 * factory is the ONE place every fetch's transport behavior is set,
 * so a silently dropped plugin or a loosened Json mode must fail here
 * rather than surface as a production mystery.
 */
class HttpClientFactorySpec {

    @Test
    fun `the built client has the timeout plugin installed`() {
        val httpClient = createHttpClient(
            json = createDefaultJson(),
            engine = MockEngine { respondOk("{}") },
        )
        try {
            // Engine defaults ship NO overall call budget; deleting
            // install(HttpTimeout) from the factory must fail this.
            assertNotNull(
                "HttpTimeout must be installed on the shared client",
                httpClient.pluginOrNull(HttpTimeout),
            )
        } finally {
            httpClient.close()
        }
    }

    @Test
    fun `the shared Json is strict except for unknown keys`() {
        val jsonConfiguration = createDefaultJson().configuration
        // Strict parsing routes wrong payloads to FailureKind.DECODE
        // instead of silently coercing them.
        assertFalse(jsonConfiguration.isLenient)
        // Server-side field additions must never break decoding.
        assertTrue(jsonConfiguration.ignoreUnknownKeys)
    }
}
