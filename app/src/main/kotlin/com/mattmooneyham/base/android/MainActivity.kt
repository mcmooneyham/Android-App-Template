package com.mattmooneyham.base.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.tooling.preview.Preview
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

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // Demonstrates the DataStore-backed preference round trip:
        // the first cold start renders "First launch", later ones
        // "Welcome back!" (until cleared from Settings).
        mainViewModel.markWelcomeSeen()

        setContent {
            CompositionLocalProvider(LocalEventManager provides eventManager) {
                BaseAppTheme {
                    NavigationBar(
                        mainViewModel = mainViewModel,
                        settingsViewModel = settingsViewModel,
                    )
                }
            }
        }
    }
}

// Previews the activity's shell (tab scaffold) with placeholder content;
// the real pages need Hilt viewmodels and have their own content previews.
@Preview(showBackground = true)
@Composable
private fun MainActivityContentPreview() {
    BaseAppTheme {
        NavigationBarScaffold(
            selectedTabIndex = 0,
            onTabSelected = {},
        ) {
            Text(text = "Home preview")
        }
    }
}
