package com.mattmooneyham.base.android.flows

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.mattmooneyham.base.android.MainActivity
import org.junit.Rule
import org.junit.Test

/**
 * The typed-navigation contract on the real app: pushed screens
 * survive tab round trips (per-tab stacks are independent of tab
 * selection), system back pops them, and the whole typed stack
 * survives activity recreation, which exercises the same saved-state
 * path process death takes.
 */
class NavigationFlowTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private fun pressSystemBack() {
        composeRule.activityRule.scenario.onActivity { activity ->
            activity.onBackPressedDispatcher.onBackPressed()
        }
    }

    /**
     * Waits for the joke card to settle, then opens the detail via
     * the card's tap affordance (stable testTag; only a loaded joke
     * is tappable). Outcome-tolerant like JokeCardFlowTest: the real
     * network must have served a joke for the card to be tappable.
     */
    private fun openJokeDetail() {
        composeRule.waitUntil(FLOW_TIMEOUT_MILLIS) {
            composeRule.onAllNodesWithContentDescription("Refresh joke")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("JokeCard").performClick()
        composeRule.waitForText("Joke details")
    }

    @Test
    fun pushedDetailSurvivesTabRoundTripAndBackPopsIt() {
        composeRule.waitForText("Welcome back!")
        openJokeDetail()

        // Per-tab stacks are independent of tab selection: the pushed
        // detail is still there after a round trip through Settings.
        composeRule.tabItem("Settings").performClick()
        composeRule.waitForText("Clear welcome flag")
        composeRule.tabItem("Home").performClick()
        composeRule.onNodeWithText("Joke details").assertExists()

        // System back pops the detail and lands on the Home root
        // (asserted by a Home-page-unique node: the tab label also
        // says "Home", so that text is ambiguous by design).
        pressSystemBack()
        composeRule.waitForTextGone("Joke details")
        composeRule.onNodeWithText("Welcome back!").assertExists()
    }

    @Test
    fun typedStackSurvivesActivityRecreation() {
        composeRule.waitForText("Welcome back!")
        openJokeDetail()

        composeRule.activityRule.scenario.recreate()

        composeRule.waitForText("Joke details")
    }
}
