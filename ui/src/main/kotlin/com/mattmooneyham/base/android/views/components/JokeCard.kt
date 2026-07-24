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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.mattmooneyham.base.android.ui.R
import com.mattmooneyham.base.android.animations.AppAnimations
import com.mattmooneyham.base.android.animations.skeletonPulseAlpha
import com.mattmooneyham.base.android.designSystem.AppDimens
import com.mattmooneyham.base.android.designSystem.AppShapes
import com.mattmooneyham.base.android.designSystem.AppSpacing
import com.mattmooneyham.base.android.views.BaseAppTheme
import com.mattmooneyham.base.android.constants.BrandColors

// The joke feature's accent is the brand MAGIC purple, giving the card
// its own identity next to the BRAND-blue chrome.
private val JokeAccent = Color(BrandColors.MAGIC)

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
    // affordance the shell wires to the router through HomePage's
    // semantic onOpenJokeDetail lambda. The testTag is the
    // instrumented suite's stable handle for that tap.
    onOpenDetails: (() -> Unit)? = null,
) {
    val refreshTint = MaterialTheme.colorScheme.primary
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = AppShapes.card,
        modifier = modifier
            .fillMaxWidth()
            .testTag("JokeCard")
            // Clip BEFORE clickable so the ripple honors the shape.
            .clip(AppShapes.card)
            .then(
                if (onOpenDetails != null) {
                    Modifier.clickable { onOpenDetails() }
                } else {
                    Modifier
                },
            ),
    ) {
        Column(modifier = Modifier.padding(AppSpacing.cardPadding)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(AppDimens.iconTileSize)
                        .background(
                            color = JokeAccent.copy(alpha = 0.12f),
                            shape = AppShapes.iconTile,
                        ),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Face,
                        contentDescription = null,
                        tint = JokeAccent,
                        modifier = Modifier.size(AppDimens.iconMedium),
                    )
                }
                Spacer(modifier = Modifier.width(AppSpacing.md))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.joke_card_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = stringResource(R.string.joke_card_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // Fixed action slot: the spinner replaces the refresh icon
                // inside the same circle, so nothing shifts while loading.
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(AppDimens.actionSlotSize)
                        .background(
                            color = refreshTint.copy(alpha = 0.12f),
                            shape = CircleShape,
                        ),
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            color = refreshTint,
                            strokeWidth = AppDimens.spinnerStrokeWidth,
                            modifier = Modifier.size(AppDimens.iconMedium),
                        )
                    } else {
                        IconButton(onClick = onRefresh) {
                            Icon(
                                imageVector = Icons.Filled.Refresh,
                                contentDescription = stringResource(
                                    R.string
                                        .joke_card_refresh_content_description,
                                ),
                                tint = refreshTint,
                                modifier = Modifier
                                    .size(AppDimens.iconLarge),
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(AppSpacing.contentGapLarge))

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
                        Spacer(
                            modifier = Modifier
                                .height(AppSpacing.contentGapMedium),
                        )
                        // Punchline as a quote: a slim accent bar ties it
                        // to the card's MAGIC identity.
                        Row(modifier = Modifier.height(IntrinsicSize.Min)) {
                            Box(
                                modifier = Modifier
                                    .width(AppDimens.accentBarWidth)
                                    .fillMaxHeight()
                                    .background(
                                        color = JokeAccent.copy(alpha = 0.5f),
                                        shape = AppShapes.accentBar,
                                    ),
                            )
                            Spacer(
                                modifier = Modifier
                                    .width(AppSpacing.contentGapMedium),
                            )
                            Text(
                                text = currentPunchline,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme
                                    .onSurfaceVariant,
                            )
                        }
                    }
                }
            } else if (errorMessage == null || isLoading) {
                // Skeleton placeholder while a joke is genuinely on
                // its way. A terminal failure with nothing cached
                // shows only the error row below: a pulsing skeleton
                // under a dead fetch would promise progress that is
                // not happening (and keep an infinite animation alive
                // in a settled screen).
                val pulseAlpha = skeletonPulseAlpha()
                Column(modifier = Modifier.alpha(pulseAlpha)) {
                    SkeletonLine(widthFraction = 0.9f)
                    Spacer(
                        modifier = Modifier
                            .height(AppSpacing.contentGapMedium),
                    )
                    SkeletonLine(widthFraction = 0.55f)
                }
            }

            if (errorMessage != null) {
                Spacer(modifier = Modifier.height(AppSpacing.md))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(AppDimens.iconSmall),
                    )
                    Spacer(
                        modifier = Modifier
                            .width(AppSpacing.contentGapSmall),
                    )
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

/** One pulsing placeholder line; shared by the loading states. */
@Composable
internal fun SkeletonLine(widthFraction: Float) {
    Box(
        modifier = Modifier
            .fillMaxWidth(widthFraction)
            .height(AppDimens.skeletonLineHeight)
            .background(
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = AppShapes.skeletonLine,
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
