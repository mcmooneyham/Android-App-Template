# Architecture scaling guide

The template ships the smallest skeleton that scales: a three-module
layer cut, one composition root, a handful of peer managers on a
typed event bus.
This file holds the designs that were DELIBERATELY deferred, adapted
to this codebase, each with an explicit adoption threshold.

The rule: adopt a mechanism AT its threshold, not before. Every one of
these earns its complexity only once the trigger condition is real;
built earlier, it is speculative framework code that a two-person team
pays for on every change.

| Mechanism            | Adopt when                                  |
| -------------------- | ------------------------------------------- |
| SessionComponent     | the first login / account feature           |
| Durable job pipeline | the first must-survive-process-death work   |
| Feature-module split | ~10 managers, or a second team (the LAYER   |
|                      | modules :core/:ui/:app ship day-one)        |
| Event governance     | the module split, or ~20 event keys         |
| Lazy startup tiers   | ~15 managers, or measured cold-start cost   |
| Payload evolution    | day one (rules, not a mechanism)            |
| Flag vendor adapter  | the first remotely-controlled flag          |
| Flag experiments     | the first A/B test                          |
| Navigation 3 upgrade | ~15 destinations, the first nested/modal    |
|                      | flow, or predictive-back needs              |
| Non-Main bus lane    | first sustained high-rate publisher         |
|                      | (~tens of Hz: sync progress, BLE telemetry) |

Everything below refers to the shipped code by its real names:
`AppComponent` and `AppConfig` in `di/`, `EventManager` /
`StateKey` / `SignalKey` / `EventLifetime` in `managers/`, and the
`ConfinedManager` base class.

## 1. SessionComponent

Adoption threshold: the FIRST login or account feature. Until a user
can sign in, there is no session state to scope, and
`EventLifetime.SESSION` plus `resetSessionReplayCaches()` (already
shipped) are the whole story.

### Design

Keep `AppComponent` process-scoped and untouched. Add a second, small
composition root that lives exactly as long as one signed-in session:

```kotlin
// Beside the session managers, like any other key. A SignalKey is
// APP-lifetime by definition, so it survives the reset performed
// during the teardown that announces it.
object SessionEnded : SignalKey(eventName = "session.Ended")

class SessionComponent(
    private val appComponent: AppComponent,
    val signedInUserId: String,
) {

    // Session-scoped work parented here so one cancel stops it all.
    private val sessionScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default,
    )

    // Session managers follow every AppComponent convention:
    // top-to-bottom construction, constructor injection, the
    // five-parameter budget, keys declared beside the manager
    // (SESSION lifetime by default, which is what makes the reset
    // below correct).
    val syncManager = SyncManager(
        appComponent.httpClient,
        appComponent.logManager,
        appComponent.eventManager,
    )

    /** Canonical teardown order; see the numbered rationale below. */
    fun close() {
        val eventManager = appComponent.eventManager
        // 1. Flush session managers.
        runBlocking { syncManager.flushPendingWrites() }
        // 2. Cancel the session scope.
        sessionScope.cancel()
        syncManager.close()
        // 3. Detach session owners immediately.
        eventManager.unsubscribeOwner(syncManager)
        // 4. Clear SESSION replay caches.
        eventManager.resetSessionReplayCaches()
        // 5. Announce the end on an APP-scoped signal.
        eventManager.trigger(SessionEnded)
    }
}
```

Why this exact order:

1. FLUSH FIRST: persisting in-flight session state needs the managers
   and their scopes alive; anything after a cancel is too late.
2. CANCEL THE SCOPE so no new session work starts while the rest of
   the teardown runs.
3. `unsubscribeOwner` for every session owner: immediate detach, not
   the eventual weak-owner sweep, so no session listener can observe
   the post-reset world.
4. `resetSessionReplayCaches()` so the next login cannot replay the
   previous user's cached state. APP-lifetime keys (connectivity,
   `SessionEnded` itself) survive by design.
