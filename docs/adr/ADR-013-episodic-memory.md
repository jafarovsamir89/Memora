# ADR-013: source-backed episodic memory

**Status:** implementation decision for the local Android phase, 2026-11-03. **Scope:** M20-04. Related: [ADR-002](ADR-002-memory-provenance.md), [ADR-006](ADR-006-retention-and-deletion.md), [ADR-008](ADR-008-ai-actions.md), [MEMORY_AND_TRUST](../MEMORY_AND_TRUST.md).

## Decision

Persist compact conversation episodes in the existing SQLCipher Room database as derived summaries, separate from the immutable conversation turns and canonical domain records. Every episode has an app-derived stable ID, a monotonically increasing revision, and one to eight links to retained user turns in the same scope. The summary is never treated as a canonical fact: retrieval returns source turn IDs and the assistant must inspect the source turns before making a material personal-history claim.

The model may create or revise an episode only through `save_memory_episode`, a normal `PERSISTED_MEMORY` tool subject to the current memory-consent and commit policy. It must first find relevant source turn IDs in the encrypted archive. The app verifies that every source is an existing user turn in the active scope. The tool stores no copied transcript in the episode row. No sensitive traits may be inferred or persisted. The existing `save_note` remains the explicit path for a user-requested current-turn note, because the current turn is appended only after the model run.

Episode writes are transactional and idempotent for the same scope/source set. Each summary revision is retained in a separate history table; the current row is only the searchable projection. A turn delete, clear, or retention prune removes the episode and every revision in the same database transaction. A read also rejects/cleans any episode whose source link is no longer backed by a user turn. Revoking cloud-memory consent blocks episode search and writes through the existing tool gate; local archive and summaries remain local and follow the user's retention choice.

Search remains provider-neutral and local: bounded lexical matching over title/summary, followed by source verification. This is an incremental layer, not a claim of semantic/vector search or complete profile-memory consolidation. A different model provider uses the same catalog, runtime policy, repository and result contract.

## Consequences

- A small additional Room migration is additive; existing conversation, finance, case, task, and user data is preserved.
- A summary can be regenerated only from retained, scoped user turns. If any source is removed, the whole episode and its revision history are removed rather than silently keeping an ungrounded partial summary.
- Users can inspect source turns and delete the derived episode from Memory. Deleting the episode alone does not delete its conversation sources.
- Until Android SQLCipher migration/readback and owner field acceptance run, this is a verified host implementation only, not device/release acceptance.

## Acceptance

Synthetic tests cover scoped source validation, idempotent revision, local retrieval, deletion propagation, finite retention prune, consent denial, database transaction/readback, and schema migration preserving pre-existing rows. Device and live-provider tests remain separately gated by a connected owner device and existing Gemini consent/quota.
