package com.mattmooneyham.base.android.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The router is a plain class over snapshot state, so its whole
 * contract (per-tab stacks, back semantics, deep links, restoration)
 * runs on the JVM beside the manager specs: no composition, no
 * Robolectric, no mocks.
 */
class AppRouterSpec {

    @Test
    fun `back pops only the selected tab's stack`() {
        val appRouter = AppRouter()
        appRouter.homeRouter.push(
            HomeDestination.JokeDetail(jokeId = 7),
        )

        appRouter.selectTab(AppTab.SETTINGS)
        // Settings is at its root: back is NOT consumed here,
        // whatever Home's stack holds.
        assertFalse(appRouter.canGoBack)
        assertFalse(appRouter.handleBack())

        appRouter.selectTab(AppTab.HOME)
        assertTrue(appRouter.canGoBack)
        assertTrue(appRouter.handleBack())
        assertTrue(appRouter.homeRouter.backStack.isEmpty())
        assertFalse(appRouter.canGoBack)
    }

    @Test
    fun `reselecting the current tab pops it to its root`() {
        val appRouter = AppRouter()
        appRouter.homeRouter.push(
            HomeDestination.JokeDetail(jokeId = 7),
        )

        appRouter.selectTab(AppTab.HOME)

        assertEquals(AppTab.HOME, appRouter.selectedTab)
        assertTrue(appRouter.homeRouter.backStack.isEmpty())
    }

    @Test
    fun `a deep link selects Home and replaces its whole stack`() {
        val appRouter = AppRouter()
        appRouter.selectTab(AppTab.SETTINGS)
        appRouter.homeRouter.push(
            HomeDestination.JokeDetail(jokeId = 1),
        )

        assertTrue(appRouter.handleDeepLink("baseapp://joke/42"))

        assertEquals(AppTab.HOME, appRouter.selectedTab)
        assertEquals(
            listOf(HomeDestination.JokeDetail(jokeId = 42)),
            appRouter.homeRouter.backStack,
        )
    }

    @Test
    fun `unrecognized deep links are rejected without state changes`() {
        val appRouter = AppRouter()
        val rejectedUrls = listOf(
            "https://joke/42", // wrong scheme
            "baseapp://video/9", // unknown host
            "baseapp://joke/abc", // non-numeric id
            "baseapp://joke/", // missing id
            "definitely not a url", // unparseable
        )

        rejectedUrls.forEach { rejectedUrl ->
            assertFalse(
                rejectedUrl,
                appRouter.handleDeepLink(rejectedUrl),
            )
        }

        assertEquals(AppTab.HOME, appRouter.selectedTab)
        assertTrue(appRouter.homeRouter.backStack.isEmpty())
    }

    @Test
    fun `navigation state survives a snapshot round trip`() {
        val originalRouter = AppRouter()
        originalRouter.handleDeepLink("baseapp://joke/7")
        originalRouter.homeRouter.push(
            HomeDestination.JokeDetail(jokeId = 8),
        )
        originalRouter.selectTab(AppTab.SETTINGS)

        val restoredRouter = AppRouter(originalRouter.snapshotState())

        assertEquals(
            originalRouter.snapshotState(),
            restoredRouter.snapshotState(),
        )
        assertEquals(AppTab.SETTINGS, restoredRouter.selectedTab)
        assertEquals(2, restoredRouter.homeRouter.backStack.size)
    }

    @Test
    fun `corrupt saved state restores to a fresh root, not a crash`() {
        // The exact hazard: an app update renamed a destination and
        // the old JSON no longer decodes. Restore must degrade to a
        // fresh root instead of a restore-loop crash.
        assertEquals(
            AppNavigationState(),
            restoreNavigationState("{\"garbage\":true"),
        )
        assertEquals(
            AppNavigationState(),
            restoreNavigationState(
                "{\"homeBackStack\":[{\"type\":\"RenamedScreen\"}]}",
            ),
        )
    }

    @Test
    fun `valid saved state round-trips through the JSON layer`() {
        val originalRouter = AppRouter()
        originalRouter.handleDeepLink("baseapp://joke/21")

        // The same path the Saver takes: encode, decode, rebuild.
        val savedJson = kotlinx.serialization.json.Json
            .encodeToString(
                AppNavigationState.serializer(),
                originalRouter.snapshotState(),
            )
        val restoredRouter =
            AppRouter(restoreNavigationState(savedJson))

        assertEquals(
            originalRouter.snapshotState(),
            restoredRouter.snapshotState(),
        )
    }

    @Test
    fun `every tab's state survives a snapshot round trip`() {
        // Exhaustive when STATEMENT (no else) over the enum: adding a
        // tab refuses to compile here until its snapshot wiring is
        // asserted below.
        AppTab.entries.forEach { tab ->
            when (tab) {
                AppTab.HOME -> {
                    val originalRouter = AppRouter()
                    originalRouter.homeRouter.push(
                        HomeDestination.JokeDetail(jokeId = 3),
                    )
                    val restoredRouter =
                        AppRouter(originalRouter.snapshotState())
                    assertEquals(
                        listOf(HomeDestination.JokeDetail(jokeId = 3)),
                        restoredRouter.homeRouter.backStack,
                    )
                }
                AppTab.SETTINGS -> {
                    // No pushable Settings destination exists yet; the
                    // tab's persisted fact is the selection itself.
                    val originalRouter = AppRouter()
                    originalRouter.selectTab(AppTab.SETTINGS)
                    val restoredRouter =
                        AppRouter(originalRouter.snapshotState())
                    assertEquals(
                        AppTab.SETTINGS,
                        restoredRouter.selectedTab,
                    )
                }
            }
        }
    }
}
