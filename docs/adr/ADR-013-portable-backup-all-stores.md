# ADR-013: portable backup covers all durable local stores

- **Status:** Engineering direction selected; implementation and release acceptance pending.
- **Date:** 2026-11-03
- **Context:** [ACT-0165](../PORTABLE_BACKUP_SCOPE_GAP.md)
- **Scope:** Android local backup and restore only. This ADR does not authorize cloud sync, billing, or access to personal data.

## Context

Memora has several durable local stores protected by different Android Keystore aliases. A real synthetic SAF round-trip restored the Room/SQLCipher database but omitted a saved Inbox capture from `captures-prototype.bin`. Active task events and voice-memory also use separate files (`tasks-prototype.bin`, `voice-memory.bin`). The current screen exports only the SQLCipher AgentDatabase. Android Keystore keys are non-exportable and device-bound, so copying encrypted files and their aliases cannot create a usable portable backup on another device. Existing per-store JSON export methods are not a suitable full-state format: capture export omits tombstones and task export omits deleted-ID state.

## Decision

Portable backup represents a consistent generation of every durable, user-owned local source that Memora promises to preserve. Unless a future owner-approved policy explicitly excludes a store, the bundle includes:

1. The SQLCipher AgentDatabase snapshot and its random snapshot key inside the outer encrypted container.
2. Typed logical snapshots of Inbox captures, task events, and voice-memory, including revisions, tombstones and deletion state required for correct readback.
3. A versioned manifest that identifies included stores, source schema versions, bounded lengths and digests.

The existing passphrase-protected authenticated outer container remains the portability boundary. Export reads each store through a typed, complete snapshot API (including tombstones/revisions) and streams the bundle into that container; it never exports or copies Android Keystore key material. Import validates/authenticates every section in isolation, then writes replacement data using new or existing device-local Keystore keys. Restore stages every store and activates all of them as one recoverable generation. Startup recovery must finish activation of the complete candidate generation or restore the complete prior generation before any screen or agent can read data. Mixed generations are forbidden.

The current importer writes the decrypted outer payload to an app-private temporary file before parsing. That is safe only while its inner payload remains independently encrypted. The full-store bundle must not put clear captures/tasks/voice-memory into that temporary file. Extend the container/parser so authenticated data is streamed into locally encrypted candidate stores, or use independently authenticated encrypted sections with a verified manifest. Plain user content may exist only in bounded process memory during conversion; cleanup must clear partial staging files and temporary key bytes.

## Alternatives considered

- **Keep exporting SQLCipher only:** rejected because user-facing Inbox content silently disappears after an otherwise successful restore.
- **Copy each encrypted file and Keystore key envelope:** rejected because Keystore keys are not portable; this also risks restoring ciphertext that cannot be decrypted on another device.
- **Move every file-backed store into Room before adding backup:** deferred. It could simplify future snapshots but requires separate schema/migration work and raises more risk to existing data. The bundle can establish correct portability without first merging storage architecture.
- **Restore files independently without a shared journal:** rejected because process death can leave Memora with mismatched generations across domains.

## Required implementation gates

- Inventory every active and legacy store, key alias/envelope, schema, migration, retention/tombstone rule and source-of-truth screen before code changes.
- Add a consistent snapshot barrier for foreground, background and scheduled writers.
- Add typed, bounded adapters for each store; no raw Keystore key export and no plaintext disk staging outside protected app-private files.
- Preserve tombstones and revisions; do not reuse current human-facing capture/task JSON exports as a complete backup format.
- Add all-store preflight, journaled activation, rollback and cleanup with fault injection at every durable step.
- Exercise create → SAF export → mutate/delete → SAF import → cold restart for one synthetic marker in every store; verify both restored and removed markers.
- Reject wrong passwords, missing/tampered/unsupported store sections and incomplete provider results before changing active data.

## Consequences

- M20-02 and release readiness remain partial until every included store passes round-trip and failure recovery.
- The Room-only flow remains technically useful for Room snapshot recovery, but must not be presented as a complete Memora backup.
- Cross-store consistency becomes an explicit part of the local data model. New persistent stores must update the bundle manifest and acceptance suite.
- Backup output remains a user-managed encrypted file. No automatic cloud backup or paid service is introduced.
