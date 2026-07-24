import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The UI layer: views, viewmodels, navigation, theme. An Android
// library that depends ONLY on :core, so the compiler enforces that
// UI code composes manager interfaces and never reaches the :app
// edge (adapters, Hilt wiring, the composition root).
plugins {
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.ksp)
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_11
    }
}

dependencies {
    api(project(":core"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.materialIconsCore)
    implementation(libs.compose.uiToolingPreview)
    debugImplementation(libs.compose.uiTooling)

    // BackHandler and the Compose activity integration.
    implementation(libs.androidx.activity.compose)

    // Navigation state serializes through the JSON saver.
    implementation(libs.kotlinx.serialization.json)

    // @HiltViewModel viewmodels; the aggregating plugin runs in :app.
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // JVM unit tests: the router is a plain class over snapshot state.
    testImplementation(libs.junit)
}

android {
    namespace = "com.mattmooneyham.base.android.ui"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}
