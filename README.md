# Android-App-Template

A small, complete Android app meant to be copied and grown into a
real product. Compose UI on top of an event-driven core, wired up by
hand, and split into a few Gradle modules so the layering is enforced
by the compiler instead of by code review.

This page is the overview. The full contracts and conventions live in
[ARCHITECTURE.md](ARCHITECTURE.md).

## The idea

Managers own the app's state. When something changes, the owning
manager publishes a typed event on a shared bus. Screens observe the
bus directly, and viewmodels just forward user actions back to the
managers. That's the whole loop:

```mermaid
flowchart LR
    M["Manager<br/>(owns state)"]
    B(("Event<br/>bus"))
    V["Views<br/>(observe directly)"]
    VM["ViewModel<br/>(thin, writes only)"]
    M2["Another manager"]
    M -- "publishes typed events" --> B
    B -- "replayed state,<br/>delivered on Main" --> V
    V -- "user action" --> VM
    VM -- "forwards the action" --> M
    M2 -- "publishes too" --> B
    B -. "choreography: subscribe,<br/>never hold a reference" .-> M2
```

Anything that touches the platform (connectivity callbacks, Logcat,
crash backends, remote flags) sits behind a small interface, and the
Android implementation lives at the app's edge. The core never sees a
`Context`, so it stays a plain Kotlin module and its tests run on the
JVM with simple hand-written fakes:

```mermaid
flowchart LR
    subgraph CORE[":core (android.* does not exist here)"]
        MGR["Manager"] --> PORT["Port<br/>(capability interface)"]
    end
    subgraph EDGE[":app edge"]
        AD["Android adapter"] --> SDK["Platform SDK"]
    end
    subgraph TESTS["JVM tests"]
        FAKE["Hand-written fake"]
    end
    AD -. "implements" .-> PORT
    FAKE -. "implements" .-> PORT
```

One plain class (`AppComponent`) builds everything. Construction runs
top to bottom, first fetches happen in a separate start phase, and
teardown walks back in reverse:

```mermaid
flowchart LR
    C["construct<br/>declaration order,<br/>wiring only, no IO"]
    S["start<br/>first fetches and<br/>warmups, exactly once"]
    R["running<br/>publish, react,<br/>observe"]
    X["close<br/>reverse order,<br/>bus torn down last"]
    C --> S --> R --> X
```

## Example flows

