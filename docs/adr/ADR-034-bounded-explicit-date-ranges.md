# ADR-034: Bounded explicit date ranges in memory retrieval

**Status:** Implemented and host-verified.
**Date:** 2026-11-04

## Context

`search_memory` can filter local dated activity by exact ISO dates, relative-day phrases, ISO week phrases and explicitly qualified weekdays. A query that clearly names a start and end date still matches only endpoint dates as ordinary exact-date terms, which can miss events between them and misrepresent the requested interval.

## Decision

Parse explicit ISO date intervals before lexical activity retrieval. Accept English `between DATE and DATE` / `from DATE to DATE`, Russian `между DATE и DATE` / `с DATE по DATE`, Azerbaijani `DATE ilə DATE arasında`, and ISO `DATE – DATE` / `DATE..DATE`. Both endpoints are inclusive and interpreted as local calendar dates for the existing profile-timezone retrieval. Cap a range at 366 inclusive days. Reject malformed, reversed and longer intervals with the safe code `date_range_invalid_or_too_wide`; never fall back to matching only endpoint dates. Ordinary exact-date, relative-date and week behavior remains unchanged. Search is read-only, same-scope, provider-independent and adds no schema/index/model call.

## Acceptance and limits

Fixed-clock synthetic tests cover both endpoints and an interior interval across finance, task and calendar sources; exclude adjacent dates; exercise every supported syntax and ensure invalid/oversized intervals are rejected. This is an explicit ISO-date interval feature, not natural-language month/day parsing, date inference, semantic retrieval or owner/device acceptance. See S72 and ACT-0210.