5. Publish `SessionEnded` LAST, once the session is actually gone, so
   listeners (navigation, analytics) react to a completed fact.

Login constructs a fresh `SessionComponent`; logout and account switch
call `close()` and drop the reference. The process-scoped bus is
shared: session keys are just keys whose caches the reset clears.

ROUTING THE SESSION END: `SessionEnded` is also the first fact the
navigation seam routes. The shell's `RouteOnAppEvents` choke point
(NavigationBar.kt) ships empty today; adopting the session component
fills it, keeping ALL routing policy in that one function:

```kotlin
val eventManager = LocalEventManager.current
DisposableEffect(eventManager, appRouter) {
    val routingOwner = object {}
    // A COMPLETED logout lands the user on Home's root; the manager
    // that published the fact knows nothing about navigation.
    eventManager.listenTo(SessionEnded, routingOwner) {
        appRouter.selectTab(AppTab.HOME)
        appRouter.homeRouter.popToRoot()
    }
    onDispose { eventManager.unsubscribeOwner(routingOwner) }
}
```

## 2. Durable job pipeline

Adoption threshold: the FIRST feature whose work must survive process
death (an upload queue, an offline outbox, a purchase flow). The demo
joke fetch does not qualify: losing it costs one tap.

### The doctrine

Lossless work never rides the bus; the bus carries latest-wins
projections of durable state.

The event bus is deliberately lossy: replay keeps only the latest
value, and overflow drops the oldest buffered event. That is exactly
right for state (`JokeStateChanged`) and exactly wrong for work items,
where every element matters. Do not "fix" the bus with bigger buffers
or acknowledgments; give work a home with the right guarantees and
keep the bus as the read side.

### Design: WorkManager + a job table

Storage: a Room (or SQLDelight) table is the source of truth.

```
jobs(
  id          TEXT PRIMARY KEY,   -- UUID, the idempotency key
  jobType     TEXT,               -- e.g. "photo.Upload"
  payloadJson TEXT,               -- kotlinx-serialization encoded
  state       TEXT,               -- ENQUEUED | RUNNING | SUCCEEDED
                                  --   | FAILED
  attempts    INTEGER,
  createdAt   INTEGER             -- epoch millis via injected Clock
)
```

Execution: WorkManager, because it is the only Android scheduler that
survives process death and reboots and honors constraints
(connectivity, battery) for free.

The pattern, using an upload feature as the example:

1. The manager's public API writes the row FIRST, then schedules a
   uniquely-named WorkManager request (`enqueueUniqueWork` keyed by
   the job id) and returns. No fetch, no bus traffic for the work
   item itself.
2. A `CoroutineWorker` loads the row by id, performs the work, and
   updates the row transactionally. Transient failures return
   `Result.retry()` and let WorkManager's backoff drive `attempts`;
   permanent failures mark the row FAILED.
3. Workers are IDEMPOTENT by job id: WorkManager may run a job twice
   around a process death, so the worker checks the row's state
   before acting.
4. The PROJECTION is where the bus comes back in: the manager
   observes the table (a Room `Flow`) on its confinement and
   publishes a `StateKey` summary, for example
   `StateKey<UploadQueueState>("upload.QueueChanged")` carrying
   pending/failed counts and the latest terminal result. The UI
   listens to that key exactly like any other state; losing an
   intermediate projection under a burst is harmless because the next
   one carries the current truth.

Recovery after process death is automatic: WorkManager reschedules,
the table is authoritative, and the first projection publish restores
the UI. Nothing about the bus, the keys, or the managers' topology
changes; the pipeline slots in as one more manager plus its storage.

## 3. Module layering and its expansion

STAGE 0 SHIPS IN THE SKELETON: a three-module layer cut whose whole
point is that the COMPILER arbitrates the layering (see the README's
Modules section). `:core` is a Kotlin JVM library, so
`android.*` imports in manager code are unresolvable, not merely
frowned on; `:ui` depends only on `:core`, so UI code cannot reach
the adapters or the composition root; `:app` sees everything and
wires it. This mirrors the layer discipline of Google's app
architecture guide (domain+data merged as `:core`, ui, app) at
template scale, and it is why the split ships day-one rather than at
a threshold: retrofitting a JVM core onto code that has quietly
absorbed Android imports means untangling every file.

