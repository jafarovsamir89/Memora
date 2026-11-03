# M20-08 Native validation matrix

Status as of 2026-11-03, APK v40 / 1.1.18-person-sources, Room schema 15. This matrix records existing evidence and remaining work; it does not turn host tests into device or live-provider acceptance.

## ACT-0125 update

Person story entries now open task/calendar source details only when the source is explicitly linked through persisted case evidence and the viewed person is an explicit participant in that case. No identity is inferred from names or labels. Source details are embedded in the current person dialog with a Back action; Azerbaijani, Russian and English labels cover the supported follow-up/appointment relations. No schema migration or finance write path changed.

Synthetic Compose coverage verifies one appearance per linked task/calendar source, source detail content, and return navigation. Screenshots `20-person-card-explicit-sources.png` and `21-person-task-source-detail.png` were viewed. Full host command:

    .\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:compileDebugAndroidTestKotlin :app:lintDebug --no-daemon --console=plain --max-workers=2

Result: 54 suites / 274 tests, 0 failures/errors; debug/AndroidTest APKs and AndroidTest Kotlin compile passed; lint 0 errors / 57 warnings / 0 information. APK v40 SHA-256 `A892D58646BC09C47C6B98D68365B606D48325633831BEE7BD877310F3A61757`; matching backup `C:\memora-backups\Memora-v40-1.1.18-person-sources-2026-11-03.apk`. The connected Redmi (Android 13) was updated from v38 with `adb install -r` and launched; versionCode 40 verified. The installed v38 APK was backed up before update. User DB contents were not exported or inspected, and app data were not cleared. No live Gemini request: the owner cannot perform that check now. Device visual/owner/TalkBack acceptance, real DB migration/readback, Gemini and remaining M20 scenarios remain open.

## ACT-0124 update

Person-card relationships now come from persisted, active `domain_link` rows in the current scope. The query excludes deleted/cross-scope endpoints and is bounded; the card does not infer links from matching names or treat task/calendar labels as canonical identity. Explicitly linked people/projects/notes are shown with localized labels. Selecting one replaces the detail content in the existing dialog and a back stack returns to the prior person. No schema or finance ledger change.

A synthetic Compose test creates a linked and an unlinked project, checks that only the explicit same-scope link is shown, opens the linked record and returns. Full host command:

    .\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:compileDebugAndroidTestKotlin :app:lintDebug --no-daemon --console=plain --max-workers=2

Combined command result (the full JVM suite was rerun after the final screenshot-only test edit): 54 suites / 273 tests, 0 failures/errors/skips; app and AndroidTest APKs assembled, AndroidTest Kotlin compiled, lint 0 errors / 57 warnings / 0 informational. The focused `CasesAndPersonCardTest` also passed 3/3. Synthetic Compose renders were reviewed; Robolectric composites underlying windows without the system dim layer, so this is host visual/navigation evidence only. APK v39 SHA-256 `A9799059B7A3C4042BFD636A578D96EABCBD8E0FAAD835CA852701B15F0E29FC`; matching backup `C:\memora-backups\Memora-v39-1.1.17-person-links-2026-11-03.apk`. `adb devices -l` was empty, so install/device/TalkBack/owner acceptance remain open. No live Gemini request or app database access.

## ACT-0122 update

The bounded cross-domain lexical path was added to search_memory without changing the Room schema or bypassing canonical tools. It reports only scoped, bounded evidence for domain records, cases, finance ledger events, tasks, calendar, custom table rows, retained conversation turns, source-backed episodes and editable profile facts. Provenance IDs/revisions are included; exact finance totals remain delegated to finance analytics tools. Search reads require the existing persisted-memory consent. Ledger rows are included only when finance-source migration/configuration is available (financeEnabled); this path does not define a separate finance-consent category. Azerbaijani normalization handles diacritics, common transliterations and inflected prefixes; this is lexical matching, not semantic/embedding search.

A synthetic fake-provider E2E fixture verifies cross-domain evidence, foreign-scope isolation, source provenance, zero writes and bounded output, including Azerbaijani name inflection. Tool descriptors continue to require persisted-memory consent; broader consent-revocation behavior is covered by existing runtime/profile tests. Full command:

    .\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:compileDebugAndroidTestKotlin :app:lintDebug --no-daemon --console=plain --max-workers=2

