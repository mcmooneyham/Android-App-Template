package com.mattmooneyham.base.android.di

/**
 * The one contract the Hilt adapter needs from the Application:
 * whoever hosts the process's [AppComponent]. BaseApplication
 * implements it in production. Depending on this abstraction instead
 * of the concrete Application class is what keeps AppModule working
 * under Hilt's instrumentation tooling: a future @CustomTestApplication
 * base can implement it too (HiltTestApplication replaces the
 * production Application in @HiltAndroidTest runs, so a cast to
 * BaseApplication would throw there).
 */
interface AppComponentHost {
    val appComponent: AppComponent
}
