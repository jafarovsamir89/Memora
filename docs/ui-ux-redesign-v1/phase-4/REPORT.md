# Stage 4 — Memory library (ACT-0245)

Implemented the Memory screen as a searchable, filterable library in `ui/redesign-v1`.

## What changed

- Replaced the eager screen column with a `LazyColumn` and stable item keys. The query, archive toggle, selected filter, and list position survive navigation/recomposition.
- Added filters for all, conversations, profile facts, people, projects, notes, and sources. They reuse the current local repositories and search behavior; no new search index or retention rule was introduced.
- Conversation history keeps its existing 30-item cursor paging. People, projects, and notes now use 30-row pages over the existing scoped DAO query; loading more preserves deterministic title/ID order. The profile-memory screen requests the repository's existing 200-fact maximum.
- Person results open the exact typed person route. Other domain results, conversation turns, episode sources, profile-fact sources, and saved capture records continue to open their selected record/source.
- Kept existing edit/delete, archive, retention, profile preferences, and new-capture actions. The new-capture action remains reachable from the Memory screen and covered by the existing capture UI tests.

No Room schema or migration, ledger write path, domain mutation rule, AI provider, consent, or retention policy changed. The only repository addition is a read-only, same-scope page wrapper around an existing DAO query.

## Verification

- Full Gradle command passed: `:app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:compileDebugAndroidTestKotlin :app:lintDebug`.
- JVM result: **77 suites / 395 tests, 0 failures, 0 errors, 1 opt-in live test skipped**.
- Debug and AndroidTest APK builds and AndroidTest Kotlin compilation passed. Lint: **0 errors / 84 warnings / 3 hints**.
- `:app:componentVisualRegression`: **31/31** light/dark component captures passed without changing baselines or thresholds.
- The new synthetic UI regression created 35 same-scope synthetic people and verified the first page, the load-more action, and a record on page two. Existing Memory tests passed for filters, exact person routing, conversation paging, source opening, retention confirmation, and deletion behavior.
- Installed APK v98 over v97 with `adb install -r` on synthetic Android 14 emulator `emulator-5556`. A synthetic profile fact created in v97 remained visible in v98. `firstInstallTime` remained `2026-11-15 16:15:34`; no app data was cleared. Font scale returned to 1.0 after the large-text capture.
- Debug APK SHA-256: `74d47a98ba78edfc860e1d4776d77059dacb546e6c48d09783df26865c179a33`.
- `git diff --check` passed.

## Real emulator screenshots

These are direct `adb screencap` captures at 1080×2400. The saved profile fact is synthetic and was entered through the app UI.

| Evidence | Screenshot |
| --- | --- |
| Empty state, dark | [01-memory-dark.png](screenshots/01-memory-dark.png) |
| Empty state, light | [02-memory-light.png](screenshots/02-memory-light.png) |
| Azerbaijani at `fontScale=1.3` | [03-memory-az-font-1.3.png](screenshots/03-memory-az-font-1.3.png) |
| Synthetic saved fact, light | [04-memory-synthetic-fact-light.png](screenshots/04-memory-synthetic-fact-light.png) |
| Search finding that fact | [05-memory-search-light.png](screenshots/05-memory-search-light.png) |
| Synthetic saved fact, dark | [06-memory-synthetic-fact-dark.png](screenshots/06-memory-synthetic-fact-dark.png) |
| Same fact after v97→v98 update | [07-memory-v98-update.png](screenshots/07-memory-v98-update.png) |

## Not tested

Physical Redmi acceptance, TalkBack, live Gemini/audio/network performance, a clean-install device flow, and very-large real profiles remain untested. The UI page regression uses 35 synthetic objects; it does not claim a performance benchmark for thousands of device records. The APK is a debug build, not a production release.
