package com.mattmooneyham.base.android.views.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.mattmooneyham.base.android.animations.AppAnimations
import com.mattmooneyham.base.android.animations.skeletonPulseAlpha
import com.mattmooneyham.base.android.views.BaseAppTheme
import com.mattmooneyham.base.android.constants.BrandColors

// The joke feature's accent is the brand MAGIC purple, giving the card
// its own identity next to the BRAND-blue chrome.
private val JokeAccent = Color(BrandColors.MAGIC)
private val RefreshTint = Color(BrandColors.BRAND)

/**
 * Demo REST card: an icon-tile header matching the SettingsRow language,
 * the API-fetched joke with a quote-bar punchline, pulsing skeleton
 * lines while the first joke loads, and an inline error row.
 */
@Composable
fun JokeCard(
    setup: String?,
    punchline: String?,
    isLoading: Boolean,
    errorMessage: String?,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    // Non-null makes the whole card tappable: the navigation
    // affordance HomePage wires to the router. The testTag is the
    // instrumented suite's stable handle for that tap.
    onOpenDetails: (() -> Unit)? = null,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(24.dp),
        modifier = modifier
            .fillMaxWidth()
            .testTag("JokeCard")
            // Clip BEFORE clickable so the ripple honors the shape.
            .clip(RoundedCornerShape(24.dp))
            .then(
                if (onOpenDetails != null) {
                    Modifier.clickable { onOpenDetails() }
                } else {
                    Modifier
                },
            ),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(34.dp)
                        .background(
                            color = JokeAccent.copy(alpha = 0.12f),
                            shape = RoundedCornerShape(10.dp),
                        ),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Face,
                        contentDescription = null,
                        tint = JokeAccent,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Random joke",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "Fresh from the Joke API",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // Fixed 40dp slot: the spinner replaces the refresh icon
                // inside the same circle, so nothing shifts while loading.
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(40.dp)
                        .background(
                            color = RefreshTint.copy(alpha = 0.12f),
                            shape = CircleShape,
                        ),
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            color = RefreshTint,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(18.dp),
                        )
                    } else {
                        IconButton(onClick = onRefresh) {
                            Icon(
                                imageVector = Icons.Filled.Refresh,
                                contentDescription = "Refresh joke",
                                tint = RefreshTint,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            if (setup != null && punchline != null) {
                // Keyed on the setup so a fresh joke plays the same swap
                // animation the session card uses.
                AnimatedContent(
                    targetState = setup to punchline,
                    transitionSpec = AppAnimations.contentSwapTransform(),
                    label = "jokeContent",
                ) { (currentSetup, currentPunchline) ->
                    Column {
                        Text(
                            text = currentSetup,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        // Punchline as a quote: a slim accent bar ties it
                        // to the card's MAGIC identity.
                        Row(modifier = Modifier.height(IntrinsicSize.Min)) {
                            Box(
                                modifier = Modifier
                                    .width(3.dp)
                                    .fillMaxHeight()
                                    .background(
                                        color = JokeAccent.copy(alpha = 0.5f),
                                        shape = RoundedCornerShape(2.dp),
                                    ),
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = currentPunchline,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme
                                    .onSurfaceVariant,
                            )
                        }
                    }
                }
            } else {
                // Skeleton placeholder while the first joke loads.
                val pulseAlpha = skeletonPulseAlpha()
                Column(modifier = Modifier.alpha(pulseAlpha)) {
                    SkeletonLine(widthFraction = 0.9f)
                    Spacer(modifier = Modifier.height(10.dp))
                    SkeletonLine(widthFraction = 0.55f)
                }
            }

            if (errorMessage != null) {
                Spacer(modifier = Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = errorMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun SkeletonLine(widthFraction: Float) {
    Box(
        modifier = Modifier
            .fillMaxWidth(widthFraction)
            .height(14.dp)
            .background(
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(7.dp),
            ),
    )
}

@Preview(showBackground = true)
@Composable
private fun JokeCardPreview() {
    BaseAppTheme {
        JokeCard(
            setup = "Why did the developer go broke?",
            punchline = "Because they used up all their cache.",
            isLoading = false,
            errorMessage = null,
            onRefresh = {},
            modifier = Modifier.padding(20.dp),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun JokeCardLoadingAndErrorPreview() {
    BaseAppTheme {
        Column {
            JokeCard(
                setup = null,
                punchline = null,
                isLoading = true,
                errorMessage = null,
                onRefresh = {},
                modifier = Modifier.padding(20.dp),
            )
            JokeCard(
                setup = "Why did the developer go broke?",
                punchline = "Because they used up all their cache.",
                isLoading = false,
                errorMessage = "Couldn't load a joke. Check your connection.",
                onRefresh = {},
                modifier = Modifier.padding(horizontal = 20.dp),
            )
        }
    }
}
