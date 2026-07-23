package com.mattmooneyham.base.android.api

import io.ktor.client.plugins.ResponseException
import io.ktor.serialization.ContentConvertException
import kotlinx.io.IOException
import kotlinx.serialization.SerializationException

/** Broad category of a failed remote fetch, for UI copy decisions. */
enum class FailureKind {
    NETWORK,
    HTTP,
    DECODE,
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
