# M20-04 local retrieval evaluation

**Status:** lexical evaluation harness covers 90 synthetic query variants; semantic retrieval is not implemented. Last updated 2026-11-03.

## ACT-0156 — 90-query lexical baseline

The host regression `syntheticRetrievalCorpusReportsMetricsForThirtyAZRuAndMixedQueries` checks 30 AZ, 30 RU and 30 mixed query variants over 10 stable synthetic task sources per language. Variants include case/diacritic changes, Azerbaijani inflections, RU inflections and alternate AZ/RU/EN word order/phrasing. One same-title source exists in a different scope and must never leak. Every returned task must carry its canonical source ID and exact task revision (`taskId:1`). These are 90 synthetic lexical cases, not 90 independent user intents or a complete relevance corpus.

Measured on the current lexical implementation (P@5 uses fixed denominator `k=5` even if fewer than five tasks were returned; one labeled relevant source per query):

| Query class | Queries | Recall@5 | Precision@5 | MRR | Cross-scope leaks | Missing/stale provenance |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Azerbaijani | 30 | 1.000 | 0.200 | 1.000 | 0 | 0 |
| Russian | 30 | 1.000 | 0.200 | 1.000 | 0 | 0 |
| Mixed AZ/RU/EN | 30 | 1.000 | 0.200 | 0.983 | 0 | 0 |

The original `Precision@5=0.20` must not be read as “four of five returned results are irrelevant”: the ranker returns fewer than five task results for many queries, and this metric keeps denominator `k=5`. ACT-0161 added `Precision@1`, the fraction of returned IDs matching the one labeled source (`P@returned`), mean result count and duplicate-source assertions. Baseline values are AZ `P@1=1.000, P@returned=0.412, meanReturned=2.90`; RU `1.000, 0.809, 1.73`; mixed `0.967, 0.396, 2.77`. The single expected source is the only judged relevant source, so `P@returned` is still a labeled precision estimate, not complete relevance truth; additional results may be relevant but unjudged. Do not declare precision accepted. The existing `car servicing meeting` negative still confirms the current mode does not provide semantic/paraphrase recall. These metrics come from the host in-memory encrypted Room test DB: no Android-device latency, memory, full-query diversity, personal data, or embedding model was evaluated.

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
