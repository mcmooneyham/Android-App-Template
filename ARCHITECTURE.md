# Architecture reference

The [README](README.md) gives the overview and the diagrams. This
page holds the full contracts and conventions. The KDocs on the
classes themselves are the source of truth; this page is the tour.

## The component map

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
     |-- logManager ............. platform log mirror + rolling
     |                            log files + telemetry funnel
     |-- connectivityManager .... validated connectivity, behind the
     |                            ConnectivityMonitor boundary
     |-- dataStoreManager ....... Preferences DataStore facade
     |-- featureFlagManager ..... layered flags: debug override >
     |                            provider (seam) > compiled default
     |-- httpClient ............. the ONE shared Ktor engine
     |                            (httpClientFactory seam); managers
     |                            wrap it in per-endpoint ApiClients
     |-- jokeManager ............ demo feature + the cross-manager
     |                            choreography exemplar
     v
AppModule (Hilt) ................ thin adapter exposing the component's
     |                            members to @Inject sites
     v
MainActivity .................... provides LocalEventManager; deep
     |                            links arrive as plain strings
     |-- navigation/ ............ AppRouter: typed destinations,
     |                            per-tab back stacks, ONE deep-link
     |                            mapper; JSON-saved across process
     |                            death
     |-- views/ ................. observe events directly via
     |                            eventState / eventStateOrNull
     |-- viewModels/ ............ thin: user actions and writes only
