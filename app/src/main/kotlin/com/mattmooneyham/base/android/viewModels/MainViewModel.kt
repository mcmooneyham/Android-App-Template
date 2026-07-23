package com.mattmooneyham.base.android.viewModels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mattmooneyham.base.android.Greeting
import com.mattmooneyham.base.android.managers.DataStoreManager
import com.mattmooneyham.base.android.managers.JokeManager
import com.mattmooneyham.base.android.managers.LogManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Holds the home screen's non-trivial state and write actions. Simple
 * event-backed values (connectivity, welcome flag, joke) are observed by
 * the views directly via LocalEventManager/eventState; managers are
 * called here only for writes and actions.
 */
@HiltViewModel
class MainViewModel @Inject constructor(
    private val dataStoreManager: DataStoreManager,
    private val logManager: LogManager,
    private val jokeManager: JokeManager,
) : ViewModel() {

    /** Sample output from the shared business logic. */
    val greeting: String = Greeting().greet()

    init {
        logManager.info("MainViewModel created")
    }

    fun markWelcomeSeen() {
        viewModelScope.launch {
            dataStoreManager.setHasSeenWelcome(true)
        }
    }

    /** Asks the app core for a fresh joke; the result arrives as events. */
    fun refreshJoke() {
        jokeManager.refreshJoke()
    }

}
