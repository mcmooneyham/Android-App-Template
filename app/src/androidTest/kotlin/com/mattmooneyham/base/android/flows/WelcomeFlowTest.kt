package com.mattmooneyham.base.android.flows

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.mattmooneyham.base.android.MainActivity
import org.junit.Rule
import org.junit.Test

/**
 * The welcome-flag round trip, end to end: launch marks the welcome as
 * seen (DataStore write -> event -> UI), Settings clears it, and Home
 * reflects the change: the full event-driven pipeline under real user
 * interaction.
 */
class WelcomeFlowTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun clearingTheWelcomeFlagReturnsHomeToFirstLaunch() {
        // Launch marks the welcome seen; the session card catches up.
        composeRule.waitForText("Welcome back!")

        composeRule.tabItem("Settings").performClick()
        composeRule.waitForText("Clear welcome flag")
        composeRule.onNodeWithText("Clear welcome flag").performClick()

        composeRule.tabItem("Home").performClick()
        composeRule.waitForText("First launch")
    }
}
