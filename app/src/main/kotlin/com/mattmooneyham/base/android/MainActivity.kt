package com.mattmooneyham.base.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.tooling.preview.Preview
import com.mattmooneyham.base.android.navigation.AppTab
import com.mattmooneyham.base.android.viewModels.MainViewModel
import com.mattmooneyham.base.android.viewModels.SettingsViewModel
import com.mattmooneyham.base.android.views.BaseAppTheme
import com.mattmooneyham.base.android.views.NavigationBar
import com.mattmooneyham.base.android.views.NavigationBarScaffold
import com.mattmooneyham.base.android.managers.eventManager.EventManager
import com.mattmooneyham.base.android.managers.eventManager.LocalEventManager
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    // Provided to the whole composition so views can listen to app core
    // events directly (see LocalEventManager/eventState).
    @Inject
    lateinit var eventManager: EventManager

    private val mainViewModel: MainViewModel by viewModels()
    private val settingsViewModel: SettingsViewModel by viewModels()

    // The latest not-yet-routed deep link. The activity only moves
    // strings; all URL knowledge (scheme, hosts, argument parsing)
    // lives in AppRouter.handleDeepLink. The shell consumes the value
    // and calls back to clear it, so recompositions never re-route.
    private val pendingDeepLinkUrl = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // Demonstrates the DataStore-backed preference round trip:
        // the first cold start renders "First launch", later ones
        // "Welcome back!" (until cleared from Settings).
        mainViewModel.markWelcomeSeen()

        // Cold-start links ride the launching intent. On recreation
        // rememberSaveable restores the stacks and the intent was
        // already consumed, so it must not replace them again.
        if (savedInstanceState == null) {
            pendingDeepLinkUrl.value = intent?.dataString
        }

        setContent {
            CompositionLocalProvider(LocalEventManager provides eventManager) {
                BaseAppTheme {
                    NavigationBar(
                        mainViewModel = mainViewModel,
                        settingsViewModel = settingsViewModel,
                        pendingDeepLinkUrl = pendingDeepLinkUrl.value,
                        onDeepLinkConsumed = {
                            pendingDeepLinkUrl.value = null
                        },
                    )
                }
            }
        }
    }

    // Warm-start links: the activity is already up, and singleTop
    // routes the intent here instead of stacking a second activity.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        pendingDeepLinkUrl.value = intent.dataString
    }
}

// Previews the activity's shell (tab scaffold) with placeholder content;
// the real pages need Hilt viewmodels and have their own content previews.
@Preview(showBackground = true)
@Composable
private fun MainActivityContentPreview() {
    BaseAppTheme {
        NavigationBarScaffold(
            selectedTab = AppTab.HOME,
            onTabSelected = {},
        ) {
            Text(text = "Home preview")
        }
    }
}
