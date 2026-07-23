# Android-App-Template

A standalone, production-shaped Android app template: native Jetpack
Compose UI on top of an event-driven manager core. Everything lives in
one Gradle module and one package tree (readable in an afternoon), and
every mechanism is one a two-person team starting an app would actually
use. Designs the template deliberately defers live in
[ARCHITECTURE-SCALING.md](ARCHITECTURE-SCALING.md), each with an
explicit adoption threshold.

## Architecture

One process-scoped composition root wires a flat layer of peer managers
that communicate only through a typed event bus; the UI listens and
stays thin. As a component diagram in text:

```
BaseApplication ................. the Android edge (onCreate)
  builds filesDir, AndroidConnectivityMonitor, min log level
     |
     v  AppConfig (the composition root's single input value)
AppComponent (di/) .............. plain class, manual constructor
     |                            injection, close() in reverse order
     |-- eventManager ........... EventManager: the bus; OWNS every
     |        ^                   event stream (keys are identifiers)
     |        |  attachLogManager (the one sanctioned setter cycle)
     |-- logManager ............. Logcat + rotating log file
     |-- networkManager ......... validated connectivity, behind the
     |                            ConnectivityMonitor boundary
     |-- dataStoreManager ....... Preferences DataStore facade
     |-- apiClient .............. Ktor HTTP (httpClientFactory seam)
     |-- jokeManager ............ demo feature + the cross-manager
     |                            choreography exemplar
     v
AppModule (Hilt) ................ thin adapter exposing the component's
     |                            members to @Inject sites
     v
MainActivity .................... provides LocalEventManager
     |-- views/ ................. observe events directly via
     |                            eventState / eventStateOrNull
     |-- viewModels/ ............ thin: user actions and writes only
```

Managers are PEERS: there is no central coordinator, and no manager
holds a reference to another manager's internals. They publish typed
events on the bus and (rarely, deliberately) react to each other's
events. Data flows one way: managers publish, the UI listens,
viewmodels forward user actions back to managers.

### Composition root (`di/`)

- `AppConfig` gathers everything the component needs from the outside
  world into one value: the files directory (a typed `java.io.File`),
  the `ConnectivityMonitor` boundary, the minimum log level, the API
  base URL, an `httpClientFactory: (Json) -> HttpClient` seam (tests
  swap in a Ktor MockEngine), an injectable `Clock`, a `CrashReporter`
  seam (no-op by default), and the log-rotation cap. Every field with
  a production default can be overridden per test.
- `AppComponent` is a plain class: property initializers run top to
  bottom, so declaration order IS the construction order. It also
  installs a `Thread` default uncaught-exception handler that reports
  the fatal to the `CrashReporter`, logs it, drains the log queue to
  disk (`flushForCrash`), and then delegates to the previous handler.
  `close()` tears down in reverse construction order, cancelling the
  event bus LAST so teardown events still deliver.
- `BaseApplication.onCreate` is the only Android edge: it builds every
  platform-touching value (typed `File`, `AndroidConnectivityMonitor`,
  debug-vs-release log level from `BuildConfig.DEBUG`) and constructs
  exactly ONE `AppComponent` per process. Managers never see a
  `Context`, which is what lets JVM tests build a real component from
  fakes alone.
- `AppModule` is a thin Hilt adapter: every provider reads a member
  FROM the component; Hilt never constructs a manager itself.

Wiring conventions (also stated in the `AppComponent` KDoc, which is
the source of truth):

1. Construction order is bus, logging, then peer managers.
2. Managers receive only what they use, by constructor, within a
   budget of five parameters (a bus handle counts as one). A manager
   that needs more is doing too much: split it.
3. Never inject the component itself into a manager.
4. The one sanctioned setter cycle is
   `eventManager.attachLogManager(logManager)`, called in the
   component's init block. Add no others.

### The event contract

Event keys are plain named objects declared BESIDE the manager that
publishes them, in two flavors (`managers/EventKey.kt`):

