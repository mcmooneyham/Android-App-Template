package com.mattmooneyham.base.android.views.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.mattmooneyham.base.android.constants.BrandColors
import com.mattmooneyham.base.android.managers.JokeAutoRetryOnReconnectFlag
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
// (the provider/default layers decide).
private val OVERRIDE_CHOICES: List<Pair<String, Boolean?>> = listOf(
    "Default" to null,
    "On" to true,
    "Off" to false,
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
            .padding(horizontal = 20.dp),
    ) {
        Text(
            text = "Feature flags",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = "Local overrides beat remote and default values and " +
                "persist across launches. Debug builds only.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )

        Spacer(modifier = Modifier.height(16.dp))

        SettingsGroupCard {
            flagRows.forEachIndexed { rowIndex, flagRow ->
                if (rowIndex > 0) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
                FeatureFlagRow(
                    flagRow = flagRow,
                    onOverrideSelected = onOverrideSelected,
                )
            }
        }

        Spacer(
            modifier = Modifier
                .height(24.dp)
                .navigationBarsPadding(),
        )
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
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = flagRow.flag.description,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "${flagRow.flag.flagKey} · " +
                        describeSource(flagRow.source),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            // Live resolved state; animates between the brand success
            // and danger colors as overrides are applied.
            StatusChip(
                isPositive = flagRow.resolvedEnabled,
                positiveText = "On",
                negativeText = "Off",
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Brand at 14% fill for the active segment, matching the tab
        // bar's selection pill.
        val segmentColors = SegmentedButtonDefaults.colors(
            activeContainerColor =
                Color(BrandColors.BRAND).copy(alpha = 0.14f),
            activeContentColor = Color(BrandColors.BRAND),
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
            OVERRIDE_CHOICES.forEachIndexed { index, (label, value) ->
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
                            text = label,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    },
                )
            }
        }
    }
}

private fun describeSource(source: FlagSource): String = when (source) {
    FlagSource.DEFAULT -> "compiled default"
    FlagSource.PROVIDER -> "remote value"
    FlagSource.OVERRIDE -> "local override"
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