Result: 54 suites / 272 tests, zero failures/errors/skips; debug and AndroidTest APKs assembled, AndroidTest Kotlin compiled, lint 0 errors / 57 warnings / 0 informational issues. APK v38 SHA-256 5D0D84E6B1594BAA463B6264B6EB75C8368DA60332C62A322A1B929064B2E9BF; backup C:\memora-backups\Memora-v38-1.1.16-unified-memory-2026-11-03.apk matches. adb install -r updated v37→v38 on the connected Redmi and launched it; user data were not cleared and the app DB was not inspected. No live Gemini request was made for this feature. AppCheckSetup.kt selects the debug provider for debug builds; project history records only the Redmi debug token, so another device likely needs its separate token added in Firebase Console before AI Logic calls succeed. That second-device diagnosis remains unverified until a request/log is observed. Search quality, semantic retrieval, owner acceptance, all other M20 and release gates remain open.

## ACT-0120 update

The full host command was rerun after tightening explicit profile-save routing:

    .\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:compileDebugAndroidTestKotlin :app:lintDebug --no-daemon --console=plain --max-workers=2

Result: 53 suites / 271 tests, zero failures/errors/skips; app and AndroidTest APKs assembled, Kotlin compiled, lint 0 errors / 57 warnings / 3 hints. APK v37 SHA-256 `DAA1DD587C3F75BDB5B547C7B788A32E2C7ECDEE9E766CA8FA920F4DE54A5247`; backup `C:\memora-backups\Memora-v37-1.1.15-profile-memory-actions-2026-11-03.apk` matches. v37 installed over v36 with `adb install -r`, launched, package manager confirmed code 37. Synthetic live Gemini scenario verified profile save, search, edit, revision-2 source readback, consent revocation and post-force-stop search. The delete tool returned APPLIED, then its follow-up hit `model_rate_limited` and runtime correctly returned PARTIAL; the probe did not claim final response or post-delete readback. The synthetic DB was removed by explicit cleanup. Trace ID `3081098a-3ed9-4794-8b73-14e41e3fc1bf`; text content was not retained.

## ACT-0118 update

The full host command passed after adding editable source-backed profile memory:

    .\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:compileDebugAndroidTestKotlin :app:lintDebug --no-daemon --console=plain --max-workers=2

Result: 53 JUnit suites / 271 tests, zero failures/errors/skips; debug and AndroidTest APKs assembled; AndroidTest Kotlin compiled; lint reported 0 errors, 57 warnings and 3 hints. The synthetic Android SQLCipher probe migrated its isolated v1 fixture through schema 15, read back a synthetic profile fact and correction history, then verified source deletion removes the derived profile fact. Probe DB and key material were cleaned. A separate opt-in live Gemini profile scenario selected `save_profile_fact`, verified write/source readback, selected `search_memory` for a synthetic preference question, and returned a final response; memory-consent revocation blocked the next model/tool request. After force-stop, a separate verify invocation reopened the same synthetic SQLCipher DB, selected `search_memory`, checked the result, and automatically cleaned the fixture. Synthetic run trace: `db709d5b-bc99-4cc2-af8e-608d3ff9a18c`; no utterance/fact/answer text was retained in logs. APK v36 SHA-256 is `B776B08DD8758C70380F8B09E26BCB3FDD6B1E240D20D81063361B08AB04BC44`; matching backup: `C:\memora-backups\Memora-v36-1.1.14-profile-memory-2026-11-03.apk`. v36 was installed over v35 using `adb install -r`, launched, and package manager reported versionCode 36. The user database was not opened or cleared.

## Executed baseline

The ACT-0115 full host command passed after adding source-backed episodic memory:

    .\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:compileDebugAndroidTestKotlin :app:lintDebug --no-daemon --console=plain --max-workers=1

