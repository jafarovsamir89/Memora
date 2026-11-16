# Memora 2.0 UI/UX Redesign V1 — Stage 1

**Status:** Stage 1 and the requested finishing pass ACT-0239 are complete. Stage 2 and Stage 3 have not started; stop here for separate owner approval.
**Branch:** `ui/redesign-v1`. `main` was not checked out or changed in this task.
**Finishing pass commit:** `53628fd876ca2001c1ee18d5e0adecb1995e550e`, pushed to `origin/ui/redesign-v1`.
**Prior approval:** the owner approved the Stage 0 audit and selected navigation option B, then explicitly authorized Stage 1.

The component inventory, screenshot comparison, `fontScale=1.3`, exact-ID route, rotation/back-stack, and v91→v93 update checks added after the original Stage 1 delivery are documented in the [finishing pass report](finishing-pass/REPORT.md). That report supersedes the original verification snapshot below where it provides newer test/build evidence.

## Result

- Established shared white, purple and mint semantic tokens, typography, spacing and shapes. Added light and dark Material 3 palettes, selected/unselected system-bar handling, and contrast assertions for primary/muted text, interactive colors and meaningful outlines.
- Built reusable cards, section headings, icon bubbles, pills, menu rows, a shared top bar, and the five-item bottom navigation.
- Added five primary tabs: Today, Secretary, Cases, Memory and More. Evolved the existing navigation instead of introducing a second navigation framework: typed destinations, UUID-backed action targets, a single route reducer, Android Back/toolbar Back, saveable route history and per-route Compose state.
- Action result cards now navigate to the exact supported saved object ID. The new read-only detail screen resolves only within the active conversation scope; it does not change source records. Invalid/unknown IDs do not become generic routes.
- Reworked More into a two-column section directory, placed above the finance report, and included Settings and the existing capture/privacy entry points.
- Kept Today, Secretary and Memory screen layouts intact; their shell, shared tokens and navigation were updated, but those screens were not wholesale redesigned. No domain behavior, finance ledger, database schema, AI provider, or Android permission changed.
- Rechecked the P0 onboarding fix on a clean synthetic install: v92 showed onboarding, completing it opened Today, and a force-stop/relaunch returned to Today. The prior P0 markerless populated-database update tests remain in ACT-0236.

## Tests and device evidence

Full command:

```text
JAVA_TOOL_OPTIONS='-XX:TieredStopAtLevel=1' ./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:compileDebugAndroidTestKotlin :app:lintDebug --no-daemon --console=plain --max-workers=1
```

**BUILD SUCCESSFUL:** 74 suites / 377 tests, 0 failures, 0 errors, 1 opt-in live test skipped. Debug and AndroidTest APKs built; AndroidTest Kotlin compiled. Lint reported 0 errors, 77 warnings and 3 hints. Debug APK v92 / `1.1.70-ui-redesign-v1`, SHA-256 `42951f3ed65e498a07ba4524b4902df2b812586f157fadef6d27edb017d2285e`.

Added route tests for UUID round-trip and rejection, tab anchoring, back-stack order, route-state save/restore, and tab-root selection. Compose regressions cover all five tabs, directory-before-reports semantics, and exact scoped case/finance targets. Contrast tests assert WCAG AA 4.5:1 for body/secondary text and interactive labels, and 3:1 for boundaries. The complete existing JVM suite remains in the run. A pre-existing asynchronous table-history assertion was made deterministic by waiting for its loaded row before interaction; the final full suite passed.

Installed the debug APK on a synthetic Android 14/API 34 emulator, not the physical Redmi. Manually checked fresh-install onboarding, complete-and-relaunch behavior, Cases→Secretary→Android Back→Cases, and More→Settings→Android Back with the More list's prior scroll position restored. Light and dark appearance were captured; the directory is visibly above the finance report. No personal database, Gemini request, microphone, or external service was used. Instrumentation tests were built/compiled but not executed on the device. TalkBack, keyboard, physical-device and owner visual acceptance remain open.

## Screenshots

These are eight direct `adb screencap` PNGs (1080×2400) from the installed v92 synthetic emulator, Azerbaijani UI. Onboarding shows the first-run screen. The remaining images show the app screens and both theme modes.

| # | Evidence |
|---|---|
| 1 | [Clean-install onboarding](screenshots/01-onboarding-first-launch.png) |
| 2 | [Today — light](screenshots/02-today-light.png) |
| 3 | [Secretary — light](screenshots/03-secretary-light.png) |
| 4 | [Cases — light](screenshots/04-cases-light.png) |
| 5 | [Memory — light](screenshots/05-memory-light.png) |
| 6 | [More section directory — light](screenshots/06-more-catalog-light.png) |
| 7 | [Settings — dark](screenshots/07-settings-dark.png) |
| 8 | [More finance report below the directory — dark](screenshots/08-more-finance-report-dark.png) |

## `e627ab3` separation for future integration

`e627ab3` (`fix(android): anchor task dates and navigation`) is an ancestor of this feature branch and mixes UI navigation/localized labels with task-date correctness work. The date/domain work is unrelated to this UI redesign and must not be attributed to it:

- Runtime date behavior: `AgentDateContext.kt`, `AgentRuntime.kt`, `GeminiAgentModelGateway.kt`, `LocalMemoryTools.kt`, `AgentToolCatalog.kt`, `AgentVoiceTurnProcessor.kt`.
- Date/domain regression coverage: `AgentDateContextTest.kt`, and date-related hunks in `AgentFakeProviderUnifiedMemoryTest.kt`, `AgentRuntimeTest.kt`, `AgentToolCatalogTest.kt`.
- Documentation hunks in `AGENTS.md`, `README.md`, `TODO.md` and `ENGINEERING_PLAN.md` describe the date fix and should be replayed selectively, not imported wholesale as UI work.

For a future integration, start a separate integration branch from the intended target `main`; do not merge this feature branch wholesale while the target lacks `e627ab3`, and do not cherry-pick the full mixed commit. Transfer only reviewed UI hunks from `e627ab3` (navigation, screen labels and displayed month formatting) where the target needs them; leave the date/domain paths above out unless separately approved as their own change. Then apply the P0 and Stage 1 implementation commits as reviewed patches/cherry-picks, resolve shared-file dependencies explicitly, and verify the resulting diff contains no date-domain hunks. This preserves history and does not rewrite `e627ab3`.

## Files changed in Stage 1

- App: `apps/android/app/build.gradle.kts`; `AgentChatScreen.kt`; new `ActionTargetDetailScreen.kt` and `AppRoute.kt`; `MemoraApp.kt`; `Screens.kt`; `ui/Components.kt`; `ui/Theme.kt`; default/AZ/EN/RU `strings.xml`.
- Tests: new `AppRouteTest.kt` and `ui/ThemeContrastTest.kt`; `UiShellTest.kt`; `TablesScreenTest.kt`.
- Project state/evidence: `AGENTS.md`, `README.md`, `TODO.md`, `docs/ENGINEERING_PLAN.md`, `docs/MEMORA_2_0_PRODUCT_SPEC.md`, this report, `docs/AGENT_DIARY.md`, `docs/ACTION_LOG.md`, and eight screenshots in `screenshots/`.
