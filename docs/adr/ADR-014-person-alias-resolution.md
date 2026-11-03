# ADR-014: scoped aliases for explicit person identity resolution

**Status:** accepted engineering decision for the native local-first stage, 2026-11-03. Implements part of M20-03 only; it does not complete identity resolution or permit automatic person merges.

## Decision

- Store an alias as an encrypted, scope-local row attached to exactly one existing active `PERSON` domain object. Do not create a second person, rewrite a finance name, or merge two identities when an alias is added.
- Normalize only Unicode composition, case, and repeated whitespace for exact lookup. Do not use phonetic, transliteration, edit-distance, or partial-prefix matching for identity resolution. The owner must enter or review the alias.
- A normalized alias has at most one active owner within a scope. A collision with another active alias or another person's canonical name is a conflict; the UI exposes the conflict without revealing out-of-scope records.
- Store append-only alias revisions for add, retire, and restore, with local actor, optional retained user-turn source ID, and timestamp. Retire is reversible; restore rechecks collisions. No alias text, name, or source text is emitted to logs.
- An alias can resolve a finance query or write only when the domain person has an existing owner-confirmed `person_identity_link` to one finance person. If the link is absent or ambiguous, return a conflict requiring owner review; never create a second ledger person from the alias.
- `search_memory` may return an exact alias match and its canonical person ID only under the existing persisted-memory consent and scope policy. Aliases do not turn task/calendar `personLabel` strings into canonical links.
- Room migration 15→16 creates empty alias and revision tables and indexes. It does not backfill, rename, or rewrite existing people, finance rows, tasks, memories, or links. Destructive migration remains disabled.

## Alternatives rejected

- Storing aliases as JSON in `domain_object.body` was rejected because it has no uniqueness, transaction, revision, or query contract.
- Automatically treating a matching name, task label, or model-generated variant as an alias was rejected because it could redirect financial history or actions to the wrong person.
- Linking an alias directly to a finance row without the existing owner-confirmed identity bridge was rejected because it bypasses the established confirmation boundary.

## Required verification

Synthetic tests must cover exact normalized lookup, one active alias per scope, same-name collision, cross-scope isolation, owner-confirmed finance reuse, missing-link conflict with zero writes, retire/restore conflict, and restart/readback through the SQLCipher migration probe. Device install and real-user acceptance remain separate gates.
