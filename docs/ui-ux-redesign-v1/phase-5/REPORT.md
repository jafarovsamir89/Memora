# Phase 5 — More catalog and Finance (ACT-0247)

Implemented on `ui/redesign-v1` as a UI-only package.

## Changes

- Grouped the More directory into **People and work**, **Planning and memory**, and **Data and settings** while keeping the directory above the finance overview/report.
- Updated shared filter chips to wrap into additional rows when the localized labels and system font scale no longer fit. This prevents the Azerbaijani “Debts” filter from being clipped at `fontScale=1.3`; the same responsive layout also applies to other screens that use `Filters`.
- Redesigned the Finance list with localized All/Received/Expenses/Debts filters, a stable lazy list, explicit loading/error/retry/empty/filter-empty states, and compact transaction cards.
- Transaction cards show the existing event type, exact amount and currency, person when available, profile-timezone date, original note/source, and existing voided/debt-settlement context. They open the exact finance detail route by stable event ID in the current scope. No mixed-currency total is introduced.
- Removed the duplicate Finance page heading: the app toolbar remains the title, and the page now shows the descriptive subtitle.
- Finance detail amounts now follow the selected app UI locale instead of the device's default locale.

No ledger, domain write path, financial semantics, schema/migration, AI, consent, privacy or permission behavior changed. No sample records are inserted into the working database.

## Verification

- Full Gradle command passed: `:app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:compileDebugAndroidTestKotlin :app:lintDebug`.
- JVM: **77 suites / 396 tests, 0 failures, 0 errors, 1 opt-in live test skipped**.
- Debug and AndroidTest APKs built; AndroidTest Kotlin compilation passed.
- Lint: **0 errors / 85 warnings / 3 hints**.
- `:app:componentVisualRegression`: **31/31** emulator captures passed; thresholds and approved baselines unchanged.
- Synthetic Compose regression covers More category ordering, ledger-kind filters, localized money amount, exact finance route and return to the same filter. At `fontScale=1.3`, all four filter chips remain displayed. These financial records exist only in the isolated in-memory test database.
- Installed v99 with `adb install -r` on the synthetic Android 14 emulator `emulator-5556`; `firstInstallTime` remained `2026-11-15 16:15:34`. MainActivity launched; no app data was cleared. System font scale was restored to 1.0 and the emulator theme was restored to dark.
- APK: v99 `1.1.77-finance-navigation`, SHA-256 `91291d0d36a61047be9c563aa228ff172b73bea0f44dd6dd8509b04dddc23949`.
- `git diff --check` passed.

## Real emulator screenshots

All images are direct `adb screencap` PNGs from the synthetic Android 14 emulator at 1080×2400. The Finance screen on this emulator had no financial records; screenshots therefore show the honest empty state. Populated-record behavior is verified by the isolated synthetic Compose test above.

| Evidence | Screenshot |
| --- | --- |
| More catalog, dark | [01-more-groups-dark.png](screenshots/01-more-groups-dark.png) |
| Finance empty state, dark | [02-finance-empty-dark.png](screenshots/02-finance-empty-dark.png) |
| More at Azerbaijani `fontScale=1.3`, dark | [03-more-az-font-1.3.png](screenshots/03-more-az-font-1.3.png) |
| Finance filters wrapped at Azerbaijani `fontScale=1.3`, dark | [04-finance-az-font-1.3-dark.png](screenshots/04-finance-az-font-1.3-dark.png) |
| Finance empty state, light | [05-finance-light.png](screenshots/05-finance-light.png) |
| More catalog, light | [06-more-catalog-light.png](screenshots/06-more-catalog-light.png) |

## Acceptance limits

Physical Redmi, TalkBack, hardware keyboard, populated-ledger APK upgrade readback, owner visual acceptance, live Gemini/audio/network timing and large-profile device performance were not tested. The emulator had only synthetic test state; no personal profile or record was inspected. This package does not establish native acceptance or production readiness.
