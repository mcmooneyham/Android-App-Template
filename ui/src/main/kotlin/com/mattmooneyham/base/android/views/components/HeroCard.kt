package com.mattmooneyham.base.android.views.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import com.mattmooneyham.base.android.ui.R
import com.mattmooneyham.base.android.views.BaseAppTheme
import com.mattmooneyham.base.android.animations.AppAnimations
import com.mattmooneyham.base.android.constants.BrandColors
import com.mattmooneyham.base.android.designSystem.AppDimens
import com.mattmooneyham.base.android.designSystem.AppShapes
import com.mattmooneyham.base.android.designSystem.AppSpacing

/**
 * Gradient hero banner for the top of a page: an eyebrow label, a large
 * headline, and a live status pill rendered on the brand gradient.
 */
@Composable
fun HeroCard(
    label: String,
    headline: String,
    isOnline: Boolean,
    modifier: Modifier = Modifier,
) {
    val statusDotColor by animateColorAsState(
        targetValue = Color(
            if (isOnline) BrandColors.SUCCESS else BrandColors.DANGER,
        ),
        animationSpec = AppAnimations.statusColorSpec,
        label = "heroStatusDot",
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color(BrandColors.BRAND),
                        Color(BrandColors.BRAND_HOVER),
                    ),
                ),
                shape = AppShapes.heroCard,
            ),
    ) {
        Column(
            verticalArrangement =
                Arrangement.spacedBy(AppSpacing.contentGapMedium),
            modifier = Modifier.padding(AppSpacing.xxl),
        ) {
            Text(
                text = label.uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = Color(BrandColors.WHITE).copy(alpha = 0.7f),
            )
            Text(
                text = headline,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = Color(BrandColors.WHITE),
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .background(
                        color = Color(BrandColors.WHITE).copy(alpha = 0.16f),
                        shape = CircleShape,
                    )
                    .padding(
                        horizontal = AppSpacing.md,
                        vertical = AppSpacing.contentGapSmall,
                    ),
            ) {
                Box(
                    modifier = Modifier
                        .size(AppDimens.statusDotSize)
                        .background(
                            color = statusDotColor,
                            shape = CircleShape,
                        ),
                )
                Text(
                    text = stringResource(
                        if (isOnline) R.string.status_online
                        else R.string.status_offline,
                    ),
                    style = MaterialTheme.typography.labelLarge,
                    color = Color(BrandColors.WHITE),
                    modifier = Modifier.padding(start = AppSpacing.sm),
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun HeroCardPreview() {
    BaseAppTheme {
        HeroCard(
            label = "App core",
            headline = "Hello, Android 36!",
            isOnline = true,
        )
    }
}
