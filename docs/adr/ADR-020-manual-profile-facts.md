# ADR-020: Owner-authored profile facts

**Status:** Engineering decision within the approved local, user-controlled memory product.
**Date:** 2026-11-04

## Context

The Memory screen already lists, edits and deletes explicit profile facts. New facts could be created only by the agent from a conversation, leaving no direct UI path for the owner to maintain their own profile memory.

## Decision

1. Allow the owner to add a profile fact directly in the local Memory screen, with an explicit category (`identity`, `preference`, `goal`, `context`, or `other`).
2. Record direct UI entry with provenance `user_entered`, a source-less revision, and no fabricated conversation turn or transcript. Conversation-sourced facts retain their exact existing source-turn provenance.
3. Reject a same-scope duplicate with the same normalized text and category. Do not silently merge sources or overwrite an existing fact.
4. Keep edits revisioned under `user_edited`; deletion removes the fact and its revision/source rows under the existing explicit confirmation flow. No Room schema change is needed because revision provenance is already a string and sourceTurnId is nullable.
5. Keep profile data local to the current scoped SQLCipher store and preserve existing consent, retention, deletion, and derived-episode invalidation behavior.

## Consequences

- The owner can create, review, correct and remove profile memory without relying on model extraction.
- A manually entered fact has no conversation source; UI must say so rather than implying that a source was checked. For conversation-backed facts, UI distinguishes source loading, an available user message, and a missing message; it never mislabels a loading state as manual entry.
- Profile category is still owner-selected. This does not add automated sensitive-trait inference or automatic fact extraction.

## Verification

Synthetic repository coverage verifies provenance, no source turn, same-scope visibility, duplicate rejection and versioned edits. Compose coverage exercises manual creation/source-less provenance and verifies conversation-backed facts still expose the original user message. Physical-device accessibility and owner acceptance remain separate.
