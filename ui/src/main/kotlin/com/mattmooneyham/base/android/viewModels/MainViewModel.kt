package com.mattmooneyham.base.android.viewModels

import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mattmooneyham.base.android.managers.dataStoreManager.DataStoreManager
import com.mattmooneyham.base.android.managers.jokeManager.JokeManager
import com.mattmooneyham.base.android.managers.logManager.LogManager
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

    /** Raw subject for the hero headline, e.g. "Android 36"; the view
     * wraps it in the localized hello_headline resource. */
    val greetingSubject: String = "Android ${Build.VERSION.SDK_INT}"

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

    /** Ensures the joke with [jokeId] is loaded for a detail screen;
     * the keyed result arrives as JokeDetailChanged events. */
    fun loadJokeDetail(jokeId: Int) {
        jokeManager.loadJokeDetail(jokeId)
    }

}
