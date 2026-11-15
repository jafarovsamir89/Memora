# Memora 2.0 UI/UX redesign V1 — P0 onboarding fix

**Task:** ACT-0236 · **Date:** 2026-11-15 · **Branch:** `ui/redesign-v1`
**Status:** P0 fixed and verified on a synthetic Android 14 emulator. Stage 1 has not started.

## Finding and proof

The suspected first-run race was reproduced. `MainActivity.onCreate()` starts `AttentionNotifications.reconcileFromApp()` before Compose. Reconciliation schedules background work that opens `AgentDatabase` before checking notification permission; Room can therefore create `memora-agent.db` on a genuinely fresh install. `LocalInstallMarker.hasLegacyState()` previously treated that filename alone as evidence of an older installation. The marker was consequently written as `existing`. With no profile row in the empty current-schema database, `LocalProfileViewModel` followed the legacy path, created a default completed profile, and showed Today instead of onboarding.

The regression test `notificationDatabaseCreationBeforeProfileInspectionKeepsFreshInstallNew` first creates the database file, then inspects the marker. Before the fix it failed: the observed disposition was `EXISTING`, expected `NEW`. After the fix it passes. A second ViewModel test follows the same order through onboarding completion and a subsequent launch.

The emulator probe also exercised the real startup path. On an uninstalled, clean synthetic emulator, the opt-in AndroidTest called production `AttentionNotifications.reconcileFromApp()`, waited for the database file to appear, and verified the install marker was still absent. MainActivity was then launched; the screenshot shows onboarding and the resulting marker was `new`. This confirms the precise initializer-before-Compose ordering, rather than only a simulated file condition.

## Change

`LocalInstallMarker` no longer uses the database filename as an installation-history signal. It continues to recognize known legacy sidecars. After Room opens, a saved local profile identifies an existing installation and is preserved; a clean database with no profile shows onboarding. This leaves Room schema, migrations, ledger, domain tools, and user data structures unchanged.

The synthetic AndroidTest probe is explicitly opt-in, requires the debug package and emulator environment, and seeds only synthetic profile, fact, case, person, finance, and task rows. It checks these rows after APK update. The test seam for injecting an install marker is internal and defaults to the production marker.

## Verification

| Check | Result |
|---|---|
| Regression before fix | Failed as expected: pre-created DB file yielded `EXISTING` instead of `NEW`. |
| Regression after fix | Passed. Full unit report: 72 suites / 368 tests, 0 failures/errors, 1 opt-in live test skipped. |
| Full Gradle verification | `:app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:compileDebugAndroidTestKotlin :app:lintDebug` — `BUILD SUCCESSFUL`; lint 0 errors / 76 warnings / 3 hints. The final emulator-probe adjustment was AndroidTest-only and was followed by successful AndroidTest APK build and Kotlin compilation. |
| Clean install | v91 installed as a fresh app; first launch showed the Azerbaijani onboarding screen. Completed with default synthetic choices; the app opened Today. After force-stop and relaunch, it opened Today again. Marker value: `complete`. |
| Notification-before-UI race | Production reconciliation created the DB before MainActivity; no marker existed before UI; MainActivity showed onboarding; afterward the marker was `new`. |
| Markerless populated update | Seeded five synthetic data categories on v90 with no install marker, updated in place to v91 using `adb install -r`, and verified all profile/fact/case/person/finance/task sentinels. `firstInstallTime` did not change. The migrated profile was recognized and marker repaired. |
| Old markerless profile unit case | A saved profile with no marker and a present DB file was loaded unchanged, was not auto-completed, and marked `EXISTING`. |
| Boundaries | Synthetic Android 14 emulator only; no physical Redmi, personal records, Gemini, network, or microphone. |

Debug APK: v91 `1.1.69-onboarding-marker-race`, SHA-256 `8dbe49ad9bb702220107f1fa563617c4a4bf6bae66ecb16b1e2ac7354cafda9e`.

## First-run evidence

These are direct 1080×2400 emulator screenshots from the app, not mockups:

1. [Clean first launch: onboarding](screenshots/01-first-launch-onboarding.png)
2. [Repeat launch after completing onboarding: Today](screenshots/02-repeat-launch-after-onboarding.png)
3. [Onboarding after notification reconciliation created the DB before UI startup](screenshots/03-notification-db-before-ui.png)

## Mixed commit `e627ab3`

The branch base commit `e627ab3` (`fix(android): anchor task dates and navigation`) combines UI work with an unrelated date correctness fix. The UI-related paths are `MemoraApp.kt` (navigation/back stack), `Screens.kt` (Settings layout), `FinanceScreen.kt` (localized report month), and localized navigation strings. The non-redesign runtime changes are `AgentDateContext.kt`, `AgentRuntime.kt`, `AgentToolCatalog.kt`, `GeminiAgentModelGateway.kt`, `LocalMemoryTools.kt`, and `AgentVoiceTurnProcessor.kt`, plus the related agent/date tests. They add current-date context and deterministic date validation for task tools. `app/build.gradle.kts` only carries the combined package version bump. `AGENTS.md`, `README.md`, `TODO.md`, `ACTION_LOG.md`, `AGENT_DIARY.md`, and `ENGINEERING_PLAN.md` record the mixed project checkpoint and are not isolated redesign implementation.

Do not cherry-pick or merge the whole mixed commit when only the redesign is wanted. For a future integration, start a fresh branch from the then-current `origin/main`, review the path-scoped diff `git diff e627ab3^ e627ab3 -- <UI paths>`, and apply only the approved UI hunks as a new commit. Cherry-pick the dedicated P0 commit separately, resolving the version metadata hunk against the current base if needed. This keeps history intact and avoids rewriting `e627ab3`; no Git history was rewritten for this task.

## Stop point

P0 is complete. The recommended Stage 0 navigation direction (option B) remains only preliminarily approved. Stage 1 has not begun and must wait for the owner's next explicit authorization.
