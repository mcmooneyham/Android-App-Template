package com.mattmooneyham.base.android.views.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.mattmooneyham.base.android.views.BaseAppTheme
import com.mattmooneyham.base.android.animations.AppAnimations
import com.mattmooneyham.base.android.constants.BrandColors

/**
 * Pill indicator for a binary status (online/offline, enabled/disabled).
 * The container and dot colors animate between the brand's success and
 * error colors whenever [isPositive] flips.
 */
@Composable
fun StatusChip(
    isPositive: Boolean,
    positiveText: String,
    negativeText: String,
    modifier: Modifier = Modifier,
) {
    val statusColor by animateColorAsState(
        targetValue = Color(
            if (isPositive) BrandColors.SUCCESS else BrandColors.DANGER,
        ),
        animationSpec = AppAnimations.statusColorSpec,
        label = "statusChipColor",
    )

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .background(
                color = statusColor.copy(alpha = 0.14f),
                shape = CircleShape,
            )
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(color = statusColor, shape = CircleShape),
        )
        Text(
            text = if (isPositive) positiveText else negativeText,
            style = MaterialTheme.typography.labelLarge,
            color = statusColor,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun StatusChipPreview() {
    BaseAppTheme {
        StatusChip(
            isPositive = true,
            positiveText = "Online",
            negativeText = "Offline",
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun StatusChipNegativePreview() {
    BaseAppTheme {
        StatusChip(
            isPositive = false,
            positiveText = "Online",
            negativeText = "Offline",
        )
    }
}
