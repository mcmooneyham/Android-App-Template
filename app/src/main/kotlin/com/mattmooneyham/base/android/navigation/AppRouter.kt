package com.mattmooneyham.base.android.navigation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.net.URI

/**
 * The app's routing state: the selected tab plus one typed back stack
 * per tab, mirroring the iOS sibling's HomeRouter. A plain class,
 * owned by the composition via rememberAppRouter.
 *
 * THE SEAM RULE: managers and viewmodels NEVER hold or call the
 * router; they publish facts on the event bus, and the shell (the
 * router's only owner) maps facts to router calls in one choke point
 * (RouteOnAppEvents in NavigationBar.kt). Direct user actions stay
 * synchronous and dumb: a page's semantic lambda (onOpenJokeDetail)
 * calls the router from the shell's wiring. A LocalAppRouter
 * CompositionLocal is deliberately NOT provided; its threshold is
 * the first navigation lambda that must thread through three or more
 * composable layers.
 */
class AppRouter(
    restoredState: AppNavigationState = AppNavigationState(),
) {

    var selectedTab by mutableStateOf(restoredState.selectedTab)
        private set

    val homeRouter =
        TabRouter<HomeDestination>(restoredState.homeBackStack)
    val settingsRouter =
        TabRouter<SettingsDestination>(restoredState.settingsBackStack)

    /** True while system back should pop instead of exiting. */
    val canGoBack: Boolean get() = routerFor(selectedTab).canPop

    /**
     * Selects [tab]; re-selecting the current tab pops it to its
     * root (the tab-bar convention on both platform siblings).
     */
    fun selectTab(tab: AppTab) {
        if (tab == selectedTab) {
            routerFor(tab).popToRoot()
        } else {
            selectedTab = tab
        }
    }

    /**
     * Back pops within the SELECTED tab only; false means "at a
     * root" and lets the system back finish the activity. (If the
     * product ever wants back-returns-to-Home-tab instead, this is
     * the one method that changes.)
     */
    fun handleBack(): Boolean = routerFor(selectedTab).pop()

    /**
     * Deep links map to navigation state in ONE place (the iOS
     * sibling's HomeRouter.handle): baseapp://joke/<id> selects Home
     * showing that joke's detail. Unrecognized or malformed URLs
     * return false and change NOTHING. New links add a branch here
     * and an intent filter in the manifest; nothing else. Replace,
     * not push: a deep link states the whole intended stack.
     */
    fun handleDeepLink(deepLinkUrl: String): Boolean {
        val parsedUri =
            runCatching { URI(deepLinkUrl) }.getOrNull() ?: return false
        if (parsedUri.scheme != DEEP_LINK_SCHEME) return false
        return when (parsedUri.host) {
            JOKE_DEEP_LINK_HOST -> {
                val jokeId = parsedUri.path.orEmpty()
                    .removePrefix("/")
                    .toIntOrNull()
                    ?: return false
                selectedTab = AppTab.HOME
                homeRouter.replaceAll(
                    HomeDestination.JokeDetail(jokeId = jokeId),
                )
                true
            }
            else -> false
        }
    }

    /** The saver's snapshot; also the unit tests' equality view. */
    fun snapshotState(): AppNavigationState = AppNavigationState(
        selectedTab = selectedTab,
        homeBackStack = homeRouter.backStack.toList(),
        settingsBackStack = settingsRouter.backStack.toList(),
    )

    // Exhaustive by construction: a new tab will not compile until
    // it has a router here. Star projection is safe: only the
    // destination-free members (pop, popToRoot, canPop) are called
    // through this.
    private fun routerFor(tab: AppTab): TabRouter<*> = when (tab) {
        AppTab.HOME -> homeRouter
        AppTab.SETTINGS -> settingsRouter
    }

    private companion object {
        const val DEEP_LINK_SCHEME = "baseapp"
        const val JOKE_DEEP_LINK_HOST = "joke"
    }
}
