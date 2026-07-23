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
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.materialIconsCore)
    implementation(libs.compose.uiToolingPreview)
    debugImplementation(libs.compose.uiTooling)

    implementation(libs.androidx.activity.compose)

    // Manager/API layer (managers, api, constants packages).
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.datetime)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.okio)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.contentNegotiation)
    implementation(libs.ktor.serialization.kotlinxJson)

    // Hilt injects the managers; their bindings live in di/AppModule.
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // JVM unit tests (app/src/test): the architecture guard plus the
    // manager, event-contract, and choreography specs, all running a
    // real AppComponent with boundary fakes (see testkit/). The Ktor
    // MockEngine backs AppConfig.httpClientFactory.
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.client.mock)

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
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
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
