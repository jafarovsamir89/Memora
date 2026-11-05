# ADR-027 — Expand the full person timeline in bounded UI steps

**Status:** Accepted implementation for the current M20-05 slice
**Date:** 2026-11-04

## Context

The person timeline merged canonical finance, explicit task links and case history, sorted newest first, then silently retained only the first 40 entries. As a result, older linked events were unavailable from the person card even though their source records remained stored and their case links were visible.

## Decision

- Preserve the existing 40 newest entries as the initial view.
- Keep the already assembled, deduplicated same-person timeline ordered newest-first and disclose older entries through a localized "show older" action that reveals the next 40, repeating until the full loaded timeline is visible.
- Provide a collapse action that returns the visible list to the newest 40.
- Keep source IDs and detail navigation unchanged; do not copy records or change source-of-truth repositories.
- No schema migration or network/model call.

## Consequences and limits

Older canonical entries can be reached without dumping a long timeline on first open. The backing set is still bounded by each source query (including at most 100 related cases) and remains a local screen projection; it does not claim history beyond retained source records. Host Compose regression covers an older revision beyond the first 40. Physical-device visual/accessibility acceptance remains open. See S65 and ACT-0196.
