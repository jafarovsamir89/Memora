# ADR-031: Page the Cases list and ranked search

**Status:** Implemented and host-verified; owner/device acceptance pending.  
**Date:** 2026-11-04

## Context

The Cases screen silently stopped at 30 ranked search results and 100 unfiltered/status-filtered records. There was no way to reach older results. Expanding all results in the existing `ScreenColumn` would compose an unbounded number of cards and use excessive memory.

## Decision

Show one page of 30 records at a time with explicit newer/older controls. Fetch one extra record to know whether another page exists. The unfiltered/status-filtered list uses a same-scope stable SQL order and bounded offset. Search retains the deterministic ranked window and accepts an offset; ranking ties end with canonical `caseId` so adjacent page boundaries are repeatable. Reset to the first page when query, status or case state changes. Bound search ranking to 1,050 results and list offsets to 99,990; disclose the limit when more matches remain. No records are copied or changed by browsing.

## Limits

Each search page re-evaluates the current local corpus in a read transaction; if source records change while the user is paging, the ranking may shift. This is local search pagination, not a full result snapshot. Synthetic tests cover repository boundaries/order and Compose older/newer navigation. Device performance and accessibility review remain open. See S69 and ACT-0204.
