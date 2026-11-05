# ADR-028 — Suggest task links from exact saved person labels

**Status:** Accepted implementation for the current M20-03 slice
**Date:** 2026-11-04

## Context

Tasks preserve a legacy `personLabel` string, while the person card stores a stable domain PERSON and reversible, owner-confirmed `task_person_link`. The link picker previously listed every task without using that contextual clue, making explicit identity resolution harder. The label itself is not proof of identity and must never silently create a canonical link.

## Decision

- In the person link picker, prioritize tasks whose saved `personLabel` exactly matches the selected person's current title or one of that person's active owner-managed aliases using the same normalization contract as alias storage.
- Label those rows as suggestions and state that owner confirmation is required.
- Keep unmatched tasks available in a separate section.
- Selecting a candidate continues into the existing confirmation dialog. Only that confirmation writes the revisioned `task_person_link`.
- Do not change task records, infer fuzzy aliases, auto-link, call a model/network, or migrate the database.

## Consequences and limits

Explicit task/person resolution becomes easier without changing identity safety. Exact text can still be coincidental, so it remains a suggestion rather than a confirmed fact. Suggestions are same-scope because both tasks and person are loaded from the active owner scope. See S66 and ACT-0198.
