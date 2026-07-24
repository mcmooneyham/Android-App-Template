package com.mattmooneyham.base.android.navigation

import kotlinx.serialization.Serializable

/**
 * The root tabs, as data. Replaces the old saveable Int index so
 * every tab switch is an exhaustive `when`: adding a tab is a compile
 * error at each switch until it is handled, and no index can ever
 * fall into a silent else.
 */
@Serializable
enum class AppTab { HOME, SETTINGS }

/**
 * Every screen the Home tab can push, as data (the iOS sibling's
 * HomeDestination). The compiler now enforces navigation: exhaustive
 * `when`s, find-usages per screen, dead screens visible as unused
 * types. Serializable because the whole navigation state round-trips
 * through saved instance state (see AppNavigationState), so the
 * payload-evolution rules in ARCHITECTURE-SCALING.md apply verbatim:
 * new arguments get defaults, and a repurposed screen is a NEW
 * destination type.
 */
@Serializable
sealed interface HomeDestination {

    /** Full-screen detail for one joke, deep-linkable by id. */
    @Serializable
    data class JokeDetail(val jokeId: Int) : HomeDestination
}

/**
 * Settings pushes nothing yet; the empty type keeps both tabs
 * uniform so the first pushed Settings screen is purely additive.
 */
@Serializable
sealed interface SettingsDestination
