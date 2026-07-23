# Android-App-Template

A standalone, production-shaped Android app template: native Jetpack
Compose UI on top of an event-driven manager layer. Everything lives in
one Gradle module and one package tree, so there is nothing to publish,
version, or wire up before the first build.

## Architecture

Event-driven, managers publish and everything else listens:

- Business-logic layer, side by side with the UI packages:
  - `di/`: `AppConfig` (the composition root's single input: file
    paths, log level, API base URL, and an HTTP-client factory seam for
    tests) and `AppComponent` (manual constructor injection with a
    reverse-order `close()`); `BaseApplication.onCreate` constructs the
    single component before anything else runs, and `AppModule` is a
    thin Hilt adapter exposing the component's members to `@Inject`
    sites.
  - `managers/`: `EventManager` (replay-1 event bus with weak, owner-based
    listeners plus Compose helpers in `EventManagerCompose.kt`),
    `LogManager` (Logcat + log file with full call-site context),
    `NetworkManager` (validated connectivity), `DataStoreManager`
    (Preferences DataStore), `JokeManager` (demo REST feature and the
    reference pattern for new features).
  - `constants/`: `BrandColors` semantic tokens and `LogLevel`. Event
    contracts are typed `StateKey`/`SignalKey` objects declared beside
    the manager that publishes them, with namespaced event names such
    as `"joke.StateChanged"`.
  - `api/`: Ktor `ApiClient` (base URL in `AppConfig.apiBaseUrl`) with
    JSON content negotiation; DTOs live beside it.
- UI layer:
  - `views/` + `views/components/`: pages and reusable components; every
    composable has a `@Preview`, backed by stateless content composables.
  - `viewModels/`: thin Hilt viewmodels (writes and actions only; views
    observe events directly via `eventState`/`eventStateOrNull`).
  - `animations/AppAnimations.kt`: ALL motion definitions live here.

## Demo feature pattern

`JokeManager` shows how to add a feature: fetch in the manager, publish
ONE state event (`JokeStateChanged`, a `StateKey<JokeState>` declared
beside the manager), let views listen, and let the viewmodel forward
user actions. Copy that shape for real features.

## Stack

Kotlin 2.4.10, AGP 9.0.1 (built-in Kotlin; do NOT apply
`org.jetbrains.kotlin.android`), Gradle 9.1.0, compileSdk 36, minSdk 32,
Hilt 2.60.1 + KSP, Compose BOM 2026.06.01, Ktor 3.5.1 (OkHttp engine),
DataStore 1.2.1, kotlinx-serialization, kotlinx-datetime, okio.

## Build

```
./gradlew :app:assembleDebug
```

No external checkouts required; this repo is self-contained.

## Tests

JVM unit tests (architecture guards today; manager and event-contract
suites are on the way):

```
./gradlew :app:testDebugUnitTest
```

Instrumented flow tests drive the REAL app on a connected device or
emulator (real Hilt graph, real managers, real DataStore; no mocks
anywhere):

```
./gradlew :app:connectedDebugAndroidTest
```
