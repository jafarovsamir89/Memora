# ADR-023 — Exact source spans for case revisions

**Status:** Accepted engineering implementation for the current P3 slice
**Date:** 2026-11-04

## Context

Cases already retain the turn ID used to create or revise them, but their timeline does not identify which words in that turn support the change. Copying a model paraphrase into the case as if it were a quote would create false evidence. Profile facts now have an established exact-span pattern (ADR-022).

## Decision

- `create_case` and substantive `update_case` calls may include optional `source_quote`, copied verbatim from the current user message.
- The repository verifies a contiguous exact match against the current message and stores only nullable UTF-16 start/end-exclusive offsets on that case revision.
- The excerpt is derived from the canonical local conversation message for case timeline, UI, and agent reads. Quote text is not duplicated in the case revision.
- Missing, oversized, or non-matching text leaves ordinary `sourceTurnId` provenance intact and yields no exact excerpt.
- Room 20→21 adds nullable columns to `case_revision`; prior revisions retain null ranges.

## Consequences and limits

Source location is inspectable without introducing a second copy of conversation text. A match does not establish that the case title, summary, or interpretation is true, nor does it represent calibrated model confidence. A substantive case update is required; a quote alone cannot create a revision. Device migration and owner acceptance remain separate.

## Verification

Synthetic coverage includes an emoji before the quoted text (UTF-16 indexing), case creation and later revision readback, fake-provider tool contract, Room upgrade chain, and localized UI excerpt. See S61 and ACT-0188 in `docs/ACTION_LOG.md`.