The remaining expansions each have a threshold; every one is a
`git mv` plus one build file, because the package tree already
mirrors the future modules:

1. `:data` out of `:core` (threshold: repositories and DTO mapping
   outgrow the `api/` package, roughly the first cache-first
   repository). Moves `api/` plus DTO mappers; `:core` keeps
   managers, ports, and the bus. The heuristic for the cut: if the
   implementation only makes sense in the presence of a remote API
   or a local DB shape, it is `:data`; if removing every transport
   and persistence detail leaves the logic intact, it stays `:core`.
2. `:platform` out of `:app` (threshold: the adapter count outgrows
   a handful; today there are two). Moves the `platform/` package;
   `:app` keeps the composition root and shell.
3. FEATURE modules (threshold: about 10 managers, or a second team).
   Features slice WITHIN the layers, composing with them rather than
   replacing them: a `:feature-x-api` holds the key objects, payload
   types, and the manager's interface; `:feature-x-impl` holds the
   class. Consumers (UI, other features) depend only on api modules;
   impls are wired solely in `:app`'s `AppComponent`. Cross-feature
   choreography stays what it is today: subscribe to the other
   feature's key from its api module; never depend on another
   feature's impl. Split one feature as the exemplar before the
   rest, letting dependency errors surface the couplings the merged
   modules hid.

The composition root stays singular and manual at every stage: the
splits change where types live, not how they are wired.

KMP note: `:core` is the commonMain candidate (its dependencies,
including the DataStore `-core` artifacts, Ktor, okio, and the
kotlinx libraries, are already multiplatform). It is KMP-shaped, not
KMP-ready: the known JVM-isms needing expect/actual are the bus's
WeakReference owner tracking, the LogManager's stack-trace call-site
capture, and java.io.File in the storage seams. Budget for those
three before promising a shared module.

## 4. Event governance

Adoption threshold: the module split, or about 20 event keys,
whichever comes first. With five keys, grep is a catalog and review is
governance; with 20, drift begins to win.

Three mechanisms, in adoption order:

1. CAPABILITY PUBLISHERS. Today any code holding the bus can trigger
   any key; convention says only the declaring manager does. Make the
   convention structural: give each manager a private publisher
   handle, for example `class Publisher<P : Any> internal constructor
   (private val bus: EventManager, private val key: StateKey<P>)`
   with the only public `fun publish(payload: P)`, created by the
   bus. `trigger` becomes internal to the core module at the module
   split; listeners are unaffected.
2. KONSIST RULES (a JVM test, like the shipped
   `WiringConventionsGuardTest`): every key object is declared in the
   same file as its publishing manager; every `eventName` matches
   `^[a-z]+\.[A-Z][A-Za-z]*$` and its namespace matches the manager;
   every `StateKey` payload is an immutable data class; no `var`
   properties in payloads.
3. GENERATED CATALOG: a small Gradle task (or KSP processor at the
   module split) that scans key declarations and emits `EVENTS.md`
   with name, payload type, lifetime, and publishing manager. The
   catalog is generated so it can never lie; hand-written event docs
   always do, eventually.

## 5. Lazy startup tiers

Adoption threshold: about 15 managers, or when a measured cold start
(macrobenchmark, not intuition) attributes real cost to
`AppComponent` construction. Today the component builds in
milliseconds and eager construction is a feature: everything is alive
before the first frame, and construction order is the whole
dependency story.

### Design

Tier the component's properties instead of introducing a framework:

- TIER 0, eager (never lazy): `eventManager`, `logManager`, the crash
  handler, `networkManager`. Infrastructure that everything else
  assumes is alive.
- TIER 1, first-frame: whatever the launch screen observes; keep
  eager until measurement says otherwise.
