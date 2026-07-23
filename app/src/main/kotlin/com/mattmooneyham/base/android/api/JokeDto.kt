package com.mattmooneyham.base.android.api

import kotlinx.serialization.Serializable

/**
 * A two-line joke from the Official Joke API
 * (official-joke-api.appspot.com), the template's keyless demo endpoint.
 */
@Serializable
data class JokeDto(
    val id: Int,
    val type: String,
    val setup: String,
    val punchline: String,
)
