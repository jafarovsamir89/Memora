# Inbox UI checkpoint — ACT-0255

**Date:** 2026-11-16
**Branch:** `ui/redesign-v1`
**Status:** implementation checkpoint committed at the owner's stop request; visual and device acceptance remain incomplete.

## Implemented

- Replaced the long `ScreenColumn` inbox with a compact header and keyed `LazyColumn`, keeping the active/archived/all filters, search, batch archive/restore, editing, revision history, delete confirmation, and existing import/export callbacks.
- Kept selection and search/filter state saveable, keyed each row by its existing source ID, retained the prior index-based test tags, and preserved the same encrypted capture store and mutation callbacks.
- Made the new-note action easy to reach; transfer actions stay in the list footer. Multi-action areas wrap to accommodate Azerbaijani labels and larger text.
- No database, domain, retention, consent, provider, privacy, or permission behavior changed.

## Verification

- Focused `CaptureUiIntegrationTest`: **8 tests passed** after correcting the large-list test to account for the existing newest-first order.
- Full JVM XML results: **79 suites / 409 tests, 0 failures, 0 errors, 1 opt-in live test skipped**.
- `assembleDebug`, `assembleDebugAndroidTest`, and `compileDebugAndroidTestKotlin` completed. Lint completed with **0 errors / 90 warnings / 2 hints**.
- Debug APK: v103 / `1.1.81-inbox-ui`, SHA-256 `6433abe2e0456f1456358738acc9910052c88ed7e3ce7404d1508fa017b609d0`.
- The synthetic Android 14 emulator reports versionCode 103 and the prior `firstInstallTime` `2026-11-15 16:15:34`. No app data was cleared. The synthetic Inbox records were **not read back after update**.
- `git diff --check` passed.

## Not completed

- The owner requested a stop while `:app:componentVisualRegression` was running. The full Gradle invocation therefore has no final `BUILD SUCCESSFUL` result. Its visual comparison is **NOT TESTED**; the checked-in 31 baselines and thresholds were not changed.
- No real Inbox screen screenshot or manual Inbox journey was captured before the stop. AZ `fontScale=1.3`, light/dark appearance, keyboard, rotation/back restoration, and exact on-device capture data readback remain **NOT TESTED** for this block.
- TalkBack, physical Redmi, large-store performance, SAF round-trip, owner acceptance, native acceptance, and release readiness remain **NOT TESTED / OPEN**.

The current UI scope remains partial. Next, after the owner resumes work, continue with Backup, Settings, and the final visual pass. Preserve `main` and owner-owned `docs/adr/ADR-012-keystore-wrapping-key-rotation.md`.


## Git handoff

Implementation commit `cfe6fd1740d4a7b1a15a742e389d336342883030` (`feat(ui): redesign inbox workspace`) is pushed to `origin/ui/redesign-v1`. `git fetch` and `git ls-remote` verified local HEAD, tracking ref, and remote feature ref at the same SHA. Remote `main` remains `ac621919aca09ece6e292eae708d55cc09de16d7`; no merge, force push, or history rewrite. Owner ADR-012 remains untracked and was not staged or changed. APKs and local keys were not committed.
