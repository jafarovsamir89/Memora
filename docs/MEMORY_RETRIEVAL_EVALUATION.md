# M20-04 local retrieval evaluation

**Status:** lexical host harness evaluates 96 synthetic queries against explicit relevant sets over 30 in-scope task sources. ACT-0168 adds exact ISO-date retrieval across dated canonical activity, using the owner's profile timezone for instants and a strict adjacent-day exclusion. This does not implement natural-language date parsing, date ranges, or semantic retrieval. Last updated 2026-11-03.

## ACT-0168 — Strict explicit-date retrieval for canonical activity

`search_memory` now indexes the stored local calendar date alongside lexical fields for ledger events, task due dates and calendar events. Finance instants are converted to the user's configured profile timezone; local task/calendar times use their stored zone; date-precision calendar events remain date-only. When a query contains a valid full `YYYY-MM-DD`, activity ranking requires an exact normalized date match, so matching only the year/month cannot return a neighboring day. No schema or data migration was needed.

Synthetic `AgentFakeProviderUnifiedMemoryTest.explicitIsoDateFindsOnlyDatedFinanceTaskAndCalendarSources` seeds same-day and next-day ledger, task and calendar sources. A UTC finance event at `2026-11-03T21:30Z` is found under `2026-11-04` for `Asia/Baku`; events at the next local date are excluded. The focused test and full suite passed. This establishes literal ISO-date behavior for these canonical activity sources, not natural language such as “tomorrow”, date ranges, every conversation/profile-memory path, or person/project relevance. Retrieval remains lexical.

## ACT-0164 — Add a judged near-miss per language class

Added negative queries `BMW ödəniş`, `BMW оплата`, and `BMW payment`, each labeled with an empty relevant-source set because the synthetic corpus has no BMW payment task. The 31 positive queries/class retain complete labels within the 30-task universe. Recall@5 is averaged over positive queries only; fixed P@5, P@1, P@returned, MRR and result counts include the negative query, which receives no relevant credit. The focused test passed:

| Class | Positive Recall@5 | Fixed P@5 | P@1 | P@returned | Mean returned | MRR | False-positive IDs | Near-miss with any hit | Near-miss false-positive IDs |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| AZ | 1.000 | 0.206 | 0.969 | 0.409 | 2.97 | 0.969 | 62 | 1/1 | 4 |
| RU | 1.000 | 0.206 | 0.969 | 0.790 | 1.81 | 0.969 | 25 | 1/1 | 3 |
| Mixed AZ/RU/EN | 0.984 | 0.194 | 0.906 | 0.386 | 2.78 | 0.938 | 58 | 1/1 | 4 |

Every class returns several tasks for its negative query. This confirms the current lexical search can surface unsupported candidates when a query combines a matching entity term (`BMW`) with a mismatched action (`payment`). The test documents a quality gap; it does not change the ranker or establish a suitable rejection threshold. All judgments remain synthetic, activity-only and narrow.

## ACT-0163 — Full judgments for the existing synthetic corpus

The regression retains the existing 30 lexical variants per AZ/RU/mixed class and adds one broad multi-source query per class: `görüşlər` expects AZ tasks `az-01/04/09`, `встречи` expects `ru-01/04/09`, and `meeting` expects mixed tasks `mx-04/09`. ACT-0164 also adds one empty-relevance near-miss per class. For each query, `relevantIds` lists all expected task sources, and the other task IDs in the 30-source synthetic evaluation universe are treated as non-relevant. Every returned task is still checked for source/revision provenance, duplicate IDs, and cross-scope leakage. Thus precision is fully judged for this narrow synthetic corpus; this does not establish relevance completeness for real user intents.

Focused test `syntheticRetrievalCorpusReportsMetricsForThirtyAZRuAndMixedQueries` passed with 32 queries/class (31 positive, one near-miss):

| Class | Recall@5 | Fixed P@5 | P@1 | P@returned | Mean returned | MRR | False-positive IDs | Relevant IDs missing at 5 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| AZ | 1.000 | 0.206 | 0.969 | 0.409 | 2.97 | 0.969 | 62 | 0 |
| RU | 1.000 | 0.206 | 0.969 | 0.790 | 1.81 | 0.969 | 25 | 0 |
| Mixed AZ/RU/EN | 0.984 | 0.194 | 0.906 | 0.386 | 2.78 | 0.938 | 58 | 1 |

These results include one near-miss per class (each with at least one hit and 3–4 incorrect candidates) plus one missed member of the broad mixed-language relevant set. They are not a reason to tune ranking yet: the corpus has only 10 synthetic task intents/class, one broad query and one near-miss per class, no real user evaluation, and no person/project/time cases. The next useful step is expand independently reviewed paraphrase/person/project/time query categories and source sets, then compare candidate retrieval changes against those labels. No production ranking behavior, model, schema or vector index changed.

## ACT-0156 — 90-query lexical baseline

The host regression `syntheticRetrievalCorpusReportsMetricsForThirtyAZRuAndMixedQueries` checks 30 AZ, 30 RU and 30 mixed query variants over 10 stable synthetic task sources per language. Variants include case/diacritic changes, Azerbaijani inflections, RU inflections and alternate AZ/RU/EN word order/phrasing. One same-title source exists in a different scope and must never leak. Every returned task must carry its canonical source ID and exact task revision (`taskId:1`). These are 90 synthetic lexical cases, not 90 independent user intents or a complete relevance corpus.

Measured in ACT-0156 on the prior narrow one-relevant-source label set (P@5 used fixed denominator `k=5` even if fewer than five tasks were returned):

