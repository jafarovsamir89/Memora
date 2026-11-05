# ADR-024 — Include verified case excerpts in unified memory search

**Status:** Accepted implementation for the current M20-04 retrieval slice
**Date:** 2026-11-04

## Context

The case detail and `search_cases` paths expose exact excerpts after local source-span verification. The broad `search_memory` case hit included only the case summary and linked canonical sources, so a natural-language question could find the case without giving the model the original words behind its revisions.

## Decision

- Case candidate matching in `search_memory` also tokenizes the exact excerpts from up to three newest revisions, so a case can be found by its anchored source wording even when that wording is absent from its title/summary.
- Each matching case returned by `search_memory` may include up to three newest revision excerpts whose offsets resolve against the retained canonical USER message.
- The result includes the source turn ID and an explicit `exact_match_verified` flag. Text is derived at read time; no quote is copied into case data.
- Missing, malformed, expired, or mismatched source spans are omitted. Cross-scope messages are not read.
- The assistant may use excerpts as direct wording evidence, but must not treat the excerpt as proof that the case's interpretation is true. `search_cases` remains the specialist path for a complete timeline.
- Existing consent-gated `search_memory` routing and response envelope remain in place; no migration or tool write was added.

## Consequences and limits

The first answer can use supporting source wording without a second tool round, and source wording can retrieve a case. Matching remains lexical and bounded; only the newest three anchored revisions contribute, so older context can be missed. This does not implement a claim-level verified-answer gate or semantic retrieval. See S62 and ACT-0190.
