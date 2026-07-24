package com.mattmooneyham.base.android.flows

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.mattmooneyham.base.android.ui.R
import com.mattmooneyham.base.android.views.BaseAppTheme
import com.mattmooneyham.base.android.views.TemplatePageContent
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

// ============================ TEMPLATE =============================
// HOW TO USE: copy this file into
// app/src/androidTest/kotlin/.../flows/, rename Template ->
// YourFeature, and delete these banners. IMPORTANT DIFFERENCE from
// this compiling exemplar: REAL flow tests drive the whole app, not a
// composable:
//
// ```
// @get:Rule
// val composeRule = createAndroidComposeRule<MainActivity>()
// ```
//
// with real Hilt, real managers, real DataStore, and NO test doubles
// (see JokeCardFlowTest/WelcomeFlowTest). This module cannot depend
// on :app, so the exemplar drives the stateless content directly,
// which is the SECOND legitimate instrumented shape: a component-level
// UI test.
// ===================================================================

/**
 * STEP 1: MATCH SEMANTICS, NOT INTERNALS. Find nodes by user-visible
 * text, contentDescription, or a stable testTag (the JokeCard
 * pattern) so tests survive refactors; never by tree position.
 * Localized copy resolves through :ui's R class ([templateString]),
 * the same resources the page reads, never through hardcoded English
 * literals.
 *
 * STEP 2: PREFER waitForText/waitUntil (FlowTestHelpers) over sleeps
 * in real flow tests: the app settles asynchronously.
 */
class TemplateFlowTest {

    @get:Rule
    val composeRule = createComposeRule()

    /** Resolves a string through the instrumented resources, so the
     * matchers follow strings.xml instead of hardcoding English. */
    private fun templateString(resId: Int): String =
        InstrumentationRegistry.getInstrumentation()
            .targetContext.getString(resId)

    @Test
    fun clearHistoryRowInvokesItsAction() {
        var clearInvocationCount = 0
        composeRule.setContent {
            BaseAppTheme {
                TemplatePageContent(
                    latestReading = 42,
                    readingCount = 3,
                    isEnriched = false,
                    onClearHistory = { clearInvocationCount += 1 },
                    onOpenDetail = {},
                )
            }
        }

        composeRule
            .onNodeWithText(
                templateString(R.string.template_latest_reading_title),
            )
            .assertExists()
        composeRule.onNodeWithText("42").assertExists()

        composeRule
            .onNodeWithText(
                templateString(R.string.template_clear_history_title),
            )
            .performClick()

        assertEquals(1, clearInvocationCount)
    }
}
