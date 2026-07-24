package com.mattmooneyham.base.android.flows

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.platform.app.InstrumentationRegistry
import com.mattmooneyham.base.android.BaseApplication
import com.mattmooneyham.base.android.di.AppComponent
import com.mattmooneyham.base.android.ui.R
import org.junit.Assume.assumeTrue

// Shared helpers for the instrumented flow tests. These drive the REAL
// app: real BaseApplication, real Hilt graph, real manager singletons,
// real DataStore in the app sandbox, against the REAL network. Nothing
// is faked, so joke- and connectivity-dependent tests self-skip (JUnit
// assumptions, not failures) when the network or the third-party joke
// API is unavailable.

const val FLOW_TIMEOUT_MILLIS = 20_000L

/**
 * The running app's composition root, so tests can drive the SAME
 * manager instances the UI under test observes.
 */
val appComponent: AppComponent
    get() {
        val applicationContext = InstrumentationRegistry
            .getInstrumentation().targetContext.applicationContext
        return (applicationContext as BaseApplication).appComponent
    }

/**
 * Resolves a localized string through the app under test's own
 * resources, so text matchers follow strings.xml instead of
 * hardcoding English copy.
 */
fun appString(resId: Int, vararg formatArgs: Any): String {
    val targetContext = InstrumentationRegistry
        .getInstrumentation().targetContext
    return if (formatArgs.isEmpty()) {
        targetContext.getString(resId)
    } else {
        targetContext.getString(resId, *formatArgs)
    }
}

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

/**
 * Waits up to [FLOW_TIMEOUT_MILLIS] for the joke card to become
 * tappable (only a loaded joke gets the detail-tap affordance), then
 * converts the outcome into a JUnit assumption: when the live joke
 * API never serves one (offline device, third-party outage) the
 * calling test SKIPS instead of failing.
 */
fun AndroidComposeTestRule<*, *>.assumeJokeLoaded() {
    val jokeCardBecameTappable = runCatching {
        waitUntil(FLOW_TIMEOUT_MILLIS) {
            onAllNodes(hasTestTag("JokeCard").and(hasClickAction()))
                .fetchSemanticsNodes().isNotEmpty()
        }
    }.isSuccess
    assumeTrue(
        "live joke API did not serve a joke; skipping",
        jokeCardBecameTappable,
    )
}

/**
 * Waits up to [FLOW_TIMEOUT_MILLIS] for the real network to resolve
 * as online (the HeroCard status pill), then converts the outcome
 * into a JUnit assumption: a device without connectivity SKIPS the
 * calling test instead of failing it.
 */
fun AndroidComposeTestRule<*, *>.assumeNetworkOnline() {
    val onlineLabel = appString(R.string.status_online)
    val networkResolvedOnline = runCatching {
        waitUntil(FLOW_TIMEOUT_MILLIS) {
            onAllNodesWithText(onlineLabel)
                .fetchSemanticsNodes().isNotEmpty()
        }
    }.isSuccess
    assumeTrue(
        "device network is unavailable; skipping",
        networkResolvedOnline,
    )
}
