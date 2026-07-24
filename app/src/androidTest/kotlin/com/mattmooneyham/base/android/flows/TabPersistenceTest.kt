package com.mattmooneyham.base.android.flows

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.mattmooneyham.base.android.MainActivity
import com.mattmooneyham.base.android.ui.R
import org.junit.Rule
import org.junit.Test

/**
 * The keep-alive tab shell contract: Home's state survives a round trip
 * through Settings with no reload ("Loading preferences..." must never
 * reappear once resolved).
 */
class TabPersistenceTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun homeStateSurvivesATabRoundTrip() {
        val welcomeBack = appString(R.string.session_welcome_back)
        composeRule.waitForText(welcomeBack)

        composeRule.tabItem(appString(R.string.tab_settings))
            .performClick()
        composeRule.waitForText(
            appString(R.string.settings_clear_welcome_title),
        )
        composeRule.tabItem(appString(R.string.tab_home)).performClick()

        // Immediately present: no reload state allowed after the trip.
        composeRule.onNodeWithText(welcomeBack).assertExists()
        composeRule.onNodeWithText(appString(R.string.session_loading))
            .assertDoesNotExist()
    }
}