```

Managers are PEERS: there is no central coordinator, and no manager
holds a reference to another manager's internals. They publish typed
events on the bus and (rarely, deliberately) react to each other's
events. Data flows one way: managers publish, the UI listens,
viewmodels forward user actions back to managers.

## Composition root (`di/`)

- `AppConfig` gathers everything the component needs from the outside
  world into one value: the files and cache directories (typed
  `java.io.File`s), the `ConnectivityMonitor` boundary, the minimum
  log level, an `httpClientFactory: (Json) -> HttpClient` seam (tests
  swap in a Ktor MockEngine), an injectable `Clock`, a `CrashReporter`
  seam (no-op by default), the `PlatformLogWriter` mirror, the
  `FeatureFlagProvider` seam with its overrides toggle, and the three
  log caps (per-file roll size, retention days, total size). Every
  field with a production default can be overridden per test. Endpoint
  URLs are deliberately NOT here: each manager declares its own base
  URL beside itself and wraps the shared `httpClient` in its own
  `ApiClient`, so apps built on the template add services by adding
  clients, never by widening a global URL.
- `AppComponent` is a plain class: property initializers run top to
  bottom, so declaration order IS the construction order. Every
  member registers its own teardown BESIDE its declaration
  (`closedBy` for resources, `registered()` for managers), so
  `close()` just walks the self-mirrored registry in reverse
  construction order, cancelling the event bus LAST so teardown
  events still deliver; feature PRs never edit `close()` (the one
  hand-maintained step is the crash-handler restore, which must run
  FIRST and conditionally). The
  component also installs a `Thread` default uncaught-exception
  handler that reports the fatal to the `CrashReporter`, logs it
  (`logFatal`, so the crash is never double-counted), drains the log
  queue to disk (`flushForCrash`), and delegates to the previous
  handler. `start()` runs every manager's post-construction side
  effects in construction order; BaseApplication calls it right
  after construction.
- `BaseApplication.onCreate` is the only Android edge: it builds every
  platform-touching value (typed `File`, `AndroidConnectivityMonitor`,
  debug-vs-release log level from `BuildConfig.DEBUG`) and constructs
  exactly ONE `AppComponent` per process. Managers never see a
  `Context`, which is what lets JVM tests build a real component from
  fakes alone.
- `AppModule` is a thin Hilt adapter: every MANAGER provider reads a
  member FROM the component (plain build metadata like `BuildInfo` is
  constructed in place); Hilt never constructs a manager itself.

Wiring conventions (also stated in the `AppComponent` KDoc, which is
the source of truth):

1. Construction order is bus, logging, then peer managers.
2. Managers receive only what they use, by constructor, within a
   budget of five parameters (a bus handle counts as one). A manager
   that needs more is doing too much: split it (or group a policy
   value the way `LogFileSettings` does).
3. Never inject the component itself into a manager.
4. The one sanctioned setter cycle is
   `eventManager.attachLogManager(logManager)`, called in the
   component's init block. Add no others.
5. Feature managers APPEND at the end of their marked region and may
   depend on the infrastructure tier (bus, logging, flags,
   httpClient) but never on each other: cross-feature conversation
   rides published events, so concurrent feature PRs merge as
   trivially adjacent additions.
6. THE INIT BUDGET: construction may allocate, subscribe, and
   register cheap callbacks; it must NOT issue network requests or
   any unbounded-latency work. First fetches belong in the manager's
   `start()`, and a guard test fails any constructor that fetches
   (zero MockEngine requests before `component.start()`).

## The event contract

Event keys are plain named objects declared BESIDE the manager that
publishes them, in two flavors (`managers/eventManager/EventKey.kt`):

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
- `JokeDetailChanged`: `StateKey<JokeDetailState>`,
  `"joke.DetailChanged"`, SESSION lifetime, beside `JokeManager`: the
  keyed list-to-detail exemplar (the payload carries the REQUESTED
  id, so screens render only their own id).
- `NetworkConnectivityChanged`: `StateKey<Boolean>`,
  `"network.ConnectivityChanged"`, APP lifetime (connectivity is a
  device fact, not user state), beside `ConnectivityManager`.
- `HasSeenWelcomeChanged`: `StateKey<Boolean>`,
  `"datastore.HasSeenWelcomeChanged"`, APP lifetime (the preference
  file is device-persistent and its bridge re-publishes only on value
  changes, so a SESSION key would go dark after a session reset),
  beside `DataStoreManager`.
- `LogsCleared`: `SignalKey`, `"log.Cleared"`, beside `LogManager`.
- `FeatureFlagsChanged`: `StateKey<FlagSnapshot>`, `"flags.Changed"`,
  APP lifetime, beside `FeatureFlagManager`.

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
- Unchanged state is not re-delivered: triggering a state key with a
  payload equal to the cached value is suppressed (subscribers
  already hold the current value; signals always deliver). Publishers
  therefore need no hand-rolled dedupe, and re-publishing state to
  "nudge" listeners does not work by design.
- Owner lifecycle: `listenTo(key, owner) { ... }` runs the callback
  WITH THE OWNER AS RECEIVER while the bus holds the owner weakly.
  Reach the owner only through the receiver (pass lambda literals;
  when the owner is not `this`, enclosing members need an explicit
  `this@Outer`) and the subscription dies with it. A callback that
  CAPTURES the owner pins it, so deterministic teardown is the
  PRIMARY contract: ViewModels call `unsubscribeOwner` in onCleared,
  sessions in their teardown, and managers simply rely on the bus
  dying with the component; auto-removal is the safety net.
- Ordering (the six rules, from the `EventManager` KDoc): delivery
  order ACROSS keys is unspecified; per-key delivery is in trigger
  order; UI listeners always run on the main dispatcher; a listener
  never observes a replay older than the latest trigger; triggers are
  synchronous, callable from any thread, and never block (overflow
  drops the OLDEST buffered event, latest wins); and a SIGNAL
  subscription is live before `listenTo` returns, so a signal fired
  on the caller's next line is delivered (state subscriptions may
  attach asynchronously; replay makes that harmless).
- A listener that throws is logged loudly and KEPT ALIVE; one bad
  payload must not silently kill a screen's subscription.
- Every DELIVERED trigger passes one breadcrumb choke point into the
  crash report's breadcrumb ring; suppressed duplicates leave only a
  debug trace, and rejected payloads log at ERROR (so they reach the
  telemetry funnel as non-fatals). Breadcrumbed events also land in
  the log file as DEBUG lines when the minimum level admits it (debug
  builds), so the app's delivered event traffic is greppable there.

Latest-wins replay means events are projections of state, never a
lossless work queue. Work that must not be lost belongs in a durable
pipeline; see the doctrine in
[ARCHITECTURE-SCALING.md](ARCHITECTURE-SCALING.md).

## Managers and concurrency

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
- `start()`, called once by `AppComponent.start()` in construction
  order: the home for first fetches and warmups the init budget
  keeps out of constructors (JokeManager's first load lives here).
- `close()`, called by the component in reverse construction order.

Transient failures retry through `util/Retry.kt`: `RetryPolicy` plus
`retry` (one-shot operations: bounded attempts, capped exponential
backoff with optional jitter for stampede-prone retriers,
cancellation and permanent failures rethrow immediately) and
`retryForever` (long-lived stream bridges: the block's `onHealthy`
hook resets the backoff on each successful emission; both share one
`bridgeRetryReporter`, ERROR on an outage's first failure, WARN on
repeats). Both DataStore bus bridges ride `retryForever`, so one bad
read costs a delay, not the feature. Work that must survive process death is NOT retry
material; it belongs to the durable job pipeline
(ARCHITECTURE-SCALING.md).

UI delivery is unaffected: `listenTo` callbacks always arrive on the
main dispatcher, whatever thread the manager published from.

## Cross-manager choreography (the canonical pattern)

`JokeManager` demonstrates how managers react to each other while
staying peers: it subscribes to `NetworkConnectivityChanged` in its
init block with the manager ITSELF as owner (the subscription then
lives exactly as long as the manager), and hops each callback onto its
own confinement before touching mutable state, because callbacks are
delivered on Main. Behavior: when `JokeAutoRetryOnReconnectFlag` is
enabled (off by default) and the last fetch FAILED and connectivity
transitions from offline to online, it auto-refreshes exactly once
per failure. Copy this shape (subscribe in init,
`owner = this`, react on your own confinement) for any manager that
needs to react to another manager's events.

## Boundaries and crash safety

- `ConnectivityMonitor` (in `:core`) isolates the platform's
  connectivity machinery; `AndroidConnectivityMonitor` (in
  `:app/platform`) is the real adapter, tests inject a fake and drive
  changes by hand. The port's contract: the FIRST report, delivered
  during start, is the device's real current state (never an assumed
  default), so a boot while online can never masquerade as a
  reconnect edge. `PlatformLogWriter` is the same shape for the
  Logcat mirror (`AndroidLogWriter`); JVM tests keep its no-op
  default. Ports live in `:core`, where the missing Android classpath
  makes the isolation structural.
- The shared HTTP client configures explicit timeouts (a 30 s overall
  call budget plus 10 s connect/socket caps in `HttpClientFactory`),
  so even a trickling response terminates and surfaces as
  `FailureKind.TIMEOUT`; engine defaults alone would let a slow body
  hold in-flight state forever.
- `Clock` (from `kotlin.time`) is injected via `AppConfig` wherever
  wall time is read (log timestamps), so tests can pin time.
- `BuildInfo` (versionName, build stamp, isDebugBuild) is constructed
  from BuildConfig in `:app` and injected into the UI layer, which
  has no application BuildConfig of its own.
- `CrashReporter` (`managers/logManager/CrashReporter.kt`, in
  `:core` beside its owning manager) is the crash-backend seam
  (its KDoc carries copy-paste Crashlytics and Sentry shapes). The
  component's handler calls `recordFatal`; everything else funnels
  through the LogManager: every accepted ERROR line becomes a
  `recordNonFatal` (the attached throwable, or a call-site-stamped
  `LoggedError`), and every delivered bus trigger plus every
  WARN/ERROR line becomes a `recordBreadcrumb`, so release crash
  reports carry the recent app history even though DEBUG traces stay
  out of the file.
- `LogManager` writes through a bounded single-writer channel so
  logging never does IO on the calling thread, with two crash-forensic
  exceptions: ERROR-level lines drain the queue synchronously before
  the call returns (BOUNDED at 64 commands, so a full queue can never
  ANR the calling thread; the writer lands any remainder moments
  later), and `flushForCrash()` keeps the unbounded drain for a dying
  process. Failed file writes report through Logcat, one non-fatal
  per process, and an honest marker line in the log itself.
  Log files live in a `logs/` subdirectory of filesDir, one per UTC
  day (`base_app-2026-01-01.log`), and a file reaching the
  `AppConfig` roll cap is renamed to the day's next numbered sibling
  (`base_app-2026-01-01.1.log`) so appends continue into a fresh
  file. Retention runs asynchronously on the manager's own scope,
  never on an append or crash-drain path: files older than the
  retention window go, then the oldest files (by modification time)
  until the total fits the size cap, and the current day's live file
  is never deleted, so disk use can transiently reach the cap plus
  one file. `writeExportSnapshot()` flushes the writer, then zips
  every log file oldest first into `base_app-export.zip` in cacheDir,
  outside the file lock so logging never stalls behind the export;
  Settings shares that zip as a FileProvider URI stream (never
  `EXTRA_TEXT`, which drops history and risks the ~1 MB Binder cap).

## Feature flags

Typed boolean flags with three value layers, most specific wins:
debug override > provider > compiled default.

- A flag is an `object` extending `BooleanFlag`, declared BESIDE the
  feature that consumes it (like an event key) and listed in
  `AppFlags.all`; `FeatureFlagRegistryGuardTest` fails the build if a
  declaration is missing from the registry.
- `FeatureFlagManager` resolves all layers and publishes every change
  as `FeatureFlagsChanged` (a full `FlagSnapshot`). Managers read
  `isEnabled(flag)` synchronously AT DECISION TIME (never cache it at
  construction); views observe with the one-line `flagState(flag)`
  composable.
- `FeatureFlagProvider` (`managers/featureFlagManager/`) is the
  remote-backend seam, a no-op by default; tests drive a fake by hand
  (see the deferred vendor-adapter design in ARCHITECTURE-SCALING.md).
- Overrides are DEBUG ONLY: the "Feature flags" row under Settings >
  Debug opens a modal sheet listing every declared flag with its live
  resolved state, the layer that decided it, and a Default/On/Off
  override control. Overrides persist in a dedicated DataStore file
  and survive relaunches. Release builds never create that store
  (`AppConfig.featureFlagOverridesEnabled` is false), so they are
  structurally locked to defaults plus provider values.
- `JokeManager` is the live example: `JokeAutoRetryOnReconnectFlag`
  (off by default) gates the reconnect auto-refresh choreography;
  enable it from the sheet to watch the choreography run.

## Navigation

A hand-rolled typed router (`navigation/`), mirroring the iOS
sibling's HomeRouter and deliberately shaped like Navigation 3 so the
library migration at threshold is mechanical (the recipe lives in
ARCHITECTURE-SCALING.md):

- Destinations are DATA: `AppTab` (the root tabs) and per-tab sealed
  hierarchies (`HomeDestination.JokeDetail(jokeId)`). Every switch is
  exhaustive; adding a screen is a compile error until handled, and
  no index can fall into a silent else.
- `AppRouter` holds the selected tab plus one `TabRouter` back stack
  per tab; `TabStackHost` renders each stack inside the keep-alive
  shell, so the tab bar stays visible on pushed screens and stacks
  survive tab switches. System back pops the selected tab's stack
  and otherwise finishes the activity; re-selecting a tab pops it to
  its root.
- Process death: the WHOLE navigation state round-trips through one
  JSON string in saved instance state (`rememberAppRouter`); corrupt
  or stale state from an app update restores to a fresh root, never
  a crash. Destination arguments follow the payload-evolution rules.
- Deep links (`baseapp://joke/<id>`, same scheme as iOS) map to
  destinations in ONE JVM-testable method,
  `AppRouter.handleDeepLink`; MainActivity only moves strings
  (singleTop plus onNewIntent).
