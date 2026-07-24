import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The app core: managers, the event bus, ports, and the API layer.
// A Kotlin JVM module ON PURPOSE: android.* / androidx.* are not on
// the classpath, so the compiler (not review) enforces that the core
// stays platform-free; Context reaches this code only through the
// port interfaces the :app edge implements. This module is also the
// KMP commonMain candidate (see ARCHITECTURE-SCALING.md).
plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinSerialization)
    // Ships the testkit (fakes, recorder, awaits) to the other
    // modules' test source sets via testFixtures(project(":core")).
    `java-test-fixtures`
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_11
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

dependencies {
    // api: these types appear in the core's public surface
    // (StateFlow/Flow on managers, HttpClient on ApiClient, DataStore
    // in constructor seams), so consumers compile against them.
    api(libs.kotlinx.coroutines.core)
    api(libs.ktor.client.core)
    api(libs.androidx.datastore.core)
    api(libs.androidx.datastore.preferences.core)

    implementation(libs.kotlinx.datetime)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okio)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.contentNegotiation)
    implementation(libs.ktor.serialization.kotlinxJson)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(testFixtures(project(":core")))

    // The testkit's FakeJokeApi exposes Ktor MockEngine types in its
    // handler signature, so consumers need them on their classpath.
    testFixturesApi(libs.ktor.client.mock)
    testFixturesImplementation(libs.kotlinx.serialization.json)
    testFixturesImplementation(libs.kotlinx.coroutines.test)
}