- TIER 2, on demand: `by lazy { ... }` property initializers in
  `AppComponent` for managers whose first use is behind navigation
  (settings, exports, rarely-visited features).

Rules that keep laziness honest: a lazy manager must not be one that
others subscribe to at startup (its keys publish nothing until first
touch; replay makes LATE subscribers safe, not late publishers);
`close()` must check `isInitialized` on lazy delegates and skip the
untouched ones; and tiers are recorded in the component's KDoc so the
next reader knows why a property is lazy. Resist DAG frameworks and
reflection: at template scale, `by lazy` on a plain class is the
entire mechanism.

## 6. Payload evolution rules

These are not deferred; they apply from the first key. They matter
most once payloads cross module boundaries or get persisted, but
following them from day one costs nothing.

1. Payloads are immutable data classes (val-only), owned by the
   publishing manager and declared beside it, like the key.
2. Changes are ADDITIVE: new fields get defaults, so every existing
   listener and every test keeps compiling and behaving. This is why
   `JokeState.joke` and `JokeState.failure` default to null.
3. Never repurpose a field: do not change a field's type or quietly
   change its meaning. A field whose semantics must change is a new
   field (with a default), and the old one is removed only after all
   listeners migrate.
4. A breaking change is a NEW KEY. Declare a new key object with a
   new eventName and its own payload type, publish both during the
   migration, delete the old key when its last listener is gone. Key
   objects are cheap; silent payload breaks are not.
5. The eventName string is part of the contract: logs, the future
   generated catalog, and any journaling all key on it. Renaming one
   is a breaking change; use rule 4.

## 7. Feature flag scaling

