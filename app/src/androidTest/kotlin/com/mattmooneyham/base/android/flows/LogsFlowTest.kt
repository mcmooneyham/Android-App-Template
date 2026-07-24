package com.mattmooneyham.base.android.flows

import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.mattmooneyham.base.android.MainActivity
import com.mattmooneyham.base.android.ui.R
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
        val clearLogsRow = appString(R.string.settings_clear_logs_title)
        val dialogTitle = appString(R.string.clear_logs_dialog_title)

        composeRule.tabItem(appString(R.string.tab_settings))
            .performClick()
        composeRule.waitForText(clearLogsRow)

        // Lay down a distinctive line we can track through the flow.
        val historyMarker = "flow-history-marker-${System.nanoTime()}"
        val logManager = appComponent.logManager
        logManager.info(historyMarker)
        composeRule.waitUntil(FLOW_TIMEOUT_MILLIS) {
            logManager.readLogContents().contains(historyMarker)
        }

        // Cancel preserves the history.
        composeRule.onNodeWithText(clearLogsRow).performClick()
        composeRule.waitForText(dialogTitle)
        composeRule
            .onNodeWithText(appString(R.string.clear_logs_dialog_cancel))
            .performClick()
        composeRule.waitForTextGone(dialogTitle)
        assertTrue(
            "cancel must not clear the logs",
            appComponent.logManager.readLogContents()
                .contains(historyMarker),
        )

        // Confirm clears it. The dialog's confirm button shares its text
        // with the settings row, so it is matched inside the dialog only.
        composeRule.onNodeWithText(clearLogsRow).performClick()
        composeRule.waitForText(dialogTitle)
        composeRule.onNode(
            hasText(appString(R.string.clear_logs_dialog_confirm))
                .and(hasAnyAncestor(isDialog())),
        ).performClick()
        composeRule.waitUntil(FLOW_TIMEOUT_MILLIS) {
            !appComponent.logManager.readLogContents()
                .contains(historyMarker)
        }
    }
}
