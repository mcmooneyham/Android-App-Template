package com.mattmooneyham.base.android.views

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import com.mattmooneyham.base.android.ui.R
import com.mattmooneyham.base.android.animations.skeletonPulseAlpha
import com.mattmooneyham.base.android.designSystem.AppSpacing
import com.mattmooneyham.base.android.managers.jokeManager.JokeDetailChanged
import com.mattmooneyham.base.android.managers.jokeManager.JokeStatus
import com.mattmooneyham.base.android.views.components.SectionHeader
import com.mattmooneyham.base.android.views.components.SettingsGroupCard
import com.mattmooneyham.base.android.views.components.SkeletonLine

/**
 * The pushed-screen exemplar (the iOS sibling's JokeDetailPage): a
 * destination with an argument, reached only through the router,
 * rendered from bus state like every other view. The screen rides the
 * KEYED detail event: it asks the manager to ensure ITS id is loaded
 * (a cache hit answers instantly, anything else fetches by id) and
 * renders only states carrying that id, so a cold-start deep link
 * works and latest-wins replay can never put the wrong joke here.
 */
@Composable
fun JokeDetailPage(
    jokeId: Int,
    onLoadJokeDetail: (Int) -> Unit,
    onBack: () -> Unit,
) {
    // Re-issued per id and again after process death; the manager
    // coalesces duplicates, so over-asking is harmless.
    LaunchedEffect(jokeId) { onLoadJokeDetail(jokeId) }
    val detailState by eventStateOrNull(key = JokeDetailChanged)
    val stateForThisId = detailState?.takeIf { detail ->
        detail.jokeId == jokeId
    }
    JokeDetailContent(
        jokeId = jokeId,
        status = stateForThisId?.status,
        setup = stateForThisId?.joke?.setup,
        punchline = stateForThisId?.joke?.punchline,
        onRetry = { onLoadJokeDetail(jokeId) },
        onBack = onBack,
    )
}

@Composable
private fun JokeDetailContent(
    jokeId: Int,
    status: JokeStatus?,
    setup: String?,
    punchline: String?,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = AppSpacing.screenEdge),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = AppSpacing.xs),
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(
                        R.string.back_content_description,
                    ),
                    tint = MaterialTheme.colorScheme.onBackground,
                )
            }
            Spacer(modifier = Modifier.width(AppSpacing.xs))
            Text(
                text = stringResource(R.string.joke_detail_title),
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
            )
        }

        SectionHeader(
            title = stringResource(
                R.string.joke_detail_section_title,
                jokeId,
            ),
        )
        SettingsGroupCard {
            when {
                setup != null && punchline != null -> {
                    Column(
                        modifier = Modifier.padding(AppSpacing.cardPadding),
                    ) {
                        Text(
                            text = setup,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(
                            modifier = Modifier
                                .height(AppSpacing.contentGapMedium),
                        )
                        Text(
                            text = punchline,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme
                                .onSurfaceVariant,
                        )
                    }
                }
                status == JokeStatus.FAILED -> {
                    Column(
                        modifier = Modifier.padding(AppSpacing.cardPadding),
                    ) {
                        Text(
                            text = stringResource(
                                R.string.joke_detail_unavailable,
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                        TextButton(onClick = onRetry) {
                            Text(
                                text = stringResource(
                                    R.string.joke_detail_retry,
                                ),
                            )
                        }
                    }
                }
                else -> {
                    // null (request not answered yet) or REFRESHING:
                    // the fetch-by-id is on its way.
                    val pulseAlpha = skeletonPulseAlpha()
                    Column(
                        modifier = Modifier
                            .padding(AppSpacing.cardPadding)
                            .alpha(pulseAlpha),
                    ) {
                        SkeletonLine(widthFraction = 0.9f)
                        Spacer(
                            modifier = Modifier
                                .height(AppSpacing.contentGapMedium),
                        )
                        SkeletonLine(widthFraction = 0.55f)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(AppSpacing.pageBottom))
    }
}

@Preview(showBackground = true)
@Composable
private fun JokeDetailContentPreview() {
    BaseAppTheme {
        JokeDetailContent(
            jokeId = 42,
            status = JokeStatus.SUCCESS,
            setup = "Why did the developer go broke?",
            punchline = "Because they used up all their cache.",
            onRetry = {},
            onBack = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun JokeDetailContentLoadingPreview() {
    BaseAppTheme {
        JokeDetailContent(
            jokeId = 42,
            status = JokeStatus.REFRESHING,
            setup = null,
            punchline = null,
            onRetry = {},
            onBack = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun JokeDetailContentFailedPreview() {
    BaseAppTheme {
        JokeDetailContent(
            jokeId = 42,
            status = JokeStatus.FAILED,
            setup = null,
            punchline = null,
            onRetry = {},
            onBack = {},
        )
    }
}