- `StateKey<Payload>`: a replayed state event. The payload type is
  part of the key's TYPE, so a mistyped `trigger` or `listenTo` does
  not compile. The latest payload is cached and replayed to late
  subscribers (latest wins under bursts).
- `SignalKey`: a payloadless one-shot notification; never replayed.

Event names follow `"namespace.EventName"`: the lowercase publishing
manager's namespace, a dot, then a PascalCase description. The keys
declared today:

- `JokeStateChanged`: `StateKey<JokeState>`, `"joke.StateChanged"`,
  SESSION lifetime, declared beside `JokeManager`.
- `NetworkConnectivityChanged`: `StateKey<Boolean>`,
  `"network.ConnectivityChanged"`, APP lifetime (connectivity is a
  device fact, not user state), beside `NetworkManager`.
- `HasSeenWelcomeChanged`: `StateKey<Boolean>`,
  `"datastore.HasSeenWelcomeChanged"`, SESSION lifetime, beside
  `DataStoreManager`.
- `LogsCleared`: `SignalKey`, `"log.Cleared"`, beside `LogManager`.

Keys are PURE IDENTIFIERS; the streams live inside `EventManager`,
keyed by the key object. Rebuilding the manager (tests, component
rebuild) therefore drops every cached value instead of leaking state
through process-global objects. Each `StateKey` carries an
`EventLifetime` (`SESSION` by default, `APP` for device/process
facts): `eventManager.resetSessionReplayCaches()` clears the replay
caches of SESSION keys on logout or account switch, leaving APP keys
and all subscriptions untouched.

The contract every publisher and subscriber can rely on:

- Publish-time validation: a payload whose runtime type contradicts
  the key is rejected and logged, never delivered (a backstop for the
  type-erased paths; typed paths do not compile when wrong).
- Weak-owner lifecycle: `listenTo(key, owner) { ... }` ties the
  subscription to `owner`'s lifetime; there is no unsubscribe
  boilerplate, dead owners are swept automatically, and
  `unsubscribeOwner(owner)` detaches immediately when teardown must be
  deterministic (session end, tests).
- Ordering (the five rules, from the `EventManager` KDoc): delivery
  order ACROSS keys is unspecified; per-key delivery is in trigger
  order; UI listeners always run on the main dispatcher; a listener
  never observes a replay older than the latest trigger; triggers are
  synchronous, callable from any thread, and never block (overflow
  drops the OLDEST buffered event, latest wins).
- A listener that throws is logged loudly and KEPT ALIVE; one bad
  payload must not silently kill a screen's subscription.
- Every trigger passes one log choke point, so the whole app's event
  traffic is greppable in the log file.

Latest-wins replay means events are projections of state, never a
lossless work queue. Work that must not be lost belongs in a durable
pipeline; see the doctrine in
[ARCHITECTURE-SCALING.md](ARCHITECTURE-SCALING.md).

### Managers and concurrency

Every manager extends `ConfinedManager`, which provides the same
concurrency rails (`managers/ConfinedManager.kt`):

- A NAMED SERIAL CONFINEMENT: one thread's worth of
  `Dispatchers.Default`, named after the manager. Because the scope
  runs at most one coroutine at a time, plain `var` state is safe and
  check-then-set sequences are atomic, with no locks and no
  main-thread dependency.
- A supervised scope with a `CoroutineExceptionHandler`: an uncaught
  coroutine exception crashes an Android app, so the handler is
  load-bearing. It logs through the injected `LogManager`.
- `onIo` / `onCpu` offload helpers, with one rule: READ confined state
  before offloading (capture into locals), WRITE it after returning.
- `close()`, called by the component in reverse construction order.

UI delivery is unaffected: `listenTo` callbacks always arrive on the
main dispatcher, whatever thread the manager published from.

### Cross-manager choreography (the canonical pattern)

