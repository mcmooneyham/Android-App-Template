package com.mattmooneyham.base.android.flows

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.mattmooneyham.base.android.MainActivity
import com.mattmooneyham.base.android.ui.R
import org.junit.Rule
import org.junit.Test

/**
 * The typed-navigation contract on the real app: pushed screens
 * survive tab round trips (per-tab stacks are independent of tab
 * selection), system back pops them, and the whole typed stack
 * survives activity recreation, which exercises the same saved-state
 * path process death takes. Opening the detail needs the live joke
 * API, so these tests self-skip (assumeJokeLoaded) when it never
 * serves a joke.
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
     * Opens the detail via the card's tap affordance (stable testTag;
     * only a loaded joke is tappable). The affordance needs a live
     * success from the real network, so the wait is a JUnit
     * assumption: offline runs SKIP instead of failing.
     */
    private fun openJokeDetail() {
        composeRule.assumeJokeLoaded()
        composeRule.onNodeWithTag("JokeCard").performClick()
        composeRule.waitForText(appString(R.string.joke_detail_title))
    }

    @Test
    fun pushedDetailSurvivesTabRoundTripAndBackPopsIt() {
        composeRule.waitForText(appString(R.string.session_welcome_back))
        openJokeDetail()

        // Per-tab stacks are independent of tab selection: the pushed
        // detail is still there after a round trip through Settings.
        composeRule.tabItem(appString(R.string.tab_settings))
            .performClick()
        composeRule.waitForText(
            appString(R.string.settings_clear_welcome_title),
        )
        composeRule.tabItem(appString(R.string.tab_home)).performClick()
        composeRule
            .onNodeWithText(appString(R.string.joke_detail_title))
            .assertExists()

        // System back pops the detail and lands on the Home root
        // (asserted by a Home-page-unique node: the tab label also
        // says "Home", so that text is ambiguous by design).
        pressSystemBack()
        composeRule.waitForTextGone(appString(R.string.joke_detail_title))
        composeRule
            .onNodeWithText(appString(R.string.session_welcome_back))
            .assertExists()
    }

    @Test
    fun typedStackSurvivesActivityRecreation() {
        composeRule.waitForText(appString(R.string.session_welcome_back))
        openJokeDetail()

        composeRule.activityRule.scenario.recreate()

        composeRule.waitForText(appString(R.string.joke_detail_title))
    }
}
