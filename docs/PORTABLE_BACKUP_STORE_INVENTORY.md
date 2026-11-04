# Portable backup: persistent store inventory

**ACT-0166 — 2026-11-03.** Read-only source inventory for ADR-013. Paths below are current repository evidence, not a claim that backup support already exists. Re-run this inventory whenever a persistent store, setting, permission, or writer is added.

Repository review also found `LegacyStoreMigrationCoordinator` and `ProtectedMemoryRepository.migrateLegacy`: they can read all three encrypted file stores and transactionally migrate them into existing SQLCipher tables, recording source digests and preserving capture revisions/tombstones, task events/deleted IDs, and finance records. Current evidence is an opt-in synthetic Android instrumentation probe against a separate empty DB; no normal app launch or user screen invokes this coordinator. It cannot yet migrate into a populated DB, replace the active file-backed UI paths, or prove restart/owner-data preservation. Therefore this existing path is a candidate for a later storage-consolidation phase, not evidence that those files are already portable or safe to omit from ADR-013's archive.

## Data that must follow the user

| Store / state | Current source and protection | Backup treatment |
| --- | --- | --- |
| Main secretary data: profile/onboarding, people, projects/cases, ledger, tasks, calendar, memory, conversations, action journal and revisions | `memora-agent.db` in Room/SQLCipher; key envelope `memora-agent-db-key.v1` in `noBackupFilesDir`, wrapped by a device-local Android Keystore key (`DatabaseKeyProvider`) | Snapshot the entire validated SQLCipher database and its snapshot key inside the passphrase-authenticated outer archive. Never copy the device envelope or Keystore key. |
| Inbox/capture history | `captures-prototype.bin`, `EncryptedCaptureStore`, `CaptureStoreKey` alias `memora.capture.prototype.v1`; active normal UI write path | Typed, bounded logical snapshot; retain revisions, deletion/tombstone state and source metadata. Encrypt candidate on destination with a device-local key. Existing display JSON is not a complete snapshot. |
| Task event store | `tasks-prototype.bin`, `EncryptedTaskStore`, `TaskStoreKey` alias `memora.tasks.prototype.v1`; read by app and reminder paths | Typed snapshot preserving event history and deleted-ID state. Do not use the UI export as a full-fidelity snapshot. Destination gets a new local encryption key. |
| Legacy voice-money store | `voice-memory.bin`, `VoiceMoneyStore`, `VoiceMoneyKey` alias `memora.voice.money.v1`; migration source for finance data | Include while the file exists and can contain unmigrated source evidence. Keep migration provenance/digest consistent with Room. Only retire it through a separately verified migration/retention contract. Its JSON export is for explicit user export and is not the archive protocol. |
| Stable local agent identity | `agent-runtime-identity` SharedPreferences: `scope-id`, `actor-id`; conversation rows, journal and memory are keyed/scoped by these stable UUIDs. Legacy versions may keep these IDs in `voice-first-consent` and migrate them on access. | Include as a typed, validated metadata section (UUIDs only) or move them into the protected DB before implementation. Losing these IDs can orphan restored conversation/history; silently generating replacements after restore is not acceptable. Never include unrelated preference keys. |
| Presentation preferences | `appearance-settings-v1`: language (`az`/`ru`/`en`) and theme (`system`/`light`/`dark`); no sensitive profile data | Include in a versioned optional settings section, validate strict enum/size bounds, and use documented defaults only when the section is absent in a supported older archive. |

## Device-bound state: do not restore as permission or identity proof

| State | Current source | Restore rule |
| --- | --- | --- |
| Google/cloud processing consent | `voice-first-consent` SharedPreferences, including voice/text, Gemini Live/note and persisted-memory consent flags | Do not export or restore a granted state. On a destination install/restore, cloud consent starts revoked and the user must review and grant it again. The same rule applies to restored conversation data: importing data is not consent to upload it. |
| App Check/Firebase SDK tokens and installation IDs | SDK/app installation scoped state | Exclude. Let SDKs re-register; never bundle a debug token or treat it as portable authentication. |
| Android permissions, notification channels, exact-alarm grant, scheduled alarms | OS-owned state; reminder sources also read the encrypted task store and protected attention settings | Exclude platform grants and pending alarm objects. After restore, reconcile/schedule from restored domain state only when current OS permission and the app's own opt-in settings allow it. Never infer permission from an archive. |
| Local install lineage | `memora-local-install-state-v1` AtomicFile in `noBackupFilesDir` | Exclude. It describes this installation, not the user's portable content. A restored install remains a local installation and must not inherit another device's first-run marker. |
| Android Keystore keys and Room key envelopes | device-local non-exportable wrapping keys; DB envelope and cutover recovery envelopes in `noBackupFilesDir` | Exclude all old device wrapping keys/envelopes. Generate destination-local keys after validating the archive; keep the current atomic recovery process all-or-nothing. |

## Derived and temporary state

- Rebuild notification/alarm schedules from restored source rows after restore, respecting current user opt-in, notification permission, exact-alarm access and timezone.
- Do not bundle transient authenticated import/export payloads, SQLCipher journals, temporary candidates, rollback files or cutover journals. They are operation state, not user records; interrupted operations must recover or clean them before normal reads.
- Android Auto Backup is disabled in the current app configuration (see the manifest and ACT-0142). Portable export remains explicit, user-initiated and user-managed.

## Consistency and implementation implications

1. The complete user-data set is Room/SQLCipher, captures, tasks, voice-money until retired, stable agent scope/actor identity, and appearance preferences. Consent and device grants are deliberately not part of that set.
2. Export needs a cross-store snapshot barrier that coordinates Room writes, file-store writes, identity creation and scheduled reminder writers. A sequence of independent reads without a write barrier can represent no real point in time.
3. Import must validate all required data sections and versions before active replacement. After a fully successful restore, set cloud consent to revoked on the destination, preserve the destination install marker, regenerate destination keys, then reconcile notifications from restored state under current device grants.
4. The legacy voice-money section may become optional only after repository evidence proves no active app path can create/read it and migration/deletion is covered by tests. Do not infer retirement from its “legacy” name.
5. Future persistent sources must be added here and to the archive manifest and round-trip suite before being described as covered.

## Source references

- `apps/android/app/src/main/java/ai/memora/app/data/protectedstore/AgentDatabase.kt`
- `apps/android/app/src/main/java/ai/memora/app/data/protectedstore/DatabaseKeyProvider.kt`
- `apps/android/app/src/main/java/ai/memora/app/data/CaptureViewModel.kt`, `CaptureStoreKey.kt`, `EncryptedCaptureStore.kt`
- `apps/android/app/src/main/java/ai/memora/app/MemoraApp.kt`, `data/TaskStoreKey.kt`, `data/EncryptedTaskStore.kt`, `reminders/TaskReminders.kt`
- `apps/android/app/src/main/java/ai/memora/app/data/VoiceMoneyStore.kt`, `VoiceMoneyKey.kt`, `voice/AgentVoiceTurnProcessor.kt`
- `apps/android/app/src/main/java/ai/memora/app/data/AppearanceSettings.kt`
- `apps/android/app/src/main/java/ai/memora/app/data/LocalProfile.kt`
- `apps/android/app/src/main/AndroidManifest.xml`
