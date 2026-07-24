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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.mattmooneyham.base.android.managers.JokeStateChanged
import com.mattmooneyham.base.android.views.components.SectionHeader
import com.mattmooneyham.base.android.views.components.SettingsGroupCard

/**
 * The pushed-screen exemplar (the iOS sibling's JokeDetailPage): a
 * destination with an argument, reached only through the router,
 * rendered from bus state like every other view. The bus is
 * latest-wins, so the argument's joke may have been superseded; the
 * view says so honestly instead of showing the wrong joke.
 */
@Composable
fun JokeDetailPage(
    jokeId: Int,
    onBack: () -> Unit,
) {
    val jokeState by eventStateOrNull(key = JokeStateChanged)
    val joke = jokeState?.joke?.takeIf { loaded -> loaded.id == jokeId }
    JokeDetailContent(
        jokeId = jokeId,
        setup = joke?.setup,
        punchline = joke?.punchline,
        onBack = onBack,
    )
}

@Composable
private fun JokeDetailContent(
    jokeId: Int,
    setup: String?,
    punchline: String?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = 4.dp),
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = MaterialTheme.colorScheme.onBackground,
                )
            }
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = "Joke details",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
            )
        }

        SectionHeader(title = "Joke #$jokeId")
        SettingsGroupCard {
            if (setup != null && punchline != null) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        text = setup,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = punchline,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                Text(
                    text = "This joke is no longer loaded.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(20.dp),
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Preview(showBackground = true)
@Composable
private fun JokeDetailContentPreview() {
    BaseAppTheme {
        JokeDetailContent(
            jokeId = 42,
            setup = "Why did the developer go broke?",
            punchline = "Because they used up all their cache.",
            onBack = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun JokeDetailContentUnloadedPreview() {
    BaseAppTheme {
        JokeDetailContent(
            jokeId = 42,
            setup = null,
            punchline = null,
            onBack = {},
        )
    }
}
