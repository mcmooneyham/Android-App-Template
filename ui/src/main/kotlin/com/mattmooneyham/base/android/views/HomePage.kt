package com.mattmooneyham.base.android.views

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import com.mattmooneyham.base.android.ui.R
import com.mattmooneyham.base.android.viewModels.MainViewModel
import com.mattmooneyham.base.android.animations.AppAnimations
import com.mattmooneyham.base.android.designSystem.AppSpacing
import com.mattmooneyham.base.android.views.components.HeroCard
import com.mattmooneyham.base.android.views.components.JokeCard
import com.mattmooneyham.base.android.views.components.SettingsGroupCard
import com.mattmooneyham.base.android.views.components.SettingsRow
import com.mattmooneyham.base.android.api.FailureKind
import com.mattmooneyham.base.android.managers.dataStoreManager.HasSeenWelcomeChanged
import com.mattmooneyham.base.android.managers.jokeManager.JokeStateChanged
import com.mattmooneyham.base.android.managers.jokeManager.JokeStatus
import com.mattmooneyham.base.android.managers.connectivityManager.NetworkConnectivityChanged

/**
 * Home tab. Simple event-backed values are observed straight from the
 * event bus via the Compose-native [eventState] helpers; the
 * viewmodel supplies only the greeting subject and write actions (the
 * localized greeting itself is built here from resources). Rendering
 * lives in the previewable [HomePageContent]. Navigation stays a
 * semantic lambda ([onOpenJokeDetail]): the shell wires it to the
 * router, so pages never see navigation machinery.
 */
@Composable
fun HomePage(
    mainViewModel: MainViewModel,
    onOpenJokeDetail: (jokeId: Int) -> Unit,
) {
    val isOnline by eventState(
        key = NetworkConnectivityChanged,
        initialValue = false,
    )
    val hasSeenWelcome by eventStateOrNull(key = HasSeenWelcomeChanged)
    // The joke arrives purely through the event bus: JokeManager fetches
    // and publishes; this view only listens (and asks for refreshes).
    val jokeState by eventStateOrNull(key = JokeStateChanged)

    // Entrance animation: starts hidden, animates in on first
    // composition.
    val contentVisibility = remember {
        MutableTransitionState(false).apply { targetState = true }
    }
    AnimatedVisibility(
        visibleState = contentVisibility,
        enter = AppAnimations.contentEnterTransition,
    ) {
        HomePageContent(
            greeting = stringResource(
                R.string.hello_headline,
                mainViewModel.greetingSubject,
            ),
            isOnline = isOnline,
            hasSeenWelcome = hasSeenWelcome,
            jokeSetup = jokeState?.joke?.setup,
            jokePunchline = jokeState?.joke?.punchline,
            isJokeRefreshing = jokeState?.status == JokeStatus.REFRESHING,
            // Copy is chosen by the typed failure kind, not by parsing
            // exception text; the technical detail stays in the logs.
            jokeErrorMessage = jokeState?.failure?.let { failure ->
                when (failure.kind) {
                    FailureKind.NETWORK ->
                        stringResource(R.string.joke_error_network)
                    FailureKind.HTTP ->
                        stringResource(R.string.joke_error_http)
                    FailureKind.DECODE ->
                        stringResource(R.string.joke_error_decode)
                    FailureKind.TIMEOUT ->
                        stringResource(R.string.joke_error_timeout)
                    FailureKind.UNKNOWN ->
                        stringResource(R.string.joke_error_unknown)
                }
            },
            onRefreshJoke = mainViewModel::refreshJoke,
            // Only a loaded joke can be opened; null hides the tap.
            onOpenJokeDetail = jokeState?.joke?.id?.let { jokeId ->
                { onOpenJokeDetail(jokeId) }
            },
        )
    }
}

@Composable
private fun HomePageContent(
    greeting: String,
    isOnline: Boolean,
    hasSeenWelcome: Boolean?,
    jokeSetup: String?,
    jokePunchline: String?,
    isJokeRefreshing: Boolean,
    jokeErrorMessage: String?,
    onRefreshJoke: () -> Unit,
    onOpenJokeDetail: (() -> Unit)?,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(AppSpacing.sectionGap),
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = AppSpacing.screenEdge),
    ) {
        Text(
            text = stringResource(R.string.home_title),
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(
                top = AppSpacing.md,
                bottom = AppSpacing.xs,
            ),
        )

        HeroCard(
            label = stringResource(R.string.home_hero_label),
            headline = greeting,
            isOnline = isOnline,
        )

        AnimatedContent(
            targetState = when (hasSeenWelcome) {
                null -> stringResource(R.string.session_loading)
                true -> stringResource(R.string.session_welcome_back)
                false -> stringResource(R.string.session_first_launch)
            },
            transitionSpec = AppAnimations.contentSwapTransform(),
            label = "sessionStatus",
        ) { sessionStatusText ->
            SettingsGroupCard {
                SettingsRow(
                    icon = Icons.Filled.Person,
                    title = stringResource(R.string.session_row_title),
                    supportingText = sessionStatusText,
                )
            }
        }

        JokeCard(
            setup = jokeSetup,
            punchline = jokePunchline,
            isLoading = isJokeRefreshing,
            errorMessage = jokeErrorMessage,
            onRefresh = onRefreshJoke,
            onOpenDetails = onOpenJokeDetail,
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun HomePageContentPreview() {
    BaseAppTheme {
        HomePageContent(
            greeting = "Hello, Android 36!",
            isOnline = true,
            hasSeenWelcome = true,
            jokeSetup = "Why did the developer go broke?",
            jokePunchline = "Because they used up all their cache.",
            isJokeRefreshing = false,
            jokeErrorMessage = null,
            onRefreshJoke = {},
            onOpenJokeDetail = {},
        )
    }
}
