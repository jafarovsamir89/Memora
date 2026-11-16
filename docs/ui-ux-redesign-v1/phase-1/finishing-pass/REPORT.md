# Stage 1 finishing pass — ACT-0239

**Date:** 2026-11-16
**Branch:** `ui/redesign-v1`
**Stage 1:** complete after this pass. Stage 2/3 were not started.
**Commit:** `53628fd876ca2001c1ee18d5e0adecb1995e550e`, pushed to `origin/ui/redesign-v1`.

## Components and visual regression

Compared `ui/Components.kt` with the required UI/UX inventory. Kept and extended existing reusable implementations instead of adding parallel versions. The shared set now covers `PrimaryButton`, `SecondaryButton`, `VoiceInputButton`, `StatusBadge`, `ActionResultCard`, `PersonCard`, `CaseCard`, `EmptyState`, `ErrorState`, `LoadingState`, `ConfirmationSheet`, and `Timeline`. Components used on upcoming screens use fixed, synthetic presentation values in previews and the debug gallery; the gallery does not open the application database.

Added three Compose previews that collectively show the required components in Azerbaijani in light and dark themes, including long content at `fontScale=1.3`. Added a debug-only component gallery and automatic RGBA pixel comparison against 31 checked-in PNG baselines (23 light states and 8 dark states). The comparator rejects dimension mismatches, changed-pixel ratios over 0.3% at a per-channel threshold of 12/255, or mean RGB delta over 0.35/255. Normal Gradle runs compare; only explicit `--record` replaces baselines. Full details and command: [component visual regression](../COMPONENT_VISUAL_REGRESSION.md).

`ANDROID_SERIAL=emulator-5556 ./gradlew :app:componentVisualRegression --no-daemon --console=plain --max-workers=1` completed `BUILD SUCCESSFUL`; all **31/31** light/dark states passed. A one-off negative check changed a broad block in a temporary copy of `primary-light.png`: comparator correctly reported `FAIL` (13.092% changed pixels, mean RGB delta 21.171), while the unmodified dark baseline passed. Temporary mutated files were under `/tmp`, not the repository.

## Palette and large text

The earlier palette used warm off-white `#F8F7F4`, dark text `#252432`, and one muted purple `#6650B8` for both fill and action; the approved white concept specifies a cooler canvas, separate bright fill/action purples, and explicit blue/lavender/mint accents. Updated the shared light tokens to the specified `#F8F9FC` canvas, white surfaces, `#202238` primary text, `#8B6EF6` fill, `#6F4FE0` action purple, `#A6F3D0` mint, `#DCEBFF` blue and `#E9E1FF` lavender. The divider uses `#888EA0` to reach the requested 3:1 boundary contrast against the canvas. Dark palette follows the specified `#12131C`, `#1B1D2A`, `#ECEDF7`, and `#B3A0FF` values. Existing style remains recognizable, with a lighter page and white content surfaces.

At Azerbaijani `fontScale=1.3`, inspected Today, More and Secretary screenshots. The five bottom labels remain visible; More card headings/descriptions wrap and scroll instead of clipping; the long Secretary action label wraps inside its button. Added accessibility/large-text regressions for 48dp tap targets, navigation labels, and More card bounds. No real clipping was found, so no screen redesign was needed.

## Route, rotation, update, and onboarding

On a synthetic Android 14 emulator, seeded a synthetic case and related action card, opened the exact case from the Secretary result card, rotated to landscape, then used Android Back. The landscape view kept `Synthetic upgrade case` and `Synthetic case sentinel`; Back returned to the originating Secretary conversation with the same synthetic message/result visible. Evidence is in screenshots 03–06 below.

Built and installed the actual v91 APK, seeded markerless synthetic records, then upgraded to v93 with `adb install -r`. `firstInstallTime` did not change. The instrumentation readback passed for the action card and case, money event, person, profile fact, and task event; `markerless_existing_profile_preserved=true`. After visual tests, cleared **only the synthetic emulator's** app data and launched v93: onboarding appeared, confirming the P0 behavior still works. No physical Redmi was connected; no personal data, Gemini, microphone or network request was used.

## Tests and build

Full Gradle command:

```text
env -u MEMORA_RUN_LIVE_GEMINI -u MEMORA_LIVE_GEMINI_CONTINUE -u MEMORA_LIVE_GEMINI_START_STORY -u MEMORA_LIVE_GEMINI_MAX_TURNS JAVA_TOOL_OPTIONS='-XX:TieredStopAtLevel=1' ./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:compileDebugAndroidTestKotlin :app:lintDebug --no-daemon --console=plain --max-workers=1
```

Result: `BUILD SUCCESSFUL`, **75 JVM suites / 380 tests**, 0 failures/errors, 1 opt-in live test skipped; debug and AndroidTest APK builds and AndroidTest Kotlin compilation passed; lint 0 errors / 77 warnings / 3 hints. The separate component screenshot task passed 31/31 states as described above. `git diff --check` passed. No schema, ledger, domain, AI-provider or permission changes.

APK `ai.memora.app` versionCode **93**, versionName `1.1.71-ui-redesign-v1-polish`, SHA-256 `72d58775806f39421c129329642d490a637e5ddce28dc73fb4f09e6f66e301c3`.

## Real emulator screenshots

Seven direct `adb screencap` images (Azerbaijani UI; synthetic data only):

| # | Evidence |
|---|---|
| 1 | [Today at fontScale 1.3](screenshots/01-today-font-130.png) |
| 2 | [More cards at fontScale 1.3](screenshots/02-more-font-130.png) |
| 3 | [Secretary result and wrapped long action button at fontScale 1.3](screenshots/03-secretary-font-130.png) |
| 4 | [Exact case opened from the action card, portrait](screenshots/04-case-detail-portrait.png) |
| 5 | [Same exact case after rotation to landscape](screenshots/05-case-detail-landscape.png) |
| 6 | [Android Back returns to the originating Secretary route](screenshots/06-secretary-return-after-rotation.png) |
| 7 | [Clean v93 onboarding after synthetic data clear](screenshots/07-clean-onboarding-v93.png) |

## Boundaries and handoff

This closes the requested Stage 1 implementation pass on `ui/redesign-v1`; it does not claim physical-device/TalkBack/keyboard acceptance, broader M20-05, Gemini or release readiness. The existing e627ab3 separation plan remains in the parent Stage 1 report; no history rewrite or `main` change was made. The next authorized work is **none yet**: stop here and wait for the owner's separate Stage 2 approval.
