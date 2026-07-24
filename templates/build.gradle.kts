import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Living documentation. This module compiles against :core and :ui
// and runs its own spec on every CI run (and any root build), so the
// exemplars can never rot, but NOTHING depends on it: it adds zero
// bytes to the APK. It is also deliberately absent from the guard
// tests' GUARDED_MODULES, so template keys and flags never pollute
// the real registries.
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
    implementation(project(":core"))
    implementation(project(":ui"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.materialIconsCore)
    implementation(libs.compose.uiToolingPreview)
    debugImplementation(libs.compose.uiTooling)

    implementation(libs.kotlinx.serialization.json)

    // The viewmodel exemplar carries the real @HiltViewModel shape.
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // The spec exemplar runs green in CI like any other suite.
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(testFixtures(project(":core")))

    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.uiTestJunit4)
    androidTestImplementation(libs.androidx.test.runner)
    debugImplementation(libs.compose.uiTestManifest)
}

android {
    namespace = "com.mattmooneyham.base.android.templates"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
        testInstrumentationRunner =
            "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}
