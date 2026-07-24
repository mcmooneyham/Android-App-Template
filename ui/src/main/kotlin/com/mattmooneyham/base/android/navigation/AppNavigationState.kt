package com.mattmooneyham.base.android.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * The serializable snapshot of everything the router knows: the
 * selected tab and each tab's typed stack. This is what saved
 * instance state carries across configuration change AND process
 * death, replacing the old single-Int story.
 */
@Serializable
data class AppNavigationState(
    val selectedTab: AppTab = AppTab.HOME,
    val homeBackStack: List<HomeDestination> = emptyList(),
    val settingsBackStack: List<SettingsDestination> = emptyList(),
)

// ignoreUnknownKeys: state written by a NEWER app version (an added
// field) must still restore on this one after a downgrade.
private val navigationJson = Json { ignoreUnknownKeys = true }

/**
 * Restore that never crashes: stale JSON from an app update that
 * renamed or removed a destination falls back to a fresh root
 * instead of a restore-loop crash. Internal so the JVM spec can feed
 * it corrupt input directly.
 */
internal fun restoreNavigationState(
    savedStateJson: String,
): AppNavigationState = runCatching {
    navigationJson.decodeFromString<AppNavigationState>(savedStateJson)
}.getOrDefault(AppNavigationState())

private val AppRouterSaver: Saver<AppRouter, String> = Saver(
    save = { router ->
        navigationJson.encodeToString(router.snapshotState())
    },
    restore = { savedStateJson ->
        AppRouter(restoreNavigationState(savedStateJson))
    },
)

/**
 * Creates the router at the composition root, surviving both
 * configuration change and process death: the whole navigation state
 * round-trips through ONE JSON string in the saved instance state.
 */
@Composable
fun rememberAppRouter(): AppRouter =
    rememberSaveable(saver = AppRouterSaver) { AppRouter() }
