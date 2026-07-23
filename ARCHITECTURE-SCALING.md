# Architecture scaling guide

The template ships the smallest skeleton that scales: one module, one
composition root, a handful of peer managers on a typed event bus.
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
| Module split         | ~10 managers, or a second team              |
| Event governance     | the module split, or ~20 event keys         |
| Lazy startup tiers   | ~15 managers, or measured cold-start cost   |
| Payload evolution    | day one (rules, not a mechanism)            |

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
        appComponent.apiClient,
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

## 3. Module split recipe

Adoption threshold: about 10 managers, or the moment a second team
works in the repo. Below that, one module compiles fast enough and the
package tree is boundary enough.

### Target shape

```
:app                  composition root, Application, activity, DI
:core                 EventManager, key base classes, ConfinedManager,
                      LogManager, boundaries (ConnectivityMonitor,
                      CrashReporter, Clock usage), ApiClient
:feature-x-api        the feature's event keys, payload types, and
                      the manager's public interface
:feature-x-impl       the manager implementation; depends on
                      :feature-x-api and :core
```

### Recipe

1. Extract `:core` first: the `managers/` infrastructure
   (`EventManager`, `EventKey.kt`, `ConfinedManager`, `LogManager`),
   `api/` plumbing, and the boundary interfaces. `:app` keeps
   `AppComponent`, `AppConfig`, `AppModule`, and `BaseApplication`.
2. Split ONE feature as the exemplar, api/impl pair, before splitting
   the rest. The api module holds what other features may see: the
   key objects (still declared beside the manager's INTERFACE, same
   file or same package), the payload data classes, and the interface
   itself. The impl module holds the class.
3. Consumers (UI, other features) depend only on api modules; impl
   modules are wired solely in `:app`'s `AppComponent`, which now
   constructs interfaces from impls. Cross-feature choreography stays
   what it is today: subscribe to the other feature's key from its
   api module; never depend on another feature's impl.
4. Split the remaining features one at a time, letting dependency
   errors surface the accidental couplings the single module hid.

The composition root stays singular and manual: the module split
changes where types live, not how they are wired.

## 4. Event governance

Adoption threshold: the module split, or about 20 event keys,
whichever comes first. With 4 keys, grep is a catalog and review is
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
   `CompositionRootGuardTest`): every key object is declared in the
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