The loop above, played out in time. First, an ordinary user action
(the demo feature's refresh button):

```mermaid
sequenceDiagram
    actor User
    participant View
    participant VM as ViewModel
    participant MGR as Manager
    participant Bus as Event bus
    User->>View: taps Refresh
    View->>VM: onRefresh()
    VM->>MGR: refresh()
    MGR->>Bus: publish State(refreshing)
    Bus-->>View: delivered on Main
    Note over View: shows the spinner
    MGR->>MGR: fetch on its own thread
    MGR->>Bus: publish State(fresh data)
    Bus-->>View: delivered on Main
    Note over View: shows the content
```

Second, one manager reacting to another. Neither holds a reference to
the other; the reaction is just a subscription (in the demo: the
device comes back online, and a failed fetch retries itself):

```mermaid
sequenceDiagram
    participant OS as Platform
    participant CM as Connectivity manager
    participant Bus as Event bus
    participant FM as Feature manager
    participant View
    OS->>CM: network is back
    CM->>Bus: publish Connectivity(online)
    Bus-->>FM: subscribed since init
    Note over FM: last fetch failed?<br/>then retry once
    FM->>Bus: publish State(fresh data)
    Bus-->>View: delivered on Main
```

Third, what the bus does for state on its own. The latest value of a
state event is cached, so a screen that opens later gets current
state immediately without fetching anything, and a publish that
nobody would learn from is dropped:

```mermaid
sequenceDiagram
    participant MGR as Manager
    participant Bus as Event bus
    participant NS as New screen
    MGR->>Bus: publish State(v1)
    Note over Bus: caches the latest value
    NS->>Bus: subscribe (opens later)
    Bus-->>NS: replays State(v1) right away
    MGR->>Bus: publish State(v1) again
    Note over Bus: equal to the cache:<br/>suppressed, nothing delivered
    MGR->>Bus: publish State(v2)
    Bus-->>NS: State(v2)
```

Signals (one-shot notifications) are the mirror image: never replayed
to late subscribers and never deduped, because firing twice means it
happened twice.

## Modules

Arrows point at what a module is allowed to see. Imports in the wrong
direction don't compile:

```mermaid
flowchart TD
    APP[":app<br/>composition root, adapters,<br/>Application + Activity"]
    UI[":ui<br/>views, viewmodels, navigation, theme"]
    CORE[":core (Kotlin JVM)<br/>managers, event bus, ports, api"]
    T[":templates<br/>living documentation,<br/>ships in nothing"]
    APP --> UI
    APP --> CORE
    UI --> CORE
    T -.-> UI
    T -.-> CORE
```

- `:core` is plain Kotlin: the managers, the event bus, the port
  interfaces, and the API layer. `android.*` isn't on the classpath,
  so platform code can't creep in.
- `:ui` holds the views, viewmodels, navigation, and theme. It only
  sees `:core`.
- `:app` is the shell: the composition root, the Android adapters,
  the `Application`, and the single `Activity`.
- `:templates` is living documentation: fully commented example files
  that compile and test on every build but ship in nothing. Start a
  new feature by copying one; see
  [templates/README.md](templates/README.md).

Rule of thumb: if a file needs `android.*`, it belongs in `:app` or
`:ui`. If the logic still makes sense with every platform detail
removed, it belongs in `:core`.

This layout is a starting cut, not a ceiling. Adding a module is
cheap: register it in `settings.gradle.kts`, depend on `:core`, and
keep the arrows pointing one way. The package tree already mirrors
the future modules, so each planned split is a `git mv` plus one
build file. The natural next splits (a `:data` module for
persistence, a `:platform` module for the adapters, feature modules
once teams need to work in parallel) are mapped out in
[ARCHITECTURE-SCALING.md](ARCHITECTURE-SCALING.md), along with the
point at which each becomes worth doing.

## Why this shape

- Managers are peers. Nothing grows into a god object, and features
  land side by side instead of tangling into each other.
- State reaches the UI exactly one way, so there's one threading
  contract, one replay rule, and one place to trace any transition.
- The compiler polices the layers, not reviewers.
- Tests are real. JVM specs boot the actual component with fakes only
  at the boundaries. There's no mocking framework in the repo.
- The lifecycle is boring on purpose: build in order, start once,
  tear down in reverse. Cold start stays out of constructors.
- Every event leaves a breadcrumb, so crash reports arrive with the
  app's recent history attached.
- The whole tree is readable in an afternoon.

## Notable features

- **Retry utility** (`util/Retry.kt`): one small policy type and two
  functions. `retry` handles one-shot work with bounded attempts and
  capped exponential backoff, rethrowing cancellation and permanent
  failures immediately. `retryForever` keeps long-lived stream
  bridges alive, resetting the backoff each time the stream emits
  successfully. The DataStore bridges ride it, so one bad read costs
  a short delay instead of the feature.
- **Feature flags**: typed boolean flags resolved as debug override >
  remote provider > compiled default. A debug-only sheet in Settings
  lists every flag and lets you override it on the spot. Release
  builds never even create the override store.
- **Crash-friendly logging**: every log line and every event on the
  bus leaves a breadcrumb, errors become non-fatals through a
  pluggable crash-reporter seam, and the rotating log file can be
  exported straight from Settings.
- **Typed navigation**: destinations are sealed classes, back stacks
  are per tab, deep links map to destinations in one testable method,
  and the whole navigation state survives process death as a single
  JSON string.
- **Guard tests**: conventions are enforced by the build, not the
  reviewer. A flag missing from the registry, a constructor doing
  network IO, or a second publisher on an event key all fail the
  test suite.

## Stack

Kotlin 2.4.10, AGP 9.0.1 (built-in Kotlin; don't apply
`org.jetbrains.kotlin.android`), Gradle 9.1.0, compileSdk 36,
minSdk 32, Hilt + KSP, Compose BOM 2026.06.01, Ktor 3.5.1,
DataStore 1.2.1, kotlinx-serialization.

## Build and test

```
./gradlew :app:assembleDebug
./gradlew :core:test :ui:testDebugUnitTest :app:testDebugUnitTest
./gradlew :app:connectedDebugAndroidTest    # real device or emulator
```

The repo is self-contained; no external checkouts required.

## Digging deeper

- [ARCHITECTURE.md](ARCHITECTURE.md): the contracts and conventions
  (events, managers, wiring, flags, navigation, logging, tests).
- [templates/README.md](templates/README.md): copy-paste starting
  points for a new manager, port, screen, and their tests.
- [ARCHITECTURE-SCALING.md](ARCHITECTURE-SCALING.md): what to add
  when the app grows, and when it's worth adding.
