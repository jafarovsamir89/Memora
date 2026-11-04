# Portable backup coverage gap

**ACT-0165 — 2026-11-03.** A user-facing Android 14 SAF round-trip on a disposable profile restored the SQLCipher Room database, but did not restore a saved Inbox capture. Test used only synthetic content: create note in Memora, export through the portable-backup screen, delete the note in Inbox, import the exported document through the same screen, confirm replacement, and restart. Inbox was empty after recovery. The temporary profile was removed; the owner profile/database were not accessed.

## Why this matters

The SAF screen exports the protected AgentDatabase snapshot. Several other active persistent stores are files under app-private storage, so they are outside that snapshot:

- Inbox/capture history: `captures-prototype.bin` through `EncryptedCaptureStore` / `CaptureStoreKey`.
- Local task events: `tasks-prototype.bin` through `EncryptedTaskStore` / `TaskStoreKey`.
- Voice-memory entries: `voice-memory.bin` through `VoiceMoneyStore` / `VoiceMoneyKey`.
- Protected agent data: Room 18 / SQLCipher database plus its database-key envelope.

The first item is proven missing from the user-facing backup. The other files are confirmed active code paths but were not individually round-trip tested in ACT-0165. Do not describe the current Room-only file as a complete export of all Memora data.

## Recommended engineering direction

Extend portable backup into one authenticated, versioned bundle of every durable user-owned local store and its required key material, not a collection of separately restorable partial backups. Keep the current outer passphrase encryption and streaming model. Before opening any store on restore, authenticate and validate every manifest section, source schema, length and digest in isolated staging. Then stage all replacement files and key envelopes under one durable journal; startup recovery must activate the whole new generation or return to the whole previous generation. The app must not serve a mixed old/new set. The selected engineering direction is recorded in [ADR-013](adr/ADR-013-portable-backup-all-stores.md); inventory and implementation gates remain open.

## Acceptance required before M20-02 can claim portable backup completeness

1. Inventory all persistent app-owned stores, keys, migrations, tombstones, revisions and data exclusions; map each owner-visible view to its source of truth.
2. Seed one unique synthetic marker through each normal local write path, export with SAF, remove or change those markers, then import through the UI and verify all original markers after a cold process restart.
3. Reject a wrong password, corrupt/missing section, unsupported store schema and incomplete file provider result without changing any active store.
4. Inject process death at every durable bundle activation phase and verify restart observes either the complete prior generation or the complete restored generation, never a mixture.
5. Verify clean-install emptiness, data-preserving APK update, cancellation/error states, alternate SAF providers and physical owner acceptance separately.

M20-02 remains partial. The successful Room database cutover, isolated SQLCipher probes and UI export/import evidence remain valid for the Room database only; they do not establish whole-app backup coverage.
