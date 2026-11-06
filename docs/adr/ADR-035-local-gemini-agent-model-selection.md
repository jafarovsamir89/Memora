# ADR-035: Local Gemini model selection for the assistant gateway

**Status:** Accepted for the Android test client; implementation and verification recorded in ACT-0230.
**Date:** 2026-11-06

## Context

The Android test client currently hard-codes `gemini-3.5-flash-lite` for agent tool calls. Google applies separate, changeable model/project quotas; a model rate limit can stop the assistant even while another Gemini model remains available. The owner asked to choose an AI model in Settings so they can switch manually when the selected model is limited.

## Decision

- Add a curated picker for the assistant's text/reasoning and tool-calling model only. Options are stable Gemini IDs verified in Google's current function-calling documentation: `gemini-3.5-flash-lite` (existing default), `gemini-3.1-flash-lite`, and `gemini-3.6-flash`.
- Keep the selection in local app preferences. A clean installation defaults to the current `gemini-3.5-flash-lite`; app updates preserve the selection. No user database, SQLCipher schema, API key, or server state changes.
- Read the selected ID when each agent turn is created and pass it through the existing `GeminiAgentModelGateway`. Keep the same model for all tool-call rounds within that turn and use it on both the direct Gemini REST and Firebase AI Logic gateway routes.
- When the agent reports `model_rate_limited`, show an explicit Settings shortcut. The owner chooses another model; there is no silent automatic fallback or unapproved provider change.
- Keep speech transcription and speech synthesis on their existing separate routes; this picker does not change them.
- Model availability and quotas are controlled by Google and may change. Do not hard-code quota figures or promise that a second model is available.
- The prior project's synthetic Developer API probe returned HTTP 503 twice for `gemini-3.8-flash`, while `gemini-3.6-flash` returned HTTP 200 for synthetic text and silent WAV. Therefore use 3.6 as the current general-model alternative. This is endpoint availability evidence only, not language, tool-quality, or production reliability evidence. Gemini 3.6's documented default sampling configuration is retained.

## Alternatives considered

- Fetch and display every model returned by `models.list`: rejected because a general generation-capable model list can include models unsuitable for this app's tool-calling contract.
- Switch providers or automatically retry another model: rejected because the owner asked to select a model in Settings and has only approved Gemini for this test route. Automatic switching would make behavior less predictable and could obscure quota/auth errors.
- Keep the model fixed: rejected because the owner needs to continue testing when the current model is limited.

## Acceptance

- The settings UI is localized in Azerbaijani, Russian, and English; options are accessible and announce selected state.
- Selection persists across screen recreation and is used by the next assistant turn through `AgentModelGateway`; existing tool schemas, repositories, consent policy, and local data stay unchanged.
- Invalid or missing stored values resolve to the existing default; a persistence failure is shown to the user.
- A rate-limited assistant error exposes navigation to model selection.
- Tests cover catalog validation, preference persistence, model propagation, and UI selection. Device and live-provider acceptance remain separate.

## References

- [Gemini function-calling supported models](https://ai.google.dev/gemini-api/docs/generate-content/function-calling#supported-models)
- [Gemini 3.1 Flash-Lite model](https://ai.google.dev/gemini-api/docs/models/gemini-3.1-flash-lite)
- [Gemini 3.6 Flash model](https://ai.google.dev/gemini-api/docs/models/gemini-3.6-flash)
- [Gemini 3 model generation guidance](https://ai.google.dev/gemini-api/docs/gemini-3)
- [Gemini function-calling supported models](https://ai.google.dev/gemini-api/docs/generate-content/function-calling#supported-models)
- [Firebase AI Logic supported models](https://firebase.google.com/docs/ai-logic/models)
- [Gemini API rate limits](https://ai.google.dev/gemini-api/docs/rate-limits)
