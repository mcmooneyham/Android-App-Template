package com.mattmooneyham.base.android.flows

import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.mattmooneyham.base.android.MainActivity
import com.mattmooneyham.base.android.BaseSdk
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The destructive-action flow against the REAL logs: cancel must
 * preserve prior history, confirm must clear it. The oracle is the log
 * CONTENT, not file existence: clearing intentionally writes one audit
 * line afterward ("Log file cleared from Settings"), which recreates
 * the file, so the user-facing contract is "old history is gone".
 */
class LogsFlowTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun clearLogsAsksFirstAndOnlyConfirmClearsHistory() {
        composeRule.tabItem("Settings").performClick()
        composeRule.waitForText("Clear logs")

        // Lay down a distinctive line we can track through the flow.
        val historyMarker = "flow-history-marker-${System.nanoTime()}"
        BaseSdk.logManager.info(historyMarker)
        composeRule.waitUntil(FLOW_TIMEOUT_MILLIS) {
            BaseSdk.logManager.readLogContents().contains(historyMarker)
        }

        // Cancel preserves the history.
        composeRule.onNodeWithText("Clear logs").performClick()
        composeRule.waitForText("Clear logs?")
        composeRule.onNodeWithText("Cancel").performClick()
        composeRule.waitForTextGone("Clear logs?")
        assertTrue(
            "cancel must not clear the logs",
            BaseSdk.logManager.readLogContents().contains(historyMarker),
        )

        // Confirm clears it. The dialog's confirm button shares its text
        // with the settings row, so it is matched inside the dialog only.
        composeRule.onNodeWithText("Clear logs").performClick()
        composeRule.waitForText("Clear logs?")
        composeRule.onNode(
            hasText("Clear logs").and(hasAnyAncestor(isDialog())),
        ).performClick()
        composeRule.waitUntil(FLOW_TIMEOUT_MILLIS) {
            !BaseSdk.logManager.readLogContents().contains(historyMarker)
        }
    }
}
