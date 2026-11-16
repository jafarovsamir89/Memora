# Memora 2.0 UI/UX — Stage 3 and Today

**Checkpoint:** ACT-0243 · 2026-11-16
**Branch:** `ui/redesign-v1`
**APK:** v96 / `1.1.74-entity-today-polish`
**Status:** the scoped implementation block and checks below passed. The wider native/owner acceptance remains open.

## What changed

- Added exact-ID, active-scope detail routes for people, cases, finance records, tasks, case evidence and original conversation turns. Missing, deleted and inaccessible records have distinct safe states.
- Replaced the normal person-list dialog path with a full person page: verified aliases, summary, finance, cases, tasks, linked memory, calendar links and history. Existing owner-confirmation and unlink flows remain the only way to change identity links.
- Added a case page with participants, next action, status, timeline and exact evidence/object links. Status actions call the existing repository and refresh only after readback.
- Added finance details using canonical ledger events: direction, amount/currency, date, counterparty, remaining debt where supported, source, linked case/task and existing correction history. A financial person link is clickable only when an owner-confirmed identity link exists. No cancel, delete or new correction semantics were added.
- Added task and source pages that resolve the exact scoped task, evidence or conversation turn. Original messages and tool-result statuses stay distinct; only `APPLIED` is rendered as applied.
- Reworked Today around the shared Secretary voice control, compact priorities and real same-scope tasks/cases/calendar events. Outstanding debt cards use the existing ledger projection, preserve each event currency and open the exact finance ID. Completing a case refreshes the dashboard and removes the stale open-case card.
- Added read-only Room DAO queries; no schema, migration, domain rules, ledger, provider, permission, privacy, consent or auth changes.
- Added a debug-only screenshot activity backed by a fresh in-memory database of fictional records. It has no production database access and supports light/dark theme capture.

## Verification

- Fresh, no-build-cache JVM run: **77 suites / 393 tests; 0 failures, 0 errors, 1 opt-in live test skipped**. Live Gemini was disabled. This includes existing onboarding, route, action-result and source regressions.
- Debug APK v96 assembled. AndroidTest APK build, AndroidTest Kotlin compilation and lint passed after the final debug screenshot-host change.
- Lint: **0 errors / 82 warnings / 3 hints**.
- `:app:componentVisualRegression`: **31/31** existing light/dark component baselines passed with unchanged thresholds.
- Synthetic Android 14 emulator `emulator-5556`: v96 installed over v95 using `adb install -r`; `firstInstallTime` stayed `2026-11-15 16:15:34`; app launched. No app data was cleared.
- Real emulator screenshots show Today and the five synthetic detail routes in Azerbaijani UI. Main Today was captured at `fontScale=1.3`; the emulator setting was restored to 1.0. Person, case, finance, task and source screens were visually checked in light/dark and at `fontScale=1.3`; scrolling exposes content below the viewport.
- Component visual regression is automated; detail-screen ADB PNGs are evidence, not screenshot golden tests. No real physical device, TalkBack, live audio/Gemini, populated-profile APK upgrade readback or owner acceptance was performed in this block.

## Screenshots

All files are direct 1080×2400 `adb screencap` images from the synthetic Android 14 emulator. Detail screens use the debug in-memory fixture; Today uses the installed app's empty synthetic profile.

| Screen | Light | Dark | Large text |
|---|---|---|---|
| Today | NOT CAPTURED in light mode | [Today](screenshots/today-dark.png) | [Today, fontScale 1.3](screenshots/today-font-130.png) |
| Case | [Case](screenshots/case-light.png) | [Case](screenshots/case-dark.png) | inspected at 1.3×; capture under `/tmp/memora-stage3-font130/case.png` |
| Person | [Person](screenshots/person-light.png) | [Person](screenshots/person-dark.png) | inspected at 1.3×; capture under `/tmp/memora-stage3-font130/person.png` |
| Finance | [Finance](screenshots/finance-light.png) | [Finance](screenshots/finance-dark.png) | inspected at 1.3×; capture under `/tmp/memora-stage3-font130/finance.png` |
| Task | [Task](screenshots/task-light.png) | [Task](screenshots/task-dark.png) | inspected at 1.3×; capture under `/tmp/memora-stage3-font130/task.png` |
| Source | [Source](screenshots/source-light.png) | [Source](screenshots/source-dark.png) | inspected at 1.3×; capture under `/tmp/memora-stage3-font130/source.png` |

## Remaining acceptance

M20-03 and M20-05 remain partial. The detail screens need physical Redmi/keyboard/TalkBack and owner acceptance; a markerless populated profile upgrade must be read back through the existing synthetic instrumentation path; keyboard behavior and process-death restoration on the redesigned screens remain open. Memory and the remaining More sections are not part of this block and remain next in the autonomous UI sequence. No claim of production readiness is made.

APK SHA-256: `b445f8ce7919af58befe49c3b967cdd1dad1bea823809b94ce797f4157fbcb7e`.
