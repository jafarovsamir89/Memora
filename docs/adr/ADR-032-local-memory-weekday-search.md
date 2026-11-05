# ADR-032: Conservative weekday and week-range memory search

**Status:** Implemented and host-verified.
**Date:** 2026-11-04

## Context

M20-04 local `search_memory` could match exact ISO dates and a small set of relative-day words, but phrases such as “last week” or “keçən cümə” were treated as ordinary lexical tokens. This made useful dated finance, task and calendar sources hard to retrieve and could not be safely solved by fuzzy name/word matching.

## Decision

Add a provider-independent, deterministic parser before lexical activity retrieval. It resolves ISO weeks as Monday–Sunday in the configured profile timezone and supports explicit previous/current/next week phrases in Azerbaijani, Russian and English. It also supports explicitly qualified weekday phrases (for example `last Friday`, `keçən cümə`, `в прошлую пятницу`). Previous/next weekdays are strict; current-week weekdays resolve within the current ISO week. Unqualified weekdays remain lexical because they can refer to past or future. Date-only temporal queries are allowed and match dated canonical activity without inventing a semantic topic.

The parser only constrains read-only local activity retrieval. It does not write or alter records, call Gemini, reinterpret source dates, or change persisted schemas. Timestamped sources are still normalized by the existing profile-timezone functions; same-scope repository queries remain the boundary. Existing ISO-date and today/tomorrow/yesterday matching remain supported.

## Acceptance and limits

Synthetic fixed-clock tests must verify AZ/RU/EN previous/current week ranges, explicit previous weekdays in all three languages, exclusion of adjacent ranges/days and foreign-scope rows across finance/tasks/calendar, and unchanged canonical data. This is a bounded phrase set, not general natural-language date understanding; unspecified weekday queries, arbitrary date ranges and semantic retrieval remain open. See S70 and ACT-0206.
