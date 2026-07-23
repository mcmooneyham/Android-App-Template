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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.mattmooneyham.base.android.views.BaseAppTheme
import com.mattmooneyham.base.android.animations.AppAnimations
import com.mattmooneyham.base.android.constants.BrandColors

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
                shape = RoundedCornerShape(28.dp),
            ),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(24.dp),
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
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(
                            color = statusDotColor,
                            shape = CircleShape,
                        ),
                )
                Text(
                    text = if (isOnline) "Online" else "Offline",
                    style = MaterialTheme.typography.labelLarge,
                    color = Color(BrandColors.WHITE),
                    modifier = Modifier.padding(start = 8.dp),
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
            label = "Shared SDK",
            headline = "Hello, Android 36!",
            isOnline = true,
        )
    }
}
