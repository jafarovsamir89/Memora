# ACT-0095 — Gemini tools and protected memory evidence

## Scope and isolation

Android debug build: versionCode 26, 1.1.4-field-secretary. Provider: the existing permitted Firebase AI Logic / Gemini configuration. All API inputs and stored fixtures are synthetic. Each API run opens a separate SQLCipher database named from its synthetic run ID. The user's database is neither queried by the probe nor cleared. APK updates use install -r.

Final installed APK SHA-256: 587cd8f5141696aed845cb4fee5e3b56ed8b59e4b565c6dfb27fe1fda15d1536.

Diagnostics contain model reply categories, tool names, status/code, duration and synthetic trace IDs. They contain no utterance, answer, tool arguments, memory payload, API key or device identifier. Runtime stage logging covers model policy, tool selection/validation, tool policy, prepare, commit, repository readback and final status. The probe additionally returns model-round categories and sanitized tool outcomes even when device Logcat is unavailable.

## Final API scenario

One receipt is recorded. A later explanation annotates that exact receipt. The user then explicitly states the original debt before that repayment and asks to link the already recorded receipt. A correction adjusts the receipt and its existing settlement allocation. A balance query must read the repository. A separate instrumentation invocation after force-stop reopens the same encrypted database and queries Gemini again.

The original debt before repayment and the current outstanding debt are different facts. An earlier test used a current-debt statement while expecting automatic subtraction of a prior receipt; that assertion was ambiguous and was corrected. No rule was added to attach a payment merely because the person or amount matches.

### Mutation results on v26

| Synthetic step | AZ model rounds | RU model rounds | Selected tools / codes | Validation, prepare, commit and DB result | Final runtime status |
|---|---:|---:|---|---|---|
| Receipt | 2 | 2 | record_money_event / receipt_recorded | applied; exact record read back | completed |
| Later explanation | 4 | 4 | search_money; annotate_money_event / money_event_annotated | applied; one receipt retained; zero-value annotation retained | completed |
| Original obligation and link | 4 | 4 | search_money; record_money_event / debt_recorded; settle_debt / debt_settlement_recorded | applied; one receipt and one linked allocation; exact remaining debt checked | completed |
| User correction | 3 | 3 | search_money; correct_money_event / money_event_corrected | applied; append-only correction; linked allocation and remaining debt checked | completed |
| Balance question | 2 | 2 | search_money / financial_evidence_loaded | full-ledger exact summary returned | completed |
| Revoke memory consent | 0 | 0 | none | blocked before model and tools | memory_consent_required |

AZ run ID: d657a86a-1a33-4927-9824-a02f3c1f725e. RU run ID: c43b29e8-f5aa-4ca7-930e-f80c31245e51. The runtime input channels alternate VOICE/TEXT; the probe supplies text and does not activate the microphone or speech output.

Observed successful mutation/query turn durations: AZ 2,604–6,363 ms, RU 2,998–11,220 ms. These measurements exclude STT/TTS and are not a field latency guarantee.

### Restart evidence

Both final v26 runs passed force-stop, local SQLCipher readback, Gemini search with a nonempty final response and automatic synthetic DB cleanup. Final AZ restart request: completed in 4,070 ms, search_money applied, trace 61e3991b-43dc-4d50-98aa-0a1082783595. RU restart request: completed in 3,372 ms, search_money applied, trace 337ac241-3564-4996-adb1-15f7fda53158. A prior v25 mutation database also survived upgrade/force-stop and passed v26 Gemini restart search; its first read-only request timed out, and one read-only retry passed. No mutation was repeated by that retry.

An earlier RU run committed its receipt but failed during the final model response. Runtime reported PARTIAL/reply_unavailable_after_write, preserving the successful write. Its provider cause was not captured by the old probe, so it is not claimed to be a confirmed quota error. The later complete RU v26 run passed. Failed/intermediate synthetic databases were cleaned only through the probe's own cleanup/verify phases.

## Local and regression checks

- Device SQLCipher/migration probe passed: all legacy readers, atomic migration/retry, encrypted on-disk marker check, exact money, task history, command/outbox idempotency, scoped objects/tables/calendar, annotation/correction/reopen, same-name conflict with zero finance writes.
- Final command after all source changes: `:app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug` — BUILD SUCCESSFUL in 7m 38s. XML: 37 suites, 219 tests, 0 failures, 0 errors. Lint report: 0 errors/fatal findings. `git diff --check` passed. This includes the typed identity-clarification guard.
- Financial evidence is bounded to 10,000 UTF-8 JSON bytes; recent rows are retained and truncation is explicit. Full ledger totals are preserved. Multilingual budget tests and small complete-result tests pass in the full suite above.
- Empty model responses produce model_empty_response, never an invented success message. A verified write remains visible as partial when the final response fails; the write is not repeated.
- A typed person_name_ambiguous write conflict with zero applied writes leads to an app-authored clarification in AZ/RU/EN even when Gemini wraps its final text as Answer. A false saved claim cannot pass this guard. Other unresolved write conflicts remain failures. The guard is covered by the final runtime regression test; full aliases/disambiguation remains a separate TODO.
- JVM UI fixtures inject a separate in-memory Room database and close it outside Compose teardown. Production still opens SQLCipher with the existing key and permission path. This follows the [official Room testing guidance](https://developer.android.com/training/data-storage/room/testing-db); no Room migration or new database driver was introduced.

The report proves synthetic tool execution and repository state. It does not accept live microphone/TTS quality, every natural-language answer claim, aliases/disambiguation UX, every tool, release signing, recovery gates or owner field acceptance. MEM-12.16/17 and the broader release checklist remain open.
