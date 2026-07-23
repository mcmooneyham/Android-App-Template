package com.mattmooneyham.base.android.flows

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.mattmooneyham.base.android.MainActivity
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
        composeRule.waitForText("Welcome back!")

        composeRule.tabItem("Settings").performClick()
        composeRule.waitForText("Clear welcome flag")
        composeRule.tabItem("Home").performClick()

        // Immediately present: no reload state allowed after the trip.
        composeRule.onNodeWithText("Welcome back!").assertExists()
        composeRule.onNodeWithText("Loading preferences...")
            .assertDoesNotExist()
    }
}
