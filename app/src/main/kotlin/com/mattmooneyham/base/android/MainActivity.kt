package com.mattmooneyham.base.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import com.mattmooneyham.base.android.viewModels.MainViewModel
import com.mattmooneyham.base.android.viewModels.SettingsViewModel
import com.mattmooneyham.base.android.views.BaseAppTheme
import com.mattmooneyham.base.android.views.LocalEventManager
import com.mattmooneyham.base.android.views.NavigationBar
import com.mattmooneyham.base.android.managers.eventManager.EventManager
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

// The activity's shell preview lives with the scaffold in :ui
// (NavigationBarScaffoldPreview); this file holds no composables of
// its own.
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
        // launch marks the welcome seen, so Home settles on "Welcome
        // back!" every start; "First launch" shows after clearing the
        // flag from Settings, until the next launch writes it again.
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