| Query class | Queries | Recall@5 | Precision@5 | MRR | Cross-scope leaks | Missing/stale provenance |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Azerbaijani | 30 | 1.000 | 0.200 | 1.000 | 0 | 0 |
| Russian | 30 | 1.000 | 0.200 | 1.000 | 0 | 0 |
| Mixed AZ/RU/EN | 30 | 1.000 | 0.200 | 0.983 | 0 | 0 |

The prior ACT-0156 `Precision@5=0.20` used fixed denominator `k=5`, while the set of relevant sources was incomplete; do not interpret it as four of five returned results being irrelevant. ACT-0161 introduced P@1 / P@returned / mean results. ACT-0163 now supplies full judgments only for the current synthetic task universe and reports false positives; the table above supersedes its precision values for this corpus. The negative `car servicing meeting` case still confirms the lexical mode does not provide semantic/paraphrase recall. All measurements are from a host in-memory encrypted Room test DB, not an Android device, and use synthetic topics only.

## What the current test proves

`AgentFakeProviderUnifiedMemoryTest.localMemoryRetrievalFindsExactAzerbaijaniRussianAndMixedButDoesNotClaimParaphraseRecall` seeds three synthetic task titles in the encrypted in-memory Room test DB:

| Query | Expected source | Result |
|---|---|---|
| `BMW təmir görüşü` | `az-bmw-task` | Passed |
| `BMW ремонт встреча` | `ru-bmw-task` | Passed |
| `BMW təmir appointment` | `az-bmw-task` and `en-bmw-task` | Passed |
| `car servicing meeting` | no lexical match | Passed (empty result) |

The result reports `match_mode=ranked_lexical`. These are exact-word checks of the current normalizer/ranker; the negative paraphrase case confirms that unrelated wording is not retrieved by current search. No semantic equivalence, production quality, or device performance is demonstrated.

## Evaluation corpus required before selecting an embedding model

Use only synthetic, reviewed records with stable expected source IDs. Include at least 30 queries each for AZ, RU, and mixed AZ/RU/EN, spanning exact wording, Azerbaijani inflections/transliteration, paraphrases, person/project references, time-related questions, and near-miss distractors. Include same-name people and cross-scope distractors. Every expected hit must name the canonical source and revision that should be returned; every negative query must list sources that must not be returned.

Record lexical-only and lexical+semantic results on the same corpus. Report Recall@5, Precision@5, MRR, wrong-scope/source count, stale-revision count, provenance completeness, p50/p95 latency, peak memory and vector-index bytes. Acceptance targets for an eventual model path: Recall@5 ≥ 0.90 for AZ/RU/mixed independently, Precision@5 ≥ 0.90, zero cross-scope results, zero deleted-source results, 100% provenance for returned facts, and device resource use measured on the Redmi Android 13 class. These are proposed engineering exit criteria, not current test results.

## ACT-0159 — Rejected lexical coverage cutoff experiment

To reduce low-confidence lexical matches, the synthetic activity ranker was temporarily gated to require at least 75% of distinct query terms to match. On the same 90-query host corpus, Recall@5 fell from `1.000` to AZ `0.933`, RU `0.833`, and mixed `0.867`; Precision@5 also fell from `0.200` to `0.187`, `0.167`, and `0.173`. MRR fell in lockstep with recall. The filter was reverted and is not part of the product. This experiment shows that a simple term-coverage cutoff trades away expected retrieval without solving the low Precision@5 signal. Keep lexical behavior unchanged until the relevance judgments and appropriate `k` are clarified by a better corpus; do not claim semantic search.

## ACT-0161 — Result-count and top-rank diagnostics

The 90-query host regression now also reports `Precision@1`, labeled-source precision among IDs actually returned (`P@returned`, zero for empty results), and mean number of task results. It asserts no duplicate task IDs per query in addition to scope isolation and canonical task revision provenance. Baseline values: AZ `P@1=1.000, P@returned=0.412, meanReturned=2.90`; RU `1.000, 0.809, 1.73`; mixed `0.967, 0.396, 2.77`. This clarifies that the earlier fixed-denominator `Precision@5=0.20` mixed retrieval quality with under-filled top-five lists; the lower `P@returned` values still use incomplete one-source relevance labels and cannot prove other results irrelevant.

## Model/runtime boundary

- Keep embeddings local. Do not send personal memory to Gemini or another embedding API under this evaluation.
- Google documents EmbeddingGemma 1 as multilingual and on-device, but its use is under Gemma Terms. Do not bundle or distribute it without a separate license/terms review.
- Google documents EmbeddingGemma 2 under Apache 2.0. Current official MediaPipe retrieval examples found in this review are Web APIs; Android's LiteRT Kotlin runtime can execute model tensors, but a production-ready Android tokenizer+EmbeddingGemma pipeline has not been verified here.
- Do not select or add a model until tokenizer/model compatibility, model asset size, license, AZ/RU/mixed relevance, min-API behavior, CPU latency, peak RAM and APK/download lifecycle are measured.
- Persist any future vector only inside SQLCipher, keyed by scope + canonical source ID + source revision + model version. Source deletion removes vectors; corrections invalidate old revision vectors before they can be retrieved. Rebuilding the index must remain possible from allowed canonical sources.

## Official references

- [EmbeddingGemma model and deployment overview](https://ai.google.dev/gemma/docs/embeddinggemma)
- [Gemma Terms](https://ai.google.dev/gemma/terms)
- [LiteRT Kotlin API source](https://github.com/google-ai-edge/LiteRT/tree/main/kotlin)
- [MediaPipe Tasks Web retrieval examples](https://github.com/google-ai-edge/mediapipe/tree/master/mediapipe/tasks/web/retrieval)
