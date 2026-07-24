package com.mattmooneyham.base.android.views

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.mattmooneyham.base.android.templates.R
import com.mattmooneyham.base.android.managers.templateManager.TemplateStateChanged
import com.mattmooneyham.base.android.viewModels.TemplateViewModel
import com.mattmooneyham.base.android.views.components.SectionHeader
import com.mattmooneyham.base.android.views.components.SettingsGroupCard
import com.mattmooneyham.base.android.views.components.SettingsRow

// ============================ TEMPLATE =============================
// HOW TO USE: copy this file into ui/src/main/kotlin/.../views/,
// rename Template -> YourFeature, and delete these banners. NOTE:
// this template lives in the same `views` package as the real pages
// so the eventState helpers resolve without imports, exactly as they
// will in your copy.
// ===================================================================

/**
 * STEP 1: THE STATEFUL WRAPPER observes the bus and owns nothing.
 * eventStateOrNull for null-means-loading screens; eventState with an
 * initialValue when a sensible default exists. The viewmodel supplies
 * only write actions.
 *
 * STEP 2: NAVIGATION is a semantic lambda ([onOpenTemplateDetail]):
 * the shell wires it to the router; pages never see navigation
 * machinery, which is what keeps them previewable.
 */
@Composable
fun TemplatePage(
    templateViewModel: TemplateViewModel,
    onOpenTemplateDetail: (readingId: Int) -> Unit,
) {
    val templateState by eventStateOrNull(key = TemplateStateChanged)

    TemplatePageContent(
        latestReading = templateState?.latestReading,
        readingCount = templateState?.readingCount ?: 0,
        isEnriched = templateState?.isEnriched == true,
        onClearHistory = templateViewModel::clearHistory,
        // Only an existing reading can be opened; null hides the tap.
        onOpenDetail = templateState?.latestReading?.let { reading ->
            { onOpenTemplateDetail(reading) }
        },
    )
}

/**
 * STEP 3: THE STATELESS CONTENT takes plain values and lambdas, so
 * the preview below (and any screenshot test) renders every state
 * without a component, a bus, or Hilt. Build screens from the shared
 * components (SettingsGroupCard, SettingsRow, SectionHeader, cards)
 * and keep ALL motion in animations/AppAnimations.kt. User-facing
 * copy lives in res/values/strings.xml (stringResource) so the page
 * localizes; only preview sample data stays literal.
 *
 * (internal rather than private ONLY so this module's exemplar flow
 * test can drive it; real pages keep their content private, and real
 * flow tests drive MainActivity from app/src/androidTest.)
 */
@Composable
internal fun TemplatePageContent(
    latestReading: Int?,
    readingCount: Int,
    isEnriched: Boolean,
    onClearHistory: () -> Unit,
    onOpenDetail: (() -> Unit)?,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        Text(
            text = stringResource(R.string.template_title),
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
        )

        SectionHeader(
            title = stringResource(R.string.template_section_readings),
        )
        SettingsGroupCard {
            SettingsRow(
                icon = Icons.Filled.Info,
                title = stringResource(
                    R.string.template_latest_reading_title,
                ),
                supportingText = if (isEnriched) {
                    stringResource(R.string.template_reading_enriched)
                } else {
                    null
                },
                trailingValue = latestReading?.toString()
                    ?: stringResource(R.string.template_reading_none),
                onClick = onOpenDetail,
            )
            SettingsRow(
                icon = Icons.Filled.Delete,
                title = stringResource(
                    R.string.template_clear_history_title,
                ),
                supportingText = stringResource(
                    R.string.template_reading_count,
                    readingCount,
                ),
                isDestructive = true,
                onClick = onClearHistory,
            )
        }
    }
}

// STEP 4: EVERY view file carries a @Preview of its stateless
// content, exercising a real-looking state.
@Preview(showBackground = true)
@Composable
private fun TemplatePageContentPreview() {
    BaseAppTheme {
        TemplatePageContent(
            latestReading = 420,
            readingCount = 7,
            isEnriched = true,
            onClearHistory = {},
            onOpenDetail = {},
        )
    }
}
