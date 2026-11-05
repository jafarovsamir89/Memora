# ADR-033: Retrieve cases through an exact project link

**Status:** Implemented and host-verified.
**Date:** 2026-11-04

## Context

Cases already preserve canonical project objects as evidence, but `search_memory` followed explicit participant links only for exact PERSON queries. A project query could therefore miss an active case whose title and excerpts use different wording.

## Decision

When local same-scope project search finds exactly one active PROJECT whose complete canonical title occurs as an exact phrase in the query, `search_memory` may follow active `case_evidence` rows where `sourceKind=domain_object` and `sourceId` equals that project ID. Multiple exact project titles suppress link traversal; a full 100-hit candidate window is treated as potentially ambiguous and disclosed as truncated. The repository independently verifies the source remains an active PROJECT in the requested scope. It returns existing active same-scope case records only; it never creates or repairs a link. Results retain canonical source evidence and include `matched_project_ids` plus `retrieval_reason=explicit_project_case_link` (or the combined person/project reason where both links apply).

Lookups are bounded to three exact projects, at most 100 recent case candidates per project and 20 explicit linked cases total, combined with the existing person-link cap. Potential truncation is exposed via the existing flag. Fuzzy names, project labels, foreign scope, deleted projects/cases and relationship writes are excluded. No schema or storage migration.

## Acceptance and limits

Synthetic end-to-end coverage must show a case with unrelated wording found through an explicit same-scope project evidence link, canonical project provenance retained, and unrelated/foreign cases excluded. This does not implement project aliases, fuzzy resolution or owner review on a device. See S71 and ACT-0208.