The shipped system (see ARCHITECTURE.md's "Feature flags" section) is
deliberately minimal: boolean flags, a no-op provider seam, and
debug-only persisted overrides, with release builds structurally
locked to compiled defaults. The designs below extend it.

### Vendor adapter

Adoption threshold: the FIRST flag that must be flipped remotely
(kill switch, staged rollout). Until then the no-op provider costs
nothing and the debug overrides cover local development.

The adapter is an edge class implementing `FeatureFlagProvider`,
built in BaseApplication like AndroidConnectivityMonitor and wired
through `AppConfig.featureFlagProvider`. Sketch, for Firebase Remote
Config:

```kotlin
class FirebaseFeatureFlagProvider : FeatureFlagProvider {

    override fun start(
        onFlagsUpdated: (Map<String, Boolean>) -> Unit,
    ) {
        val remoteConfig = Firebase.remoteConfig
        // 1. Push the last-fetched values immediately.
        onFlagsUpdated(remoteConfig.booleanFlagValues())
        // 2. Re-push after every successful fetch/activate cycle.
        remoteConfig.addOnConfigUpdateListener(...)
    }

    override fun stop() { /* remove the listener */ }
}
```

The manager needs no changes: providers only ever push full maps.
NOTE on lifetimes: `FeatureFlagsChanged` ships APP-lifetime because
the template's layers are device/build facts. A provider that targets
flags BY USER makes the snapshot user state: flip the key to SESSION
lifetime and re-resolve after `resetSessionReplayCaches()` during the
session teardown (section 1).

### Typed variants

Adoption threshold: the first non-boolean remote value (a string
message, a numeric limit). Add `StringFlag`/`IntFlag` siblings beside
`BooleanFlag` and widen the provider callback to a
`Map<String, Any?>`; resolution and precedence stay identical. Resist
JSON-payload flags until something genuinely structured is needed.

### Experiments and exposure logging

Adoption threshold: the first A/B test. An experiment is a flag plus
two obligations: STICKINESS (a user must not flap between variants;
resolve once per session and cache) and EXPOSURE (analytics must know
when a user actually HIT the gated code path). Add an
`exposureLogged` wrapper around `isEnabled` that fires an analytics
event on first read per session, and keep assignment server-side in
the provider. Do not build this speculatively; it doubles the flag
system's surface.

### Flag hygiene

Adoption threshold: about 15 flags. Flags are meant to be DELETED:
each one is a fork in the code that tests must cover twice. Extend
the registry guard with per-flag metadata (owner, introduced date)
and fail the build when a flag is older than a chosen ceiling
(e.g. 180 days) without an explicit `permanent = true` marker, which
is reserved for true kill switches.

## 8. Navigation 3 upgrade

Adoption threshold: about 15 destinations, the first nested or modal
flow (auth, checkout, a multi-step form), or a product requirement
for predictive-back previews or shared-element transitions. The typed
router itself is NOT deferred: destinations as data, per-tab stacks,
one deep-link function, and process-death restore ship in the
skeleton (navigation/), because retrofitting types onto stringly or
index-based navigation later means touching every screen. Like
payload evolution, the mechanism is day-one; only the LIBRARY has a
threshold.

The shipped router is deliberately Navigation 3-shaped: Nav 3's model
is an app-owned snapshot back stack of typed serializable keys that
the library renders. Migration recipe:

1. Destination types implement NavKey (they are already @Serializable
   data types; this is an interface change, not a remodel).
2. Each TabRouter's list becomes a rememberNavBackStack; push and pop
   stay list operations.
3. TabStackHost is replaced by NavDisplay with an entryProvider whose
   entries are the SAME exhaustive `when` branches.
4. The JSON Saver in AppNavigationState.kt is deleted; NavKey stacks
   handle saved state.
5. handleDeepLink and every AppRouterSpec test survive unchanged:
   they touch only destinations and stacks, never the renderer.

What the upgrade buys AT the threshold: predictive-back previews,
directional and shared-element transitions, and per-entry
ViewModelStore/lifecycle scoping. Before adopting, verify the library
is stable and its androidx train fits the pinned compileSdk/AGP (see
the warning in gradle/libs.versions.toml); until then the hand-rolled
router costs nothing to keep.

Deferred with it (do not hand-roll these early): nested graphs,
modal/bottom-sheet destinations as stack entries, per-destination
ViewModelStores, and directional push/pop transitions beyond the
shared content-swap motion. One accepted quirk of the keep-alive
shell plus stack host: popping to a root replays that page's one-shot
entrance animation, a deliberate simplicity trade recorded here so
nobody "fixes" it into complexity prematurely.

## 9. Non-Main event delivery

Adoption threshold: the first publisher with SUSTAINED high-rate
traffic, roughly tens of events per second (sync-pipeline progress,
BLE telemetry). Today every listenTo delivery dispatches on Main
(that is rule 3's contract for UI listeners), and managers hop the
callback onto their own confinement, so each event costs one
main-looper message plus one confinement dispatch per manager
listener. At human-scale traffic this is invisible; at tens of Hz it
competes with frame deadlines, and under Main congestion the
DROP_OLDEST buffers shed deliveries, which is harmless for
latest-wins state keys but silently loses SignalKey firings.

At the threshold, two moves, in order of preference:

1. MANAGERS COLLECT DIRECTLY. A manager that consumes a hot key
   collects `typedEventsOf(key)` on its own confinement
   (`managerScope.launch { eventManager.typedEventsOf<P>(key)
   .collect { ... } }`), which lands delivery straight on the
   confinement and REMOVES today's double dispatch; the collection
   dies with the scope at close(). UI listeners stay on listenTo and
   Main, untouched.
2. If the weak-owner/unsubscribeOwner bookkeeping must be kept for
   the hot subscriber, add one defaulted parameter instead:
   `listenTo(..., deliveryContext: CoroutineContext =
   EmptyCoroutineContext)` folded into the collector launch. Main
   stays the default; rules 2, 4, and 5 are unaffected (one
   sequential collector per subscription preserves per-key order,
   and the replay cache is written synchronously inside trigger).

Never solve a hot publisher with a bigger buffer: throttle or
conflate the latest-wins projection at the SOURCE, per the section 2
doctrine (the bus carries projections of durable state, not work).
