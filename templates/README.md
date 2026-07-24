# :templates, the living documentation

Fully-commented exemplar files showing how to build each kind of
component this codebase uses. They COMPILE against `:core` and `:ui`
on every build and `TemplateManagerSpec` runs green in CI, so unlike
a docs folder they can never rot; but no module depends on
`:templates`, so they ship in nothing.

## How to use a template

1. Copy the file into the real module and package its banner names
   (each file's banner states the destination).
2. Rename `Template` to your feature or boundary name.
3. Follow the numbered STEP comments, then DELETE every banner and
   STEP comment: the copied result should read like the real
   exemplars (JokeManager, HomePage), whose comments state only what
   the code cannot.
4. Add the wiring lines each banner lists (AppComponent, AppModule,
   AppConfig, AppFlags) in the same PR.

## The templates

| File | Teaches | Real home |
| --- | --- | --- |
| `managers/templateManager/TemplateManager.kt` | keys and flags beside the manager, ConfinedManager rails, the init budget, start(), confined state, choreography, publishing, close() | `core/.../managers/<feature>Manager/` |
| `managers/templateManager/TemplatePort.kt` | naming and shaping a hexagonal port, the AppConfig seam | `core/.../managers/<feature>Manager/` |
| `platform/TemplateAdapter.kt` | logic-free SDK adapters, instance-owned state | `app/.../platform/` |
| `viewModels/TemplateViewModel.kt` | thin write-only viewmodels, what does NOT belong in one | `ui/.../viewModels/` |
| `views/TemplatePage.kt` | bus observation, stateless previewable content, semantic navigation lambdas | `ui/.../views/` |
| `navigation/TemplateDestination.kt` | destinations as data plus the three wiring points (stack host branch, deep link, router spec) | `ui/.../navigation/AppDestinations.kt` |
| `src/test/.../testkit/FakeTemplatePort.kt` | hand-written fakes with driving methods | `core/src/testFixtures/.../testkit/` |
| `src/test/.../managers/TemplateManagerSpec.kt` | the direct-construction spec shape (vs TestAppContext component specs) | beside the copied manager's module tests |
| `src/androidTest/.../flows/TemplateFlowTest.kt` | semantics-first instrumented tests; the banner shows the real drive-the-app shape | `app/src/androidTest/.../flows/` |

## Rules this module lives by

- Nothing may depend on `:templates`; it exists to be read and copied.
- Templates share the app's real package names on purpose, so a copy
  needs no import surgery and same-package helpers (eventState)
  resolve exactly as they will at the destination.
- This module is deliberately OUTSIDE the guard tests'
  GUARDED_MODULES, so template keys and flags never pollute the real
  registries; the moment you copy a flag out, register it in
  AppFlags.all or the registry guard fails, as it should.
- A convention change lands WITH its template update in the same PR;
  a template that contradicts the real exemplars is a bug.