Result: 51 JUnit suites / 265 tests, zero failures/errors/skips; debug and AndroidTest APKs assembled; AndroidTest Kotlin compiled; lint reported 0 errors, 56 warnings and 3 hints. This full baseline predates the ACT-0116 tool-contract change. After ACT-0116, the focused catalog/episode/argument-normalizer suite passed 10 tests with zero failures/errors; AndroidTest APK assembled and Kotlin compiled. The newest debug APK is 29,693,037 bytes, SHA-256 `118CBFA85DD4B6F6B91883F32495C221580A082EB9354E2866306F8B7BF552B3`, backed up at `C:\memora-backups\Memora-v35-1.1.13-episodic-memory-typed-sources-2026-11-03.apk`. Lint and the full 265-test command were not rerun after ACT-0116.

The latest v35 APK was installed over v35 with `adb install -r` and launched; package manager reported versionCode 35. The original installed v26 APK had been copied to `C:\memora-backups\Memora-installed-v26-before-v35.apk` before the first upgrade. The actual app database has not been queried for schema or rows. No application data was cleared or app uninstalled.

## Cross-scenario status

| Master scenario | Existing evidence | Status and remaining proof |
|---|---|---|
| Casual chat without forced tools | AgentRuntimeTest.emptyRegistryIsClosedAndCanAnswerWithoutTools | Host fake-provider behavior only; real Gemini conversation and natural-language quality are unverified. |
| Ruslan gift/context inquiry | AgentFakeProviderMemoryTest.fakeProviderUsesScopedMemoryEvidenceForGiftAdviceWithoutWriting seeds a fictional person and preference, routes search_memory through AgentRuntime, checks source revision and scope isolation, and verifies no writes. | Partial: fake model chooses the tool; Gemini natural-language routing, consented conversational archive search, one useful follow-up, optional reminder, and live/device acceptance remain open. |
| Business analysis from synthetic data | AgentFakeProviderWorkStatusTest, AgentFakeProviderFinanceTest, FinanceAnalyticsTest | Local tool/data calculations and no-write behavior are covered. Model-selected multi-tool explanation and coverage in a Gemini response remain open. |
| Elchin/BMW case, amount/payment, next step and restart | AgentFakeProviderCaseTest.fakeProviderCreatesCaseAndLinksCanonicalFinanceSourceWithoutCopyingIt, case revision/source tests, DatabaseStorageProbe synthetic probe code | Individual case/source and ledger paths exist. Full BMW cross-domain chain plus SQLCipher process-restart readback has not run on Android. |
| Same-name people | AgentFakeProviderCaseTest.sameNameSuggestionsStayUnlinkedUntilOwnerConfirmsAndLinkCanBeReversed; FinanceAnalyticsTest.sameDisplayNameDoesNotMergeSeparatePersonLedgerIdentities | Host identity and finance isolation tests pass. Full people/task/calendar cross-domain disambiguation remains open. |
| Canonical event on person/case timeline without duplicates | AgentFakeProviderCaseTest.fakeProviderCreatesCaseAndLinksCanonicalFinanceSourceWithoutCopyingIt; CasesAndPersonCardTest.personCardRequiresVisibleOwnerConfirmationBeforeShowingFinanceHistory | Host source/link and owner-confirmation behavior is covered. Android accessibility and screenshot acceptance remain open. |
| Long-term source-backed conversation memory and deletion propagation | `ConversationEpisodeMemoryTest` covers episode source/lifecycle. `ProfileMemoryRepositoryTest` covers explicit profile facts, idempotency, revisions, search, scope, manual edit, stale conflicts, source deletion and retention. `AgentFakeProviderProfileMemoryTest` routes save/search/edit/delete through `AgentRuntime` and verifies revoked memory consent blocks writes. Host migration validates 14→15. ACT-0116 Gemini episode route and ACT-0118/0120 Gemini profile save/search/edit, consent revocation, separate-process revision-2 retrieval and synthetic Android SQLCipher v1→15 readback/source deletion passed. The latest delete tool returned APPLIED but final response was rate limited, so post-delete live readback remains open. | Partial: robust live delete/final-response path, episode continuation, semantic/cross-domain retrieval and correction propagation beyond source deletion remain open. Actual user DB migration/readback/restart remains open. |
| Blank first install versus data-preserving upgrade | LocalProfileOnboardingTest and additive host migrations; device v26→v37 signed `adb install -r` and launch, including v36→v37 in-place update. ACT-0118 isolated SQLCipher fixture upgraded v1→15 and verified profile rows/revisions/source deletion. | Fresh-install host tests pass; in-place install launches without clearing data. The real app DB was intentionally not opened, so its direct schema/row readback after upgrade remains unverified. The synthetic SQLCipher DB does not prove app DB preservation. |
| Provider substitution | AgentToolCatalogTest.alternateProviderUsesTheSameRuntimeToolAndTypedResultEnvelope, AgentFakeProviderFinanceTest.alternateProviderWritesExactAmountThroughExistingFinanceHandler | Fake adapter uses the same runtime/domain handler and exact finance result. No second production provider or network adapter is authorized/implemented. |
| Today/Secretary/Cases/Memory/People/Reports render and navigate | UiShellTest and screen-specific Compose/Robolectric tests; ACT-0111 Today/Settings screenshots reviewed. | Host visual evidence is partial. Not every screen/state has a reviewed screenshot; TalkBack, target-size checks and real-device navigation remain open. |
| Corrections update derived balance with immutable history | FinanceAnalyticsTest.amountCorrectionFlowsIntoTheSameMonthlyAggregateWithoutAnotherEvent and finance ledger/adjustment tests | Host exact-balance and append-only correction behavior is covered. Live Gemini correction and device readback remain open. |
| No premature account/sync/release claims | Source and manifest review; local-first scope remains the implemented path. | No server login/sync implementation is claimed. MEM-14.09 and all release gates remain open. |

