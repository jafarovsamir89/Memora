# ADR-026 — Retrieve a person's linked cases independently

**Status:** Accepted implementation for the current M20-03 slice
**Date:** 2026-11-04

## Context

The person detail view previously fetched the newest 100 cases in the whole scope and only then filtered them by participant. A person's older case could therefore disappear when unrelated cases were newer. The UI also displayed only eight related cases without a way to expand the list.

## Decision

- Query active cases by an explicit, same-scope `case_participant` row in SQL, ordered newest first and bounded to 100 records.
- Keep case and participant rows canonical; do not copy case data into the person record or infer links from names.
- Show eight linked cases by default and provide a localized expand/collapse control when more are available.
- Keep the query local; no schema migration, model call, or network request is introduced.

## Consequences and limits

The person's case list is no longer displaced by unrelated recent cases and the owner can open all of the newest 100 explicitly linked cases. This is bounded UI retrieval, not unbounded archival pagination. The person timeline remains separately bounded to 40 visible entries. Device visual/accessibility and owner acceptance remain separate validation gates. See S64 and ACT-0194.