`JokeManager` demonstrates how managers react to each other while
staying peers: it subscribes to `NetworkConnectivityChanged` in its
init block with the manager ITSELF as owner (the subscription then
lives exactly as long as the manager), and hops each callback onto its
own confinement before touching mutable state, because callbacks are
delivered on Main. Behavior: when the last fetch FAILED and
connectivity transitions from offline to online, it auto-refreshes
exactly once per failure. Copy this shape (subscribe in init,
`owner = this`, react on your own confinement) for any manager that
needs to react to another manager's events.

### Boundaries and crash safety

- `ConnectivityMonitor` (`managers/ConnectivityMonitor.kt`) isolates
  the platform's connectivity machinery; `AndroidConnectivityMonitor`
  is the real adapter, tests inject a fake and drive changes by hand.
- `Clock` (from `kotlin.time`) is injected via `AppConfig` wherever
  wall time is read (log timestamps), so tests can pin time.
- `CrashReporter` (`di/CrashReporter.kt`) is the crash-backend seam;
  the component's uncaught-exception handler calls `recordFatal`, the
  no-op default reports nowhere.
- `LogManager` writes through a bounded single-writer channel so
  logging never does IO on the calling thread, with two crash-forensic
  exceptions: ERROR-level lines drain the queue synchronously before
  the call returns, and `flushForCrash()` lets a dying process make
  the same best-effort drain. The log file rotates by size
  (`base-app.log` becomes `base-app.1.log` at the `AppConfig` cap;
  one rotated file is kept).

### UI layer

- `views/` + `views/components/`: pages and reusable components; every
  composable has a `@Preview`, backed by stateless content
  composables.
- `viewModels/`: thin Hilt viewmodels (writes and user actions only;
  views observe events directly via `eventState`/`eventStateOrNull`
  from `EventManagerCompose.kt`, backed by the `LocalEventManager`
  CompositionLocal that `MainActivity` provides).
- `animations/AppAnimations.kt`: ALL motion definitions live here.
- `constants/`: `BrandColors` semantic tokens and `LogLevel`.

## Demo feature pattern

`JokeManager` shows how to add a feature: fetch in the manager,
publish ONE state event (`JokeStateChanged`, a `StateKey<JokeState>`
declared beside the manager) whose payload carries the whole story
(REFRESHING, then SUCCESS or FAILED, retaining the last good joke),
let views listen, and let the viewmodel forward user actions. Copy
that shape for real features.

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

JVM unit tests exercise a REAL `AppComponent` per test with boundary
fakes only (`app/src/test`):

```
./gradlew :app:testDebugUnitTest
```

- `testkit/TestAppContext` builds the component from `AppConfig` with
  a Ktor MockEngine (`FakeJokeApi`), a `FakeConnectivityMonitor`, a
  unique temp directory per test for the real DataStore and log file,
  a pinned `VirtualClock`, and a test Main dispatcher; `close()` in
  teardown restores everything.
- `testkit/TestEventRecorder` is the event assertion kit: suspending
  `expectEvent`/`expectState`, `assertOrder`, `assertNoEvent`.
- Suites: `EventManagerContractSpec` (validation, replay vs signal,
  session reset, `unsubscribeOwner`, weak-owner sweep),
  `JokeManagerLifecycleSpec`, `DataStoreManagerSpec`,
  `JokeConnectivityChoreographySpec` (the reconnect auto-refresh),
  `MainViewModelSpec`, `SettingsViewModelSpec`, plus
  `CompositionRootGuardTest`, which fails the build if a global
  container or library-kit terminology ever creeps back into the
  sources.

Instrumented flow tests drive the REAL app on a connected device or
emulator (real Hilt graph, real managers, real DataStore; no mocks
anywhere):

```
./gradlew :app:connectedDebugAndroidTest
```

## Scaling

The template ships small on purpose. When the app grows, the deferred
designs in [ARCHITECTURE-SCALING.md](ARCHITECTURE-SCALING.md) say what
to add and, just as importantly, WHEN: a session component, a durable
job pipeline, the module split, event governance, lazy startup tiers,
and the payload evolution rules that apply from day one.
