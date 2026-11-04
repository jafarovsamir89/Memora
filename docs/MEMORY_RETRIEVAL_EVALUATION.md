# M20-04 local retrieval evaluation

**Status:** baseline harness started; semantic retrieval is not implemented. Last updated 2026-11-03.

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
