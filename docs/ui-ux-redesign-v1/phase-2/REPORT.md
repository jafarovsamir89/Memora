# UI/UX Redesign V1 — Stage 2: Secretary

**Date:** 2026-11-16
**Branch:** `ui/redesign-v1`
**Build:** v94 / `1.1.72-secretary-conversation`
**Status:** implementation and host verification complete; network, physical-device, TalkBack, and populated-profile update acceptance remain open.

## What changed

- Reworked the Secretary into a compact header, encrypted-memory/cloud-consent controls, reverse chronological conversation, and a fixed composer that remains above the IME. Draft text and the lazy-list position are saveable; loading history no longer jumps away from a restored position.
- Added an Activity-scoped turn coordinator so a text or voice request can finish across route changes and configuration recreation. It exposes real runtime stages, keeps a confirmed action visible before the final model response, and restores the draft after a failed request. A bounded timeout prevents a stuck busy state. No new conversation or domain store was added.
- Connected visible thinking/tool/readback progress to the existing agent runtime. Action cards use the existing scoped repositories for read-only summaries and stable object routes. Correction remains an explicit draft through the existing correction flow; this UI does not edit or delete ledger rows.
- Unified the Secretary and Today microphone control: hold to record, release to process, short tap focuses text, and TalkBack can toggle recording with an accessible button action. Cancellation, loss of focus, denied permission, STT/model/TTS failure, and timeout have distinct UI states. The screen continues to use the existing voice pipeline and consent settings.
- Added an explicit memory-consent revocation explanation. Revocation affects subsequent cloud requests; local records remain under the existing storage policy.
- Added privacy-safe release-to-next-frame timing. It logs only the elapsed milliseconds, never audio or transcript. No network-stage timing was fabricated.
- Added an optional NumPy fast path for component pixel comparison. It keeps the same 12/255 changed-pixel and mean-delta thresholds and falls back to the dependency-free comparator when NumPy is unavailable.

No database schema, migration, ledger, domain behavior, AI/STT/TTS provider, model, permission, or authentication changes were made.

## Verification

Full command:

```text
env -u MEMORA_RUN_LIVE_GEMINI -u MEMORA_LIVE_GEMINI_CONTINUE -u MEMORA_LIVE_GEMINI_START_STORY -u MEMORA_LIVE_GEMINI_MAX_TURNS JAVA_TOOL_OPTIONS='-XX:TieredStopAtLevel=1' ./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:compileDebugAndroidTestKotlin :app:lintDebug --no-daemon --console=plain --max-workers=1
```

Result: **BUILD SUCCESSFUL**, 76 JVM suites / 388 tests, 0 failures, 0 errors, 1 opt-in live test skipped; debug and AndroidTest APKs built; AndroidTest Kotlin compilation passed; lint 0 errors / 80 warnings / 3 hints. The existing test suites were retained.

`ANDROID_SERIAL=emulator-5556 ./gradlew :app:componentVisualRegression` passed **31/31** component states before the comparator optimization. After the optimization, the same emulator regression path passed both primary light/dark cases; deterministic RGBA fixtures showed exact parity with the fallback, including the 12/255 threshold boundary. The thresholds and checked-in baselines were not changed.

Updated v93→v94 with `adb install -r` on the synthetic Android 14 emulator and launched `MainActivity`. `firstInstallTime` remained `2026-11-15 16:15:34`; font scale was restored to 1.0 and rotation to portrait. Existing P0 onboarding regressions remain in the full test suite; no onboarding code changed in this stage.

## Screenshots

These are direct ADB screenshots of the running emulator, in Azerbaijani. The empty-history captures show no user messages; no personal records were opened.

| Screen/state | Evidence |
|---|---|
| Secretary, light theme, empty history | [secretary-empty-az-light.png](screenshots/secretary-empty-az-light.png) |
| Secretary with keyboard open; composer remains visible | [secretary-keyboard-az-light.png](screenshots/secretary-keyboard-az-light.png) |
| Secretary in landscape | [secretary-keyboard-landscape.png](screenshots/secretary-keyboard-landscape.png) |
| Secretary, dark theme | [secretary-final-install-az-dark.png](screenshots/secretary-final-install-az-dark.png) |
| Secretary at `fontScale=1.3` | [secretary-az-font-scale-1_3.png](screenshots/secretary-az-font-scale-1_3.png) |

## Not tested / blocked

- **NOT TESTED:** real microphone, live STT/model/tool/readback/TTS timings, network p50/p95, or voice quality. No Gemini or paid API request was made. Only local release-to-frame instrumentation was added.
- **NOT TESTED:** physical Redmi, TalkBack, and owner acceptance.
- **PARTIAL:** the v93→v94 `adb install -r` update and unchanged `firstInstallTime` were confirmed, but the isolated lineage seeder refused because the existing install marker was present. It wrote no fixture and no app data was cleared. A fresh Android 14 AVD could not be provisioned because the emulator requires 7.37 GB free while the host had about 5 GB. An existing API 26 AVD was booted only to read package-version metadata, then stopped; no app install or app data operation was performed there. Therefore full populated synthetic-profile readback for this exact update remains open. The prior v91→v93 six-type synthetic retention check from Stage 1 remains valid.
- **NOT TESTED:** a real device screenshot of populated action cards. Synthetic action-card, route, retry/error, and progress behavior are covered by Compose/host tests; component cards also remain covered by the synthetic component gallery.

APK: `ai.memora.app` v94 / `1.1.72-secretary-conversation`, SHA-256 `3f28157a92517889dc5fd069269b8367b92e8704f16fda9ad87aea696590f402`.

Stage 3 is authorized by the owner and proceeds next; this report does not claim the overall redesign or release is complete.
