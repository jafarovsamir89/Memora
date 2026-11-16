# Memora 2.0 UI/UX — Tables

**Status:** Tables UI implementation and verification complete on `ui/redesign-v1`.
**APK:** v102 / `1.1.80-tables-ui`, SHA-256 `af4e3a0a0eea044916e610203359e027ab64f398d393ac0043e0649bc688cb31`.

## Changes

- Rebuilt the Tables collection and detail surface as separate, keyed `LazyColumn` views. The collection has a primary create action, search by table title or active column name, compact editable/linked badges, loading/error/retry/empty/no-match states and stable table IDs.
- Added a collection Back action and Android Back handling from a table detail. Search and selected ID survive recreation. Separate saveable list positions keep the collection position when returning from a detail and open a detail at its own scroll position.
- Kept existing row edit/delete/restore, schema preview/history/restore, exact per-currency totals, linked-view search/sort/paging, and JSON SAF import/export entry points. Linked views remain read-only. No underlying import/export, schema, repository or domain contract changed.
- Changed the create-table mode/source chips to `FlowRow`; Azerbaijani labels remain available at `fontScale=1.3` without clipping. Added localized collection search/no-match strings.
- Improved the component visual comparison capture wait for the animated confirmation sheet only. Thresholds and committed goldens were not modified.
- No Room schema, migration, ledger/domain behavior, AI provider, consent, permission, authentication or privacy behavior changed.

## Verification

The full Gradle verification batch ran `testDebugUnitTest`, `assembleDebug`, `assembleAndroidTest`, `compileDebugAndroidTestKotlin` and `lintDebug`. These tasks passed. JVM XML reports show **79 suites / 408 tests, 0 failures, 0 errors, 1 opt-in live test skipped**. Lint reported **0 errors / 90 warnings / 2 hints**. Debug and AndroidTest APK packaging and AndroidTest Kotlin compilation passed.

Six `TablesScreenTest` Compose tests pass. New synthetic in-memory tests cover collection search by a column, returning from a detail with the search preserved, linked-view exact source visibility and keeping its read-only/write-action boundary, plus editable/linked creation controls at a 1.3 font scale. Existing tests continue to cover JSON transfer controls, exact minor-unit/currency totals, and schema preview/history/restore. No test weakened or removed.

The first 31-component screenshot run caught the confirmation sheet before its enter animation finished. The screenshot showed only the transitional scrim/sheet position; an immediate targeted comparison passed. The capture runner now allows that sheet additional settle time. The final `:app:componentVisualRegression` Gradle task completed **BUILD SUCCESSFUL**, 31/31, with existing comparison thresholds and approved baselines unchanged.

On synthetic Android 14 emulator `emulator-5556`, APK v102 was installed over v101 through `installDebug` without clearing app data. `firstInstallTime=2026-11-15 16:15:34` remained unchanged. A synthetic `Demo` table with one `Sample` row was created through the v101 UI and read back after the upgrade in v102 (`1 row · 1 column`, `Name: Sample`). The phone/emulator showed versionCode 102 and versionName `1.1.80-tables-ui`.

Manual emulator checks covered the table collection and exact selected table, dark and light themes, AZ at `fontScale=1.3`, the create-table dialog at 1.3, search with the keyboard open, rotation from a filtered collection into a detail, and Android Back returning to the filtered collection. The query `Demo`, selected table and row remained visible after the rotation/back journey. System font scale was restored to 1.0, orientation to automatic portrait, and app appearance to its prior light setting.

The repository `tables(scope)` API still materializes its table snapshots and row payloads before Compose receives them. `LazyColumn` limits composition/drawing work; it does not change this existing repository loading behavior. Linked-view reads continue using their existing 25-row page. Large populated-table memory/performance is **NOT TESTED**.

Physical Redmi, TalkBack, process-death recovery, SAF provider import/export round-trip, very large profiles, owner acceptance and release readiness are **NOT TESTED**. No personal records, Gemini, microphone, network AI or paid API was used.

## Real emulator screenshots

All PNGs are direct `adb screencap` captures from the installed v102 application, 1080×2400 unless the landscape image is named otherwise. The table content `Demo` / `Sample` is synthetic.

| Screen/state | Evidence |
|---|---|
| More catalog, light | [01-more-light.png](screenshots/01-more-light.png) |
| Table detail with synthetic row, light | [02-table-detail-light.png](screenshots/02-table-detail-light.png) |
| Table detail, AZ `fontScale=1.3` | [03-table-detail-az-font-1.3.png](screenshots/03-table-detail-az-font-1.3.png) |
| Table collection, AZ `fontScale=1.3` | [04-table-list-az-font-1.3.png](screenshots/04-table-list-az-font-1.3.png) |
| Create-table dialog, AZ `fontScale=1.3` | [05-table-create-az-font-1.3.png](screenshots/05-table-create-az-font-1.3.png) |
| Table collection, light | [06-table-list-light.png](screenshots/06-table-list-light.png) |
| Table collection, dark | [07-table-list-dark.png](screenshots/07-table-list-dark.png) |
| Table detail with synthetic row, dark | [08-table-detail-dark.png](screenshots/08-table-detail-dark.png) |
| Table detail, dark landscape | [09-table-detail-dark-landscape.png](screenshots/09-table-detail-dark-landscape.png) |
