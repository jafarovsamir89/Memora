# ADR-030: Retrieve explicitly linked cases for an exact person query

**Status:** Implemented and host-verified; owner/device acceptance pending.  
**Date:** 2026-11-04

## Context

`search_memory` could resolve a canonical person or an exact owner-managed alias, but case retrieval still depended on case text containing query words. A case explicitly linked to that person could therefore be missed when its title and summary used different wording.

## Decision

When a query contains the complete exact canonical person name or an active exact alias, include active same-scope cases joined through the existing `case_participant` relationship. Return the matched canonical person IDs and mark `retrieval_reason=explicit_person_case_link`. Preserve ordinary lexical case results and deduplicate by canonical case ID. Cap exact person resolution at three people; fetch at most 100 recent linked cases per person and load snapshots for at most 20 linked cases across the query. Indicate possible truncation in the existing activity truncation flag.

Do not resolve people from partial/fuzzy name tokens, retired aliases, foreign scopes or current canonical-name collisions. Do not create, repair, or infer participant links. Case source evidence remains the existing repository snapshot with exact quote verification.

## Limits

This is cross-link retrieval over already-confirmed case participants, not semantic search or full case pagination. Specialist `search_cases` remains the full case-timeline tool. Synthetic host coverage verifies that exact alias wording retrieves a case with unrelated text only through the explicit participant link, while unrelated and foreign-scope cases stay out. It does not replace field acceptance. See S68 and ACT-0202.
