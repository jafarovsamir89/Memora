# Memora 2.0 UI/UX — Projects and Notes

**Status:** implementation and host verification complete; screenshots captured on synthetic Android 14 emulator.
**Branch:** `ui/redesign-v1`.
**APK:** v101 / `1.1.79-projects-notes-ui`, SHA-256 `b067281ac3eabfafeb0789feeb17bc19acdc18991fa3d4266d7c43d1a33cab52`.

## Changes

- Replaced the generic Projects and Notes destinations with one shared, read-only Compose library over the existing `ProtectedDomainRepository`.
- Added localized titles and descriptions for default, Azerbaijani, English and Russian resources. The screen has a scoped search field, refresh/retry, result count, loading/error/empty states, 30-record pages, stable row keys and a disclosed 100-result search limit.
- Added typed `PROJECT` and `NOTE` targets. List cards and related records open the exact ID. Detail lookup checks both object kind and active conversation scope; missing or unavailable objects have explicit states. Existing person/project/note links remain within the same scope.
- Search, refresh and list state use Compose saveable state; asynchronous list requests ignore stale results after a query/scope change. Production screens are read-only and do not insert preview or demo records.
- No database schema, ledger, domain mutation, AI provider, consent, privacy, authentication or permission behavior changed.

## Verification

Full command:

```text
JAVA_TOOL_OPTIONS='-XX:TieredStopAtLevel=1' ./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:compileDebugAndroidTestKotlin :app:lintDebug :app:componentVisualRegression --no-daemon --console=plain --max-workers=1
```

**BUILD SUCCESSFUL:** 79 JVM suites / 405 tests, 0 failures, 0 errors, 1 opt-in live test skipped; debug and AndroidTest APK builds and AndroidTest Kotlin compile passed; lint 0 errors / 90 warnings / 2 hints; component visual regression 31/31 passed with the approved thresholds and baselines unchanged.

Four host Compose regressions use an isolated synthetic database: project pagination beyond 30 with a foreign-scope record excluded; a note search hit beyond the first page; exact project detail and same-scope related note/person navigation; and refusal to resolve a project ID as a note. Route tests cover typed ID round-trip and back-stack behavior. No live AI call or production write was used.

On the synthetic Android 14 emulator `emulator-5556`, the v101 debug app was installed without clearing its profile. Package version and `firstInstallTime=2026-11-15 16:15:34` were observed. Both empty library screens were inspected in light and dark themes and at Azerbaijani `fontScale=1.3`; text wrapped without clipping. On Projects, the search field remained visible with the keyboard open, and the layout remained usable in landscape. Emulator settings were restored to light theme, portrait and `fontScale=1.0`.

The emulator had no project or note records, so its screenshots document real empty states. Populated-list, search, exact-ID detail and scope behavior are proven by host Compose tests with synthetic fixtures, not by an instrumented populated-profile emulator journey. Physical Redmi, TalkBack, process-death restoration, owner acceptance and release readiness are **NOT TESTED**. No personal data, microphone, Gemini, network AI or paid API was used.

## Real emulator screenshots

All are direct `adb screencap` PNGs from the installed v101 app (1080×2400), Azerbaijani UI:

| Screen | Evidence |
|---|---|
| Projects, light | [01-projects-light.png](screenshots/01-projects-light.png) |
| Projects, dark | [02-projects-dark.png](screenshots/02-projects-dark.png) |
| Projects, AZ at fontScale 1.3 | [03-projects-az-font-1.3.png](screenshots/03-projects-az-font-1.3.png) |
| Notes, light | [04-notes-light.png](screenshots/04-notes-light.png) |
| Notes, dark | [05-notes-dark.png](screenshots/05-notes-dark.png) |
| Notes, AZ at fontScale 1.3 | [06-notes-az-font-1.3.png](screenshots/06-notes-az-font-1.3.png) |

Additional direct captures used for manual inspection of the Projects search field with IME and landscape layout are in `/tmp/memora-projects-ime-v101.png` and `/tmp/memora-projects-landscape-v101.png`; they are not repository artifacts.
