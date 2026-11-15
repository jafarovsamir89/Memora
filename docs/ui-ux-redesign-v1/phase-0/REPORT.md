# Memora 2.0 UI/UX redesign V1 — Stage 0 audit

**Status:** Stage 0 complete; waiting for owner approval before Stage 1.
**Branch/base:** `ui/redesign-v1` at `e627ab3` (`fix(android): anchor task dates and navigation`).
**Method:** source and handler review, clean synthetic Android 14 profile, 8 actual ADB screenshots, manual route checks, full pre-change Gradle baseline. No runtime code was changed.

## Findings

| Priority | Finding | Evidence and practical effect |
|---|---|---|
| P0 | Fresh install skipped onboarding | A clean profile opened Today immediately. The profile state requires onboarding for `InstallDisposition.NEW`, but startup notification reconciliation opens the agent DB before Compose; the install marker then appears `complete`. This is consistent with a first-run initialization race. The exact root cause is not proven; no fix was made. No cloud access or data disclosure was observed. |
| P1 | Two different voice/capture paths look alike | Today and Secretary expose the cloud AI assistant alongside a separate capture sheet for saving a note. Their labels do not make “ask Memora” versus “save a note” sufficiently distinct. See `MemoraApp.kt`, `WorkingScreens.kt`, `VoiceFirstScreen.kt`, `Sheets.kt`, `AgentChatScreen.kt`. |
| P1 | More directory is below a long report | On the actual More screenshot the finance report and explanatory copy occupy the first viewport; most section links require scrolling. This reduces discovery of people, tasks, projects and settings. |
| P1 | Action result navigation drops exact-object context | Action cards route to a generic section for “open related”; the target ID is not consistently used to open that exact person, case or other object. A correction draft is the narrow exception. |
| P1 | Tasks combines two stores and interaction models | The Tasks destination presents protected assistant tasks beside a separately stored prototype task list with different CRUD behavior. The prototype source marks this as test-only. This can confuse users about which task list is authoritative. |
| P1 | Component boundary contrast is weak | Theme line/surface contrast is about 1.24:1 light and 1.29:1 dark, below 3:1 for meaningful control boundaries. Body and primary text contrast exceed 4.5:1. Recheck token pairs and focus/error states in Stage 1. |
| P2 | Nested person details have inconsistent system Back | Details are stacked inside one dialog; the in-dialog Back pops a detail, while Android Back dismisses the dialog. The dismiss path does not consistently clear `selectedTask`, which may leave stale detail state. Static finding; populated detail flow was not exercised. |
| P2 | Some failure states have no in-place retry | Cases and saved-object load failures render an error empty state without a retry action; Home/Finance also lack a local retry control. Calendar has refresh. |
| P2 | Cases filters do not reveal horizontal overflow | Five status chips share a horizontal row; the last option can sit off-screen without an obvious scroll cue. |

**Accessibility positives:** screen headings expose heading semantics; voice control has a spoken label and button role; settings model options expose radio semantics; primary chips meet the existing 48 dp minimum. These are source observations, not TalkBack acceptance. The color-boundary issue and all interaction semantics need device validation.

## Navigation options

| Criterion | A — Navigation Compose, typed routes | B — evolve current navigation (recommended) |
|---|---|---|
| Project risk / change size | Higher now: Navigation Compose is not an existing dependency; broad route, state and back-stack migration. | Lower: preserve `rememberSaveable` route/back-stack and `SaveableStateHolder`; improve them incrementally. |
| State restoration | Strong built-in destination/back-stack support when wired correctly. | Existing route/list state already saves; typed route reducer and nested-state rules must be tested explicitly. |
| Exact object links / deep links | First-class typed destination patterns, but requires route migration and argument policy. | Add typed route arguments and exact-object destinations gradually; deep-link dispatch remains app-owned. |
| Compose fit / tests | Native Compose ecosystem fit; destination-level tests available after migration. | Fits current Compose implementation and existing tests with minimal dependency churn. |
| Incremental adoption | Possible, but running two navigation systems risks transitional complexity. | Straightforward: introduce sealed route/argument types, centralize transitions, then add object detail routes. |

**Recommendation:** B for this redesign. First define typed routes and one navigation reducer around the current shell, preserve root-tab behavior, and add exact object IDs to detail destinations. This limits risk to the working local-data flows while addressing the audit’s context-loss issue. Deep links and process-death restoration remain explicit test obligations. Do not implement until Stage 0 is approved.

## Screens and evidence

| Screen | Actual screenshot |
|---|---|
| Today | [01-today.png](screenshots/01-today.png) |
| Secretary | [02-secretary.png](screenshots/02-secretary.png) |
| Cases | [03-cases.png](screenshots/03-cases.png) |
| Memory | [04-memory.png](screenshots/04-memory.png) |
| More | [05-more.png](screenshots/05-more.png) |
| Settings | [06-settings.png](screenshots/06-settings.png) |
| People | [07-people.png](screenshots/07-people.png) |
| Tables | [08-tables.png](screenshots/08-tables.png) |

All eight are direct `adb shell screencap` PNGs from `ai.memora.app` v90 / `1.1.68-date-anchor`, clean synthetic Android 14/API 34 emulator, 1080×2400, Azerbaijani UI. They show empty/test-only screens; no personal records were opened or created. Manual checks: More→Settings→Android Back restores More; Cases→Secretary→Android Back restores Cases. No microphone, Gemini, network AI, or real data was used.

## Baseline and limits

Full command: `:app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:compileDebugAndroidTestKotlin :app:lintDebug` with the opt-in live Gemini variables unset. **BUILD SUCCESSFUL:** 72 suites / 366 JVM tests, 0 failures/errors, 1 opt-in live test skipped; debug and AndroidTest APK builds and AndroidTest Kotlin compilation passed; lint 0 errors / 75 warnings / 3 hints. No instrumentation test execution was performed. Debug APK SHA-256: `23f8dc2df571bb7ec99606c31449b55b8c2060b9290a54d7f502df5c09afb073`.

Inventory in [interaction-inventory.json](interaction-inventory.json) covers routes, controls, handler effects, route/data context, Back, loading/error/empty/permission states, accessibility evidence, and verification limits. Audit is source-backed and visually checked against an empty synthetic profile; populated linked-object journeys, TalkBack, keyboard, large font, dark theme, physical Redmi and owner acceptance remain unverified. No data migration, product-rule, AI-provider or domain changes were made.

**Next:** wait for the owner to say **“Да, переходи к следующему этапу”**. Stage 1 recommendation is navigation option B and a token/component/accessibility package. Stage 2 and Stage 3 remain out of scope until separately approved.