- THE SEAM RULE: managers and viewmodels never navigate; they publish
  facts, and the shell (the router's only owner) maps facts to router
  calls in one choke point (`RouteOnAppEvents`). Direct user actions
  stay semantic lambdas (`onOpenJokeDetail`), wired to the router at
  the shell, so pages remain previewable and navigation-free.

## UI layer

- `views/` + `views/components/`: pages and reusable components; every
  composable has a `@Preview`, backed by stateless content
  composables.
- `viewModels/`: thin Hilt viewmodels (writes and user actions only;
  views observe events directly via `eventState`/`eventStateOrNull`
  from `EventManagerCompose.kt`, backed by the `LocalEventManager`
  CompositionLocal that `MainActivity` provides).
- `animations/AppAnimations.kt`: ALL motion definitions live here.
- `designSystem/`: the design tokens. `AppSpacing` (the 4-point scale
  plus semantic aliases like `screenEdge` and `cardPadding`),
  `AppTypography` (the explicit type scale MaterialTheme serves, with
  `AppFontFamily` as the one-line brand-font swap point), `AppShapes`
  (the corner language), and `AppDimens` (icon, tile, and tab-bar
  metrics). Views consume tokens, never raw dp/sp; a rhythm or type
  change is one edit here.
- `constants/`: `BrandColors` semantic tokens, mapped onto the
  Material color scheme in `BaseAppTheme` (`LogLevel` lives in
  `:core`'s constants package).
- Localization: user-facing copy lives in string resources (`:ui`'s
  and `:templates`' `res/values/strings.xml`), read via
  `stringResource`; adding a language is a `values-xx` folder, not a
  refactor, and the instrumented flow tests resolve their matchers
  through the same resources.
- Keep-alive tabs, a deliberate cost: every tab stays composed
  (hidden ones drawn at alpha 0) so screen state and scroll positions
  survive tab switches. Hidden tabs therefore keep their bus
  subscriptions collecting; that stays cheap because state keys are
  low-traffic replayed projections, and loading animations leave the
  composition once content settles. Revisit alongside the Navigation
  3 threshold if a tab ever hosts high-rate collections.

## The demo feature

`JokeManager` shows how to add a feature: declare the service's base
URL beside the manager and wrap the shared `httpClient` in the
manager's own `ApiClient`, subscribe in init but fetch in `start()`
(the init budget), publish a state event (`JokeStateChanged`, a
`StateKey<JokeState>` declared beside the manager) whose payload
carries the card's whole story (REFRESHING, then SUCCESS or FAILED,
retaining the last good joke), let views listen, and let the
viewmodel forward user actions. The pushed `JokeDetailPage` shows the
KEYED list-to-detail half: the screen asks the manager to ensure its
id is loaded (`loadJokeDetail`; a cache hit answers instantly,
anything else fetches by id) and renders only `JokeDetailChanged`
states carrying that id, which is what makes a cold-start deep link
to any id work. Copy those shapes for real features; each new
service gets its own per-endpoint `ApiClient` on the same shared
engine.

## Tests

JVM unit tests exercise a REAL `AppComponent` per test with boundary
fakes only. They span the modules: `:core` holds the bus-contract,
recorder, and retry specs (plain JVM, no AGP), `:ui` holds the router
spec, `:app` holds the component-level specs and the guards, and
`:templates` self-tests its exemplars:

```
./gradlew :core:test :ui:testDebugUnitTest :app:testDebugUnitTest \
    :templates:testDebugUnitTest
```

- The fakes, the recorder, and `awaitTrue` live in `:core`'s
  testFixtures, consumed everywhere via
  `testFixtures(project(":core"))`; `TestAppContext` itself lives in
  `:app`'s tests because it builds the real `AppComponent`.
- `testkit/TestAppContext` builds the component from `AppConfig` with
  a Ktor MockEngine (`FakeJokeApi`), a `FakeConnectivityMonitor`, a
  unique temp files directory per test for the real DataStore and the
  `logs/` subdirectory, a unique temp cache directory for the export
  zip, a pinned `VirtualClock`, and a test Main dispatcher; `close()`
  in teardown restores everything.
- `testkit/TestEventRecorder` is the event assertion kit: suspending
  `expectEvent`/`expectState`, `assertOrder`, `assertNoEvent`.
- Suites: `EventManagerContractSpec` (validation, replay vs signal,
  dedupe of unchanged state, session reset, `unsubscribeOwner`, both
  sides of the weak-owner contract, the live-before-return signal
  subscription, and DROP_OLDEST overflow's contiguous-suffix
  behavior), `JokeManagerLifecycleSpec` (fetch lifecycle, in-flight
  guard, failure taxonomy incl. TIMEOUT and DECODE, the keyed detail
  pattern),
  `DataStoreManagerSpec` (including bridge resubscription after a
  read failure), `JokeConnectivityChoreographySpec` (the reconnect
  auto-refresh and its flag gate), `FeatureFlagManagerSpec` (layer
  precedence, live provider updates, override persistence, the
  release lock, bridge resubscription), `LogManagerReportingSpec`
  (the telemetry funnel, breadcrumbs, level filtering, write-failure
  markers), `LogManagerCrashSafetySpec`, `LogManagerRollingFileSpec`
  (daily dated files, age and total-size prunes, the midnight
  read fallback, zip ordering), `RetrySpec` (the full
  retry-utility contract), `AppRouterSpec` (back semantics, deep
  links, corrupt restore), `MainViewModelSpec`,
  `SettingsViewModelSpec` (including the export zip's flush and
  full-history guarantees), `ConnectivityManagerSpec` (duplicate
  platform reports publish once; a boot while online is never a
  reconnect edge), plus the guards:
  `FeatureFlagRegistryGuardTest` (every declared flag is registered),
  `WiringConventionsGuardTest` (single-publisher; AppModule mirrors
  the component's managers; destinations are data types), and the
  init-budget fence in `TestAppContextSpec` (construction performs
  zero network IO).

Instrumented flow tests drive the REAL app on a connected device or
emulator (real Hilt graph, real managers, real DataStore; no mocks
anywhere). That honesty has a stated cost: the joke flows exercise
the real third-party API, so the tests that require a served joke or
a validated network SELF-SKIP (JUnit assumptions) instead of failing
when either is unavailable:

```
./gradlew :app:connectedDebugAndroidTest
```