## Reliability dimensions

| Dimension | Host evidence | Remaining limitation |
|---|---|---|
| Consent revocation | AgentConsentPolicyTest.revokedMemoryConsentBlocksHistoryToolAndPreparedCommit, AgentRuntimeTest.deniedMemoryConsentPreventsSecondCloudSend, LocalProfileOnboardingTest.revokingCloudConsentKeepsTheExistingLocalConversationScope; ACT-0118/0120 synthetic live Gemini profile probe blocked before model/tools after revocation. | Mid-session revocation was checked on isolated probes only; owner app session behavior remains unverified. |
| Crash/restart recovery | AgentRuntimeTest.recoveryMarksInterruptedTurnWithoutRetainingText, cancellation/unknown-effect tests, archive and encrypted-store restart tests; ACT-0116 synthetic Android SQLCipher episode DB reopened after force-stop with source readback; ACT-0118 synthetic profile migration/readback completed. | Actual app DB restart/readback remains unverified; offline UI and unknown-effect recovery on Android remain open. |
| Idempotence and conflicts | Runtime duplicate-call, encrypted task/capture command replay, case revision and confirmation-gate tests | End-to-end Gemini retries against Android SQLCipher remain open. |
| Offline/failure behavior | Runtime timeout/empty-response/failure tests and storage retry/error tests | Airplane-mode Android UX, queued behavior, and recovery after connectivity returns not checked. |
| AZ/RU/mixed language | Locale validation accepts az-AZ and ru-RU. Live synthetic Gemini finance chains passed separately in AZ and RU; live Azerbaijani episodic save/readback passed. | Mixed RU/AZ same-utterance routing, full voice acceptance and final-response reliability remain open. |
| SQLCipher migration and recovery | Synthetic Android instrumentation migration probe passed a separate DB from Room v1 to `user_version=15`, retained its control row, verified new episode/profile tables, corrected and read back a synthetic profile fact, checked source-linked deletion, and cleaned the probe DB. A synthetic episode/profile DB was reopened after force-stop and retrieved. App v37 launches after signed in-place installation. | The actual user DB was not queried; its schema/readback and data continuity after process restart remain unverified. |

## Next validation actions

1. Use the installed v35 in the owner's normal daily flow; collect only sanitized issue descriptions and avoid writing live records in engineering logs.
2. Continue synthetic live Gemini gift-context, business-analysis and BMW/case chains, plus mixed-language RU/AZ voice. Keep each scenario in its own test DB and keep only status/code/trace IDs.
3. Resolve intermittent final-response timeout behavior for live episodic recall; then complete all-screen screenshots/TalkBack and notifications/accessibility review.
4. Before the next packaged feature release, run the documented full Gradle baseline/lint. Direct app DB schema/readback remains unverified and must use a non-destructive approach; do not claim owner acceptance or release readiness from host results.
