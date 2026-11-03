# M20-08 Native validation matrix

Status as of 2026-11-03, APK v34 / 1.1.12-attention, Room schema 13. This matrix records existing evidence and remaining work; it does not turn host tests into device or live-provider acceptance.

## Executed baseline

The ACT-0113 host command passed after adding the synthetic memory-context case:

    .\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:compileDebugAndroidTestKotlin :app:lintDebug --no-daemon --console=plain --max-workers=1

Result: 49 JUnit suites / 258 tests, zero failures/errors/skips; debug and AndroidTest APKs assembled; AndroidTest Kotlin compiled; lint reported 0 errors and 56 warnings. Instrumentation was not launched. Synthetic Robolectric screenshots were reviewed for Settings and Today. No live Gemini request was made. The SDK adb.exe exists, but adb devices -l lists no devices. The test-only addition did not change APK contents; APK v34 and its backup still match at SHA-256 7BA970ACF774128817BF3B6006B025D9BA22A5FD1DE76A9F30864169CF632503.

## Cross-scenario status

| Master scenario | Existing evidence | Status and remaining proof |
|---|---|---|
| Casual chat without forced tools | AgentRuntimeTest.emptyRegistryIsClosedAndCanAnswerWithoutTools | Host fake-provider behavior only; real Gemini conversation and natural-language quality are unverified. |
| Ruslan gift/context inquiry | AgentFakeProviderMemoryTest.fakeProviderUsesScopedMemoryEvidenceForGiftAdviceWithoutWriting seeds a fictional person and preference, routes search_memory through AgentRuntime, checks source revision and scope isolation, and verifies no writes. | Partial: fake model chooses the tool; Gemini natural-language routing, consented conversational archive search, one useful follow-up, optional reminder, and live/device acceptance remain open. |
| Business analysis from synthetic data | AgentFakeProviderWorkStatusTest, AgentFakeProviderFinanceTest, FinanceAnalyticsTest | Local tool/data calculations and no-write behavior are covered. Model-selected multi-tool explanation and coverage in a Gemini response remain open. |
| Elchin/BMW case, amount/payment, next step and restart | AgentFakeProviderCaseTest.fakeProviderCreatesCaseAndLinksCanonicalFinanceSourceWithoutCopyingIt, case revision/source tests, DatabaseStorageProbe synthetic probe code | Individual case/source and ledger paths exist. Full BMW cross-domain chain plus SQLCipher process-restart readback has not run on Android. |
| Same-name people | AgentFakeProviderCaseTest.sameNameSuggestionsStayUnlinkedUntilOwnerConfirmsAndLinkCanBeReversed; FinanceAnalyticsTest.sameDisplayNameDoesNotMergeSeparatePersonLedgerIdentities | Host identity and finance isolation tests pass. Full people/task/calendar cross-domain disambiguation remains open. |
| Canonical event on person/case timeline without duplicates | AgentFakeProviderCaseTest.fakeProviderCreatesCaseAndLinksCanonicalFinanceSourceWithoutCopyingIt; CasesAndPersonCardTest.personCardRequiresVisibleOwnerConfirmationBeforeShowingFinanceHistory | Host source/link and owner-confirmation behavior is covered. Android accessibility and screenshot acceptance remain open. |
| Long-term source-backed conversation memory and deletion propagation | Archive/search and retention suites cover local conversation storage, lookup, whole-turn deletion and retention behavior. | Partial: no episodic summarization spanning weeks is implemented; cross-domain derived-memory deletion and Android restart are open. |
| Blank first install versus data-preserving upgrade | LocalProfileOnboardingTest.installMarkerInspectsBeforeFreshDatabaseAndRecognizesExistingDatabase, cleanProfileStartsEmptyAndOnboardingWritesOnlyProfilePreferences, encryptedProfileStorePersistsPreferencesWithoutSeedingDomainFacts; AttentionEngineTest.versionTwelveMigrationPreservesExistingRowsAndAddsOnlyAttentionSettings | Host fresh-profile and Room 12-to-13 migration tests pass. Real SQLCipher migration, signed install -r, and preserved user-data readback on Android remain unverified. |
| Provider substitution | AgentToolCatalogTest.alternateProviderUsesTheSameRuntimeToolAndTypedResultEnvelope, AgentFakeProviderFinanceTest.alternateProviderWritesExactAmountThroughExistingFinanceHandler | Fake adapter uses the same runtime/domain handler and exact finance result. No second production provider or network adapter is authorized/implemented. |
| Today/Secretary/Cases/Memory/People/Reports render and navigate | UiShellTest and screen-specific Compose/Robolectric tests; ACT-0111 Today/Settings screenshots reviewed. | Host visual evidence is partial. Not every screen/state has a reviewed screenshot; TalkBack, target-size checks and real-device navigation remain open. |
| Corrections update derived balance with immutable history | FinanceAnalyticsTest.amountCorrectionFlowsIntoTheSameMonthlyAggregateWithoutAnotherEvent and finance ledger/adjustment tests | Host exact-balance and append-only correction behavior is covered. Live Gemini correction and device readback remain open. |
| No premature account/sync/release claims | Source and manifest review; local-first scope remains the implemented path. | No server login/sync implementation is claimed. MEM-14.09 and all release gates remain open. |

## Reliability dimensions

| Dimension | Host evidence | Remaining limitation |
|---|---|---|
| Consent revocation | AgentConsentPolicyTest.revokedMemoryConsentBlocksHistoryToolAndPreparedCommit, AgentRuntimeTest.deniedMemoryConsentPreventsSecondCloudSend, LocalProfileOnboardingTest.revokingCloudConsentKeepsTheExistingLocalConversationScope | No real Gemini request verifies revocation mid-session. |
| Crash/restart recovery | AgentRuntimeTest.recoveryMarksInterruptedTurnWithoutRetainingText, cancellation/unknown-effect tests, archive and encrypted-store restart tests | SQLCipher process death/restart on Android not executed. |
| Idempotence and conflicts | Runtime duplicate-call, encrypted task/capture command replay, case revision and confirmation-gate tests | End-to-end Gemini retries against Android SQLCipher remain open. |
| Offline/failure behavior | Runtime timeout/empty-response/failure tests and storage retry/error tests | Airplane-mode Android UX, queued behavior, and recovery after connectivity returns not checked. |
| AZ/RU/mixed language | Locale validation accepts az-AZ and ru-RU; Gemini instrumentation probe contains synthetic AZ/RU paths. | No live API call was run and no Android device is attached; mixed RU/AZ and natural phrasing are not accepted. |
| SQLCipher migration and recovery | Synthetic Android instrumentation probe code exists in DatabaseStorageProbe; current v12-to-13 Room migration has a host test. | Instrumentation was compiled, not run. No device was attached. |

## Next validation actions

1. When a non-destructive Android test session is available, run the synthetic SQLCipher migration probe, signed adb install -r, process-restart readback, notification permission/delivery, and UI/accessibility review. Do not clear application data or run a runner that can reset the user's install.
2. If Gemini test credentials and the allowed test configuration are available in the app, run only synthetic scenarios: casual chat, gift lookup with seeded evidence, business analysis, and the BMW/case chain. Store only status/code/trace identifiers in logs.
3. Add/run live Gemini gift-context routing only with the allowed synthetic setup; implement episodic-summary/deletion propagation, mixed-language, complete cross-domain BMW and all-screen visual scenarios before calling M20-08 complete.
4. Re-run the documented full Gradle baseline after any code changes and record the actual database readback and APK hash. Do not claim owner acceptance, device migration, or release readiness from host results.
