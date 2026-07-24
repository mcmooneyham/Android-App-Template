package com.mattmooneyham.base.android.viewModels

import com.mattmooneyham.base.android.managers.dataStoreManager.HasSeenWelcomeChanged
import com.mattmooneyham.base.android.managers.JokeStateChanged
import com.mattmooneyham.base.android.managers.JokeStatus
import com.mattmooneyham.base.android.testkit.TestAppContext
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * MainViewModel against the REAL component (fakes at the boundaries
 * only): its write actions go through the real managers and surface
 * as events. viewModelScope rides the harness's Main override.
 */
class MainViewModelSpec {

    private var testContext: TestAppContext? = null

    private fun startApp(): TestAppContext =
        TestAppContext().also { testContext = it }

    @After
    fun tearDown() {
        testContext?.close()
    }

    private fun buildViewModel(app: TestAppContext) = MainViewModel(
        dataStoreManager = app.component.dataStoreManager,
        logManager = app.component.logManager,
        jokeManager = app.component.jokeManager,
    )

    @Test
    fun `markWelcomeSeen persists the flag through the real manager`() =
        runBlocking<Unit> {
            val app = startApp()
            val viewModel = buildViewModel(app)
            val recorder = app.newRecorder().record(HasSeenWelcomeChanged)
            assertEquals(
                false,
                recorder.expectState(HasSeenWelcomeChanged),
            )

            viewModel.markWelcomeSeen()

            assertEquals(
                true,
                recorder.expectState(HasSeenWelcomeChanged),
            )
        }

    @Test
    fun `refreshJoke delegates to the joke manager`() =
        runBlocking<Unit> {
            val app = startApp()
            val viewModel = buildViewModel(app)
            val recorder = app.newRecorder().record(JokeStateChanged)
            // Drain the startup fetch first.
            recorder.expectStateMatching(JokeStateChanged) { state ->
                state.status == JokeStatus.SUCCESS
            }

            viewModel.refreshJoke()

            assertEquals(
                JokeStatus.REFRESHING,
                recorder.expectState(JokeStateChanged).status,
            )
            assertEquals(
                JokeStatus.SUCCESS,
                recorder.expectState(JokeStateChanged).status,
            )
            assertEquals(2, app.jokeApi.requestCount)
        }
}
