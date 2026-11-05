# ADR-025 — Local search and status filters for the case list

**Status:** Accepted implementation for the current M20-05 slice
**Date:** 2026-11-04

## Context

The Cases screen could display real cases and their linked sources, but it had no way to narrow the list. The repository provides scope-filtered lexical search over case titles, summaries, next actions, participants and up to three newest locally verified source excerpts. Searching only the first 100 recent cases would hide older active work.

## Decision

- Use `ProtectedCaseRepository` search for the screen; do not add a second search implementation or send the query to Gemini/network. Search scans all non-deleted cases in 100-row SQL pages inside one read transaction and retains the top matches.
- Debounce non-empty input briefly, cap the UI query length at 200 characters, and display at most 30 ranked search matches.
- Apply status filters (`all`, `open`, `waiting`, `completed`, `cancelled`) in same-scope repository queries. With no query, show the newest 100 records for the selected status; with a query, scan all non-deleted cases in that status.
- Return at most 30 ranked search matches, and explain this bound in the UI. No schema or stored-data changes.
- Localize search, filters, empty results and clear-filters action in Azerbaijani, Russian and English.

## Consequences and limits

The owner can find old cases using exact source wording and quickly separate ongoing from finished work. Retrieval remains lexical; the search output is top-30, and the unfiltered/filtered list is top-100. A full scan may take longer as the number of cases grows and does not establish semantic relevance. See S63 and ACT-0192.
