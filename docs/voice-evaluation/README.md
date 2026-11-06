# Voice scenario corpus v1

The repository contains 1,000 deterministic, synthetic voice-test scripts in `voice-scenarios-v1.jsonl`.

## Coverage

- 250 cases each for Azerbaijani, Russian, English and Azerbaijani-led mixed speech.
- 10 categories, 25 cases per language/category cell: casual conversation, note saving, receipt, expense, unclear debt direction, task/reminder, calendar event, memory search, negated transaction and ambiguous transaction.
- 800 development rows and 200 reserved rows. Each cell contributes five reserved rows spread across its five recurring slot variants.
- Each row states the scripted utterance, reference transcript, expected high-level action/tool, safety expectation and synthetic slots. Rows contain no personal recordings or real user facts.

## What the 1,000-case run verifies

`tools/voice_eval/generate_voice_corpus.py --check` verifies deterministic generation, exact counts, unique IDs/texts, language/category balance, splits, bounded text, no embedded audio, and no obvious email, URL or API-key pattern. `VoiceScenarioCorpusTest` checks every row against the app's current locale policy, transcript buffer, tool catalog, no-write expectations, date/time fields and exact decimal slots.

This is a **text-fixture and app-contract regression**, not 1,000 real speech recognition tests. No audio is attached, no microphone is used, and no Gemini request is made by this corpus test. The `reference_transcript` is the script itself, not a measured transcript. A passing run does not measure WER, intent accuracy, answer quality, TTS intelligibility, latency, or Gemini reliability.

The reserved split is deterministic but not an independent speaker/audio holdout: it uses varied scripted slots while reusing the same scenario families. Do not claim MEM-02.03, MEM-02.05/06, MEM-13.09, or voice acceptance complete from this corpus run. Those require reviewed ground truth, approved real/synthetic audio acquisition, separation by speaker and similar utterances, predeclared metrics/thresholds, and an actual allowed provider/device evaluation.

## Reproduce

From the repository root:

```sh
python3 tools/voice_eval/generate_voice_corpus.py --check
cd apps/android
./gradlew :app:testDebugUnitTest --tests 'ai.memora.app.voice.VoiceScenarioCorpusTest' --no-daemon --console=plain --max-workers=1
```

The project-wide JVM/build/lint baseline remains the verification required before a release package.

## Live Azerbaijani scenario campaign

`az-gemini-live-scenarios-v1.jsonl` and `GeminiLiveScenarioCampaignTest` exercise 1,000 synthetic `az-AZ` text turns through the real Gemini Developer API gateway and Memora's production `AgentOrchestrator`, local tools and repositories. The rows form 100 separate 10-turn story chains. Each scope starts empty. Every nonblank COMPLETED/PARTIAL response is recorded in conversation history before independent assertions, matching `AgentVoiceTurnProcessor`; therefore later turns receive realistic context even when an earlier expected-tool or readback assertion fails. After each turn the test verifies tool behavior, reads ledger/memory/task/calendar state from isolated in-memory Room, checks cross-turn corrections and source references, and compares the final answer with requested facts. The harness permits `search_memory` to provide exact task/calendar rows for a cross-domain query when that unified-search result contains the same canonical fields; exact balances still require `search_money` evidence.

This campaign sends only synthetic text. It does not use audio, the microphone, STT, TTS, the installed APK, the Redmi phone, SQLCipher persistence or the owner's data. It is a real provider/runtime/tool/database integration test, but not device acceptance or release readiness. It measures these Azerbaijani text scenarios only; it does not measure WER or speech/audio quality. An ignored, per-turn report is written to `apps/android/app/build/live-gemini-e2e/<run-id>.jsonl`; the report stores scenario/story IDs, status, safe error code, tool names/statuses, request-round count and duration, not utterances, model answers or API keys.

The owner explicitly authorized this live synthetic-data campaign. To reproduce it from `apps/android`, with the local debug Gemini key configured:

```sh
MEMORA_RUN_LIVE_GEMINI=1 MEMORA_LIVE_GEMINI_CONTINUE=1 ./gradlew :app:testDebugUnitTest --tests 'ai.memora.app.agent.GeminiLiveScenarioCampaignTest' --no-daemon --console=plain --max-workers=1
```

`MEMORA_LIVE_GEMINI_MAX_TURNS=10` limits a run to one complete story; limits must be multiples of ten. To continue with ten new stories, set `MEMORA_LIVE_GEMINI_START_STORY=11 MEMORA_LIVE_GEMINI_MAX_TURNS=100`; story numbering is 1-based. If quota interrupts a story, rerun that story from its beginning because each run has a fresh in-memory database. Reports include `campaign_revision=2`; combine completed chunks with `python3 tools/voice_eval/summarize_az_live_campaign.py`. The test paces requests to an average target near 5 requests/minute and stops on systemic provider/quota/auth failures. Actual Gemini limits vary by project/model and are visible in AI Studio; the public docs do not guarantee a fixed rate. A 1,000-case pass requires one latest passing row for every scenario ID; a quota stop or any failed row means incomplete/failed, never a pass.

**ACT-0230 status (2026-11-06):** the owner requested that the live campaign stop. While cancellation propagated, the current report `abc90a36-a0b7-4fb3-8990-55261593fb5f.jsonl` reached 44 turns: 41 passed and 3 failed. `AZ-LIVE-001-06`, `AZ-LIVE-003-06` and `AZ-LIVE-004-06` all saved an episode and read conversation evidence, but the generated episode summary omitted one required amount (`episode_sources_5_missing_1_unreadable_0`). Story results: S-001 9/10, S-002 10/10, S-003 9/10, S-004 9/10, S-005 4/10. This is a partial real Gemini text/runtime/database integration run using isolated synthetic data; the 1,000-turn campaign remains incomplete and was not resumed after the owner stop. No audio, STT/TTS, APK, phone, SQLCipher persistence, user data or billing was involved.

Passed IDs: `AZ-LIVE-001-01..05`, `AZ-LIVE-001-07..10`, `AZ-LIVE-002-01..10`, `AZ-LIVE-003-01..05`, `AZ-LIVE-003-07..10`, `AZ-LIVE-004-01..05`, `AZ-LIVE-004-07..10`, `AZ-LIVE-005-01..04` (41 total). The failure set is exactly `AZ-LIVE-001-06`, `AZ-LIVE-003-06`, `AZ-LIVE-004-06` (3 total).

The Settings picker was finalized with `gemini-3.5-flash-lite` (default), `gemini-3.1-flash-lite` and `gemini-3.6-flash`; the initial 3.8 option was removed after the prior direct API probe returned 503 twice for 3.8 and 200 for 3.6. Picker regression clicks 3.6 and verifies that selection persists. This separate host test does not continue the stopped live campaign.
