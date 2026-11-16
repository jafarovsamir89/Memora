# Phase 6 — Calendar and Tasks (ACT-0249)

Implemented on `ui/redesign-v1` under the owner's active autonomous UI/UX authorization.

## What changed

- Calendar now has localized Upcoming, Past, Cancelled, and All filters; a visible result count; refresh/retry handling; clear loading, storage-error, empty, and filter-empty states; and a lazy event list. The screen caps visible history at the 200 latest events from the existing scoped query. Event dates and time zones are formatted for the selected UI locale.
- Each event opens its exact typed calendar route using the stable event ID and current scope. The details screen shows the localized event status, formatted time, and time zone.
- The empty-state component gains an optional compact horizontal layout. Calendar uses it on short-height screens and hides its explanatory subtitle there, so the empty-state title and body remain visible in landscape above the bottom navigation.
- Tasks is now a full-screen lazy list with filters and separate, clearly labeled sections for assistant tasks in the protected store and legacy tasks saved on this device. Existing create/edit/complete/delete/export behavior remains on its prior stores and rules. Assistant tasks use the existing task completion/write/readback path and exact ID detail route.
- Task title draft and selected filter survive route restoration and Activity recreation. Due dates follow the selected UI locale. Long Azerbaijani labels wrap instead of clipping.
- Added Azerbaijani, Russian, and English copy for the new states and labels.

No database schema or migrations, ledger, domain rules, AI provider/model, consent, privacy, authentication, or permissions changed. No test fixture was written to the app's working calendar store; the one temporary UI task used for device interaction was deleted and the empty state was confirmed afterward.

## Verification

- Full command passed: `:app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:compileDebugAndroidTestKotlin :app:lintDebug :app:componentVisualRegression`.
- JVM suite: **78 suites / 400 tests; 0 failures, 0 errors, 1 opt-in live test skipped**.
- Debug and AndroidTest APK builds and AndroidTest Kotlin compilation: **PASS**.
- Lint: **0 errors / 89 warnings / 2 hints**.
- Component screenshot regression: **31/31 PASS**. Existing thresholds and approved baselines were not changed.
- Synthetic Compose tests cover calendar filtering, exclusion of a foreign scope, exact event route/detail resolution, and a landscape empty state with top/bottom navigation space. Task tests cover the existing local task flow and exact assistant-task route.
- Real Android 14 emulator checks confirmed the task screen, local synthetic task card, completion/readback state, completed filter, deletion back to an empty list, keyboard visibility, AZ `fontScale=1.3`, and draft preservation through rotation. The landscape Calendar screenshot confirms both empty-state texts remain visible.
- APK v100 `1.1.78-calendar-tasks-ui`, SHA-256 `ed0a9f4746b3bc0f57f17d5300c4fe9f632829d1d8782a8287efad4be261f44f`. Gradle `installDebug` and an explicit `adb install -r` updated the synthetic emulator; `firstInstallTime` remained `2026-11-15 16:15:34`, and MainActivity launched. No app data was cleared. The device calendar had no events; populated calendar behavior is verified only with isolated synthetic in-memory Compose tests.
- Emulator settings were restored to `fontScale=1.0`, portrait, and the prior dark theme. No personal records were inspected and no live AI/audio/API request was made.
- `git diff --check`: **PASS**.

## Real emulator screenshots

All images are unaltered `adb screencap` PNGs from synthetic Android 14 emulator `emulator-5556` (portrait 1080×2400, landscape 2400×1080).

| Evidence | Screenshot |
| --- | --- |
| Calendar empty state, dark | [01-calendar-empty-dark.png](screenshots/01-calendar-empty-dark.png) |
| Calendar empty state, light | [02-calendar-empty-light.png](screenshots/02-calendar-empty-light.png) |
| Calendar at AZ `fontScale=1.3`, dark | [03-calendar-fontscale-1-3-dark.png](screenshots/03-calendar-fontscale-1-3-dark.png) |
| Calendar compact empty state in landscape | [04-calendar-landscape-compact-dark.png](screenshots/04-calendar-landscape-compact-dark.png) |
| Tasks empty state, dark/light | [05-tasks-empty-dark.png](screenshots/05-tasks-empty-dark.png), [06-tasks-empty-light.png](screenshots/06-tasks-empty-light.png) |
| Tasks at AZ `fontScale=1.3`, dark/light | [07-tasks-fontscale-1-3-dark.png](screenshots/07-tasks-fontscale-1-3-dark.png), [08-tasks-fontscale-1-3-light.png](screenshots/08-tasks-fontscale-1-3-light.png) |
| Synthetic task card, completion/readback, and completed filter | [09-task-synthetic-record-dark.png](screenshots/09-task-synthetic-record-dark.png), [10-task-completed-readback-dark.png](screenshots/10-task-completed-readback-dark.png), [11-task-completed-filter-dark.png](screenshots/11-task-completed-filter-dark.png) |
| Task input with keyboard | [12-task-keyboard-light.png](screenshots/12-task-keyboard-light.png) |
| Task draft after landscape rotation | [13-task-draft-rotation-landscape-light.png](screenshots/13-task-draft-rotation-landscape-light.png) |

## Limits

Physical Redmi, TalkBack, hardware keyboard, populated calendar records on device, populated-profile data-field readback after APK update, large-profile performance, and owner visual acceptance remain **NOT TESTED**. The repository currently materializes the scoped calendar rows before the UI applies its 200-row cap, so very large calendar performance still needs a paged-query follow-up. The emulator's unchanged `firstInstallTime` confirms an in-place install; it does not substitute for a populated-profile migration/readback check. M20-05 and native/release readiness remain partial. Continue with Projects/Notes, then Tables, Inbox, Backup, Settings, and the authorized final visual pass.
