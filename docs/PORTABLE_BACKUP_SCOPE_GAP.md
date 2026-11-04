# Portable backup coverage gap

**ACT-0165 — 2026-11-03.** A user-facing Android 14 SAF round-trip on a disposable profile restored the SQLCipher Room database, but did not restore a saved Inbox capture. Test used only synthetic content: create note in Memora, export through the portable-backup screen, delete the note in Inbox, import the exported document through the same screen, confirm replacement, and restart. Inbox was empty after recovery. The temporary profile was removed; the owner profile/database were not accessed.

## Why this matters

The SAF screen exports the protected AgentDatabase snapshot. Several other active persistent stores are files under app-private storage, so they are outside that snapshot:

- Inbox/capture history: `captures-prototype.bin` through `EncryptedCaptureStore` / `CaptureStoreKey`.
- Local task events: `tasks-prototype.bin` through `EncryptedTaskStore` / `TaskStoreKey`.
- Voice-memory entries: `voice-memory.bin` through `VoiceMoneyStore` / `VoiceMoneyKey`.
- Protected agent data: Room 18 / SQLCipher database plus its database-key envelope.

The first item is proven missing from the user-facing backup. The other files are confirmed active code paths but were not individually round-trip tested in ACT-0165. Do not describe the current Room-only file as a complete export of all Memora data.

ACT-0166 completed a read-only inventory of additional persisted state. Stable agent `scope-id`/`actor-id` must follow scoped conversation/journal records; language/theme can be carried as bounded optional settings. Cloud/voice/memory consent, Firebase/App Check installation state, Android permission grants, notification channels, scheduled alarms, local install lineage and device Keystore material are deliberately device-bound and must not transfer. Destination consent starts revoked; notification work is recomputed from restored source data and current OS/app grants. See [PORTABLE_BACKUP_STORE_INVENTORY.md](PORTABLE_BACKUP_STORE_INVENTORY.md). This inventory is design evidence only and adds no runtime backup coverage.

Follow-up code review found an existing synthetic-only `LegacyStoreMigrationCoordinator` that atomically moves captures/tasks/voice-money into SQLCipher on a separate empty test database. It is not wired into normal app flows and currently refuses migration when the destination already has domain data. Keep all three sources in the backup scope until a populated-upgrade migration and active UI/source cutover are verified; the probe alone is not grounds to omit them.

## Recommended engineering direction

Extend portable backup into one authenticated, versioned bundle of every durable user-owned local store and its required key material, not a collection of separately restorable partial backups. Keep the current outer passphrase encryption and streaming model. Before opening any store on restore, authenticate and validate every manifest section, source schema, length and digest in isolated staging. Then stage all replacement files and key envelopes under one durable journal; startup recovery must activate the whole new generation or return to the whole previous generation. The app must not serve a mixed old/new set. The selected engineering direction is recorded in [ADR-013](adr/ADR-013-portable-backup-all-stores.md); inventory and implementation gates remain open.

Do not simply reuse the existing per-store JSON export methods: capture export omits tombstones and task export omits deleted-ID state. The current importer temporarily writes the decrypted outer payload to an app-private file. The full bundle must keep logical Inbox/task/voice data encrypted at rest during staging; use authenticated streaming or independently encrypted sections that produce only device-encrypted candidates.

## Acceptance required before M20-02 can claim portable backup completeness

1. [x] Read-only source inventory of active durable stores, keys, current consent/settings/identity/install state, and derived device state: [PORTABLE_BACKUP_STORE_INVENTORY.md](PORTABLE_BACKUP_STORE_INVENTORY.md). Runtime adapters and consistency barrier are still open.
2. [ ] Seed one unique synthetic marker through each normal local write path, export with SAF, remove or change those markers, then import through the UI and verify all original markers after a cold process restart.
3. [ ] Reject a wrong password, corrupt/missing section, unsupported store schema and incomplete file provider result without changing any active store.
4. [ ] Inject process death at every durable bundle activation phase and verify restart observes either the complete prior generation or the complete restored generation, never a mixture.
5. [ ] Verify clean-install emptiness, data-preserving APK update, cancellation/error states, alternate SAF providers and physical owner acceptance separately.

M20-02 remains partial. The successful Room database cutover, isolated SQLCipher probes and UI export/import evidence remain valid for the Room database only; they do not establish whole-app backup coverage.
