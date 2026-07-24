package com.mattmooneyham.base.android.views.components

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import com.mattmooneyham.base.android.animations.pressScale
import com.mattmooneyham.base.android.designSystem.AppDimens
import com.mattmooneyham.base.android.designSystem.AppShapes
import com.mattmooneyham.base.android.designSystem.AppSpacing
import com.mattmooneyham.base.android.views.BaseAppTheme
import com.mattmooneyham.base.android.constants.BrandColors

/**
 * Settings row with a tinted icon tile, title, optional supporting text,
 * and either a trailing value (info rows) or a chevron (actionable rows).
 * Destructive rows tint the tile and title with the danger color.
 */
@Composable
fun SettingsRow(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
    trailingValue: String? = null,
    isDestructive: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val accentColor =
        if (isDestructive) Color(BrandColors.DANGER)
        else MaterialTheme.colorScheme.primary
    val pressInteractionSource = remember { MutableInteractionSource() }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (onClick != null) {
                    // Press feedback (scale) rides on top of the ripple;
                    // both observe the same interaction source.
                    Modifier
                        .pressScale(pressInteractionSource)
                        .clickable(
                            interactionSource = pressInteractionSource,
                            indication = LocalIndication.current,
                        ) { onClick() }
                } else {
                    Modifier
                },
            )
            .padding(
                horizontal = AppSpacing.lg,
                vertical = AppSpacing.contentGapLarge,
            ),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(AppDimens.iconTileSize)
                .background(
                    color = accentColor.copy(alpha = 0.12f),
                    shape = AppShapes.iconTile,
                ),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = accentColor,
                modifier = Modifier.size(AppDimens.iconLarge),
            )
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = AppSpacing.contentGapLarge),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = if (isDestructive) accentColor
                else MaterialTheme.colorScheme.onSurface,
            )
            supportingText?.let { supporting ->
                Text(
                    text = supporting,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        when {
            trailingValue != null -> Text(
                text = trailingValue,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            onClick != null -> Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun SettingsRowPreview() {
    BaseAppTheme {
        SettingsRow(
            icon = Icons.Filled.Delete,
            title = "Clear logs",
            supportingText = "Delete the app's log file",
            isDestructive = true,
            onClick = {},
        )
    }
}
