package com.mattmooneyham.base.android.flows

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.mattmooneyham.base.android.MainActivity
import com.mattmooneyham.base.android.ui.R
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
        val clearWelcomeRow = appString(
            R.string.settings_clear_welcome_title,
        )

        // Launch marks the welcome seen; the session card catches up.
        composeRule.waitForText(appString(R.string.session_welcome_back))

        composeRule.tabItem(appString(R.string.tab_settings))
            .performClick()
        composeRule.waitForText(clearWelcomeRow)
        composeRule.onNodeWithText(clearWelcomeRow).performClick()

        composeRule.tabItem(appString(R.string.tab_home)).performClick()
        composeRule.waitForText(appString(R.string.session_first_launch))
    }
}
