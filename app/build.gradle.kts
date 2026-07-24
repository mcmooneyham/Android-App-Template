import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// AGP 9 has built-in Kotlin support: org.jetbrains.kotlin.android must NOT be
// applied. Only the Kotlin compiler plugins (compose, serialization) are
// applied separately.
plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_11
    }
}

dependencies {
    // The layered modules: :core (managers, ports, api; Kotlin JVM)
    // and :ui (views, viewmodels, navigation). This module holds the
    // composition root, the platform adapters, and the app shell.
    implementation(project(":core"))
    implementation(project(":ui"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.androidx.activity.compose)
    // FileProvider for the log-export share (declared explicitly:
    // relying on a transitive edge for a manifest-declared class is
    // fragile).
    implementation(libs.androidx.core)
    // AppComponent builds the shared Json for the HTTP client factory.
    implementation(libs.kotlinx.serialization.json)

    // Hilt exposes the component's members to @Inject sites; the
    // bindings live in di/AppModule.
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // JVM unit tests (app/src/test): the architecture guards plus the
    // component-level specs, all running a real AppComponent with the
    // boundary fakes shipped by :core's testFixtures. The Ktor
    // MockEngine backs AppConfig.httpClientFactory.
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.ktor.client.contentNegotiation)
    testImplementation(libs.ktor.serialization.kotlinxJson)
    testImplementation(testFixtures(project(":core")))

    // Instrumented flow tests drive the REAL app (real Hilt, real
    // managers, real DataStore); no test doubles anywhere.
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.uiTestJunit4)
    androidTestImplementation(libs.androidx.test.runner)
    debugImplementation(libs.compose.uiTestManifest)
}

android {
    namespace = "com.mattmooneyham.base.android"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.mattmooneyham.base.android"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "1.0"

        // Build stamp shown in Settings > About: epoch SECONDS (10 digits).
        // Kept as a BuildConfig field rather than versionCode (an Int) so
        // the stamp format stays portable. Captured at configuration time:
        // with the configuration cache on, it refreshes when the cache
        // invalidates.
        buildConfigField(
            "long",
            "BUILD_TIMESTAMP_SECONDS",
            "${System.currentTimeMillis() / 1000}L",
        )
    }
    buildTypes {
        release {
            // R8 on: the release build ships minified from day one so
            // keep-rule gaps surface in CI's assembleRelease, never in
            // a derived app's first release week. The app-specific
            // rules live in proguard-rules.pro (call-site attribution
            // and stack-trace attributes); library rules ship inside
            // the libraries themselves.
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    lint {
        // lintVitalRelease already gates release builds by default;
        // CI additionally runs :app:lintDebug so the full analysis
        // fails the pipeline, not just the vital subset.
        abortOnError = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}
