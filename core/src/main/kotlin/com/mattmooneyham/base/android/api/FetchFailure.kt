package com.mattmooneyham.base.android.api

import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.ResponseException
import io.ktor.serialization.ContentConvertException
import java.io.IOException
import kotlinx.serialization.SerializationException

/** Broad category of a failed remote fetch, for UI copy decisions. */
enum class FailureKind {
    NETWORK,
    HTTP,
    DECODE,
    TIMEOUT,
    UNKNOWN,
}

/**
 * Typed failure carried inside manager state events, so views choose
 * user-facing copy by [kind] instead of parsing exception strings.
 * [detail] is the technical message, for logs and diagnostics only.
 */
data class FetchFailure(
    val kind: FailureKind,
    val detail: String? = null,
)

/** Maps a caught fetch exception to its typed [FetchFailure]. */
fun Throwable.toFetchFailure(): FetchFailure = when (this) {
    // Timeout arms come FIRST: every one of these is an IOException,
    // and the NETWORK arm below would otherwise swallow them.
    is HttpRequestTimeoutException,
    is ConnectTimeoutException,
    is SocketTimeoutException,
    is java.net.SocketTimeoutException,
    -> FetchFailure(
        kind = FailureKind.TIMEOUT,
        detail = message,
    )
    is ResponseException -> FetchFailure(
        kind = FailureKind.HTTP,
        detail = "HTTP ${response.status}",
    )
    is ContentConvertException, is SerializationException -> FetchFailure(
        kind = FailureKind.DECODE,
        detail = message,
    )
    is IOException -> FetchFailure(
        kind = FailureKind.NETWORK,
        detail = message,
    )
    else -> FetchFailure(
        kind = FailureKind.UNKNOWN,
        detail = message,
    )
}
