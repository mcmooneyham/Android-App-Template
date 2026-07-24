package com.mattmooneyham.base.android.views.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.annotation.StringRes
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import com.mattmooneyham.base.android.ui.R
import com.mattmooneyham.base.android.designSystem.AppSpacing
import com.mattmooneyham.base.android.managers.jokeManager.JokeAutoRetryOnReconnectFlag
import com.mattmooneyham.base.android.managers.featureFlagManager.BooleanFlag
import com.mattmooneyham.base.android.managers.featureFlagManager.FlagSource
import com.mattmooneyham.base.android.views.BaseAppTheme

/** One flag's render model for the feature-flags sheet. */
data class FlagRowUiState(
    val flag: BooleanFlag,
    val resolvedEnabled: Boolean,
    val source: FlagSource,
)

// The three override choices, in display order. null = no override
// (the provider/default layers decide). Labels are resource IDs,
// resolved in the composable segment row, so the control localizes.
private val OVERRIDE_CHOICES: List<Pair<Int, Boolean?>> = listOf(
    R.string.flag_override_default to null,
    R.string.flag_override_on to true,
    R.string.flag_override_off to false,
)

/**
 * Body of the Settings > Debug > Feature flags modal sheet: a header
 * plus every declared flag in the app's grouped-card style. Each row
 * shows the flag's live resolved state (the shared StatusChip pill),
 * which layer decided it, and a brand-tinted tri-state
 * override control; "Default" clears the local override so the
 * provider or compiled value decides again.
 *
 * Stateless and previewable; the modal wrapper lives in SettingsPage.
 */
@Composable
fun FeatureFlagSheetContent(
    flagRows: List<FlagRowUiState>,
    onOverrideSelected: (BooleanFlag, Boolean?) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = AppSpacing.screenEdge),
    ) {
        Text(
            text = stringResource(R.string.flag_sheet_title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = stringResource(R.string.flag_sheet_explainer),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = AppSpacing.xs),
        )

        Spacer(modifier = Modifier.height(AppSpacing.sectionGap))

        SettingsGroupCard {
            flagRows.forEachIndexed { rowIndex, flagRow ->
                if (rowIndex > 0) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant,
                        modifier = Modifier
                            .padding(horizontal = AppSpacing.lg),
                    )
                }
                FeatureFlagRow(
                    flagRow = flagRow,
                    onOverrideSelected = onOverrideSelected,
                )
            }
        }

        Spacer(modifier = Modifier.height(AppSpacing.pageBottom))
    }
}

@Composable
private fun FeatureFlagRow(
    flagRow: FlagRowUiState,
    onOverrideSelected: (BooleanFlag, Boolean?) -> Unit,
) {
    // Which segment is active: the override value when one is set,
    // otherwise "Default" (whatever layer is currently deciding).
    val selectedOverride: Boolean? =
        if (flagRow.source == FlagSource.OVERRIDE) {
            flagRow.resolvedEnabled
        } else {
            null
        }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = AppSpacing.lg,
                vertical = AppSpacing.contentGapLarge,
            ),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = flagRow.flag.description,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                // The flagKey is a technical identifier and stays
                // raw; only the source description localizes.
                Text(
                    text = "${flagRow.flag.flagKey} · " +
                        stringResource(sourceLabelResId(flagRow.source)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.width(AppSpacing.md))
            // Live resolved state; animates between the brand success
            // and danger colors as overrides are applied.
            StatusChip(
                isPositive = flagRow.resolvedEnabled,
                positiveText = stringResource(R.string.flag_state_on),
                negativeText = stringResource(R.string.flag_state_off),
            )
        }

        Spacer(modifier = Modifier.height(AppSpacing.md))

        // Brand at 14% fill for the active segment, matching the tab
        // bar's selection pill.
        val segmentColors = SegmentedButtonDefaults.colors(
            activeContainerColor =
                MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
            activeContentColor = MaterialTheme.colorScheme.primary,
            activeBorderColor = MaterialTheme.colorScheme.outlineVariant,
            inactiveContainerColor = Color.Transparent,
            inactiveContentColor =
                MaterialTheme.colorScheme.onSurfaceVariant,
            inactiveBorderColor =
                MaterialTheme.colorScheme.outlineVariant,
        )
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier.fillMaxWidth(),
        ) {
            OVERRIDE_CHOICES.forEachIndexed { index, (labelResId, value) ->
                SegmentedButton(
                    selected = value == selectedOverride,
                    onClick = { onOverrideSelected(flagRow.flag, value) },
                    shape = SegmentedButtonDefaults.itemShape(
                        index = index,
                        count = OVERRIDE_CHOICES.size,
                    ),
                    colors = segmentColors,
                    label = {
                        Text(
                            text = stringResource(labelResId),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    },
                )
            }
        }
    }
}

// Resource ID rather than text: the caller resolves it in composable
// context, keeping this mapping usable outside composition too.
@StringRes
private fun sourceLabelResId(source: FlagSource): Int = when (source) {
    FlagSource.DEFAULT -> R.string.flag_source_default
    FlagSource.PROVIDER -> R.string.flag_source_provider
    FlagSource.OVERRIDE -> R.string.flag_source_override
}

// Both preview rows reuse the registered demo flag (a preview-only
// BooleanFlag object would trip the registry guard); the row renders
// from the UiState values alone.
@Preview(showBackground = true)
@Composable
private fun FeatureFlagSheetContentPreview() {
    BaseAppTheme {
        FeatureFlagSheetContent(
            flagRows = listOf(
                FlagRowUiState(
                    flag = JokeAutoRetryOnReconnectFlag,
                    resolvedEnabled = false,
                    source = FlagSource.DEFAULT,
                ),
                FlagRowUiState(
                    flag = JokeAutoRetryOnReconnectFlag,
                    resolvedEnabled = true,
                    source = FlagSource.OVERRIDE,
                ),
            ),
            onOverrideSelected = { _, _ -> },
        )
    }
}
