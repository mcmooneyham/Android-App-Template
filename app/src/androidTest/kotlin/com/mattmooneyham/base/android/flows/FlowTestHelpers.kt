package com.mattmooneyham.base.android.flows

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onAllNodesWithText

// Shared helpers for the instrumented flow tests. These drive the REAL
// app: real BaseApplication, real Hilt graph, real manager singletons, real
// DataStore in the app sandbox. Nothing is faked.

const val FLOW_TIMEOUT_MILLIS = 20_000L

/**
 * The bottom bar item for [label]. Matched by Role.Tab so it can never
 * collide with page titles that carry the same text.
 */
fun AndroidComposeTestRule<*, *>.tabItem(
    label: String,
): SemanticsNodeInteraction = onNode(
    SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)
        .and(hasText(label)),
)

/** Waits until [text] exists somewhere in the semantics tree. */
fun AndroidComposeTestRule<*, *>.waitForText(text: String) {
    waitUntil(FLOW_TIMEOUT_MILLIS) {
        onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }
}

/** Waits until [text] no longer exists in the semantics tree. */
fun AndroidComposeTestRule<*, *>.waitForTextGone(text: String) {
    waitUntil(FLOW_TIMEOUT_MILLIS) {
        onAllNodesWithText(text).fetchSemanticsNodes().isEmpty()
    }
}
