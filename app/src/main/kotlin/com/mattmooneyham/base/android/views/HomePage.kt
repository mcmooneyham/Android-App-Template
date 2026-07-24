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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.mattmooneyham.base.android.viewModels.MainViewModel
import com.mattmooneyham.base.android.animations.AppAnimations
import com.mattmooneyham.base.android.views.components.HeroCard
import com.mattmooneyham.base.android.views.components.JokeCard
import com.mattmooneyham.base.android.views.components.SettingsGroupCard
import com.mattmooneyham.base.android.views.components.SettingsRow
import com.mattmooneyham.base.android.api.FailureKind
import com.mattmooneyham.base.android.managers.dataStoreManager.HasSeenWelcomeChanged
import com.mattmooneyham.base.android.managers.JokeStateChanged
import com.mattmooneyham.base.android.managers.JokeStatus
import com.mattmooneyham.base.android.managers.connectivityManager.NetworkConnectivityChanged
import com.mattmooneyham.base.android.managers.eventManager.eventState
import com.mattmooneyham.base.android.managers.eventManager.eventStateOrNull

/**
 * Home tab. Simple event-backed values are observed straight from the
 * event bus via the Compose-native [eventState] helpers; the
 * viewmodel supplies only the greeting and write actions. Rendering
 * lives in the previewable [HomePageContent].
 */
@Composable
fun HomePage(mainViewModel: MainViewModel) {
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
            greeting = mainViewModel.greeting,
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
                        "Couldn't load a joke. Check your connection."
                    FailureKind.HTTP ->
                        "The joke service had a problem. Try again."
                    FailureKind.DECODE ->
                        "The joke service sent an unexpected response."
                    FailureKind.UNKNOWN ->
                        "Couldn't load a joke. Try again."
                }
            },
            onRefreshJoke = mainViewModel::refreshJoke,
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
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        Text(
            text = "Home",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
        )

        HeroCard(
            label = "App core",
            headline = greeting,
            isOnline = isOnline,
        )

        AnimatedContent(
            targetState = when (hasSeenWelcome) {
                null -> "Loading preferences..."
                true -> "Welcome back!"
                false -> "First launch"
            },
            transitionSpec = AppAnimations.contentSwapTransform(),
            label = "sessionStatus",
        ) { sessionStatusText ->
            SettingsGroupCard {
                SettingsRow(
                    icon = Icons.Filled.Person,
                    title = "Session",
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
        )
    }
}
