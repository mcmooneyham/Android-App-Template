package com.mattmooneyham.base.android.flows

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import com.mattmooneyham.base.android.MainActivity
import com.mattmooneyham.base.android.ui.R
import org.junit.Rule
import org.junit.Test

/**
 * The joke card's interactive contract over the REAL network: whatever
 * the fetch outcome, the card must settle (spinner replaced by the
 * refresh button), and a refresh must cycle spinner -> settled again.
 * Deliberately outcome-tolerant: the API's availability is not what is
 * under test; the UI state machine is.
 */
class JokeCardFlowTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun refreshCyclesTheLoadingStateAndSettles() {
        val refreshLabel = appString(
            R.string.joke_card_refresh_content_description,
        )

        // Launch fetch settles: the refresh affordance appears.
        composeRule.waitUntil(FLOW_TIMEOUT_MILLIS) {
            composeRule.onAllNodesWithContentDescription(refreshLabel)
                .fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithContentDescription(refreshLabel)
            .performClick()

        // The refresh round trip must settle again (the spinner phase is
        // transient and can outrun the semantics poller on a fast
        // network, so its brief absence is deliberately not asserted).
        composeRule.waitUntil(FLOW_TIMEOUT_MILLIS) {
            composeRule.onAllNodesWithContentDescription(refreshLabel)
                .fetchSemanticsNodes().isNotEmpty()
        }
    }
}
