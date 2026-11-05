# ADR-022 — Exact source spans for profile memory

**Status:** Accepted engineering implementation for the current P3 slice  
**Date:** 2026-11-04

## Context

Profile facts already retain the ID of the user message that supports them, but the Memory screen can only show the whole message. A source link proves where a fact came from; it does not identify the exact words supporting it. Storing a model-generated paraphrase as a quotation would create false evidence.

## Decision

- `save_profile_fact` and `edit_profile_fact` may provide an optional `source_quote` copied verbatim from the current user message.
- Android verifies the quote against the actual current message and calculates UTF-16 start/end-exclusive offsets locally. Only offsets are stored; the quote is not duplicated in the database.
- Missing, oversized, or non-matching quotes produce no span. The source-turn provenance remains intact, and the UI continues to show the original message without calling it an exact excerpt.
- The repository exposes the span with its source turn. UI derives the excerpt from the retained local message and labels it as exact only when both offsets are valid.
- Room 19→20 adds two nullable integer columns. Existing source links migrate unchanged with null spans. No clear text or user data is rewritten.
- Synthetic tests cover Unicode offsets, exact readback, invalid quote handling, provider-neutral tool contract and UI display. Device migration/owner acceptance remain separate.

## Consequences

Evidence can be inspected precisely while original conversation text remains canonical and local. This does not establish factual correctness of a model-selected fact or a confidence score; a matching quote is a location/provenance check, not semantic proof. Existing rows and providers omitting `source_quote` remain compatible.
