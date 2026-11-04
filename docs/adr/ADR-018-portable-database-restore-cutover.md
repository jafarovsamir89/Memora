# ADR-018: Recoverable cutover for portable SQLCipher restore

**Status (ACT-0157):** implementation slice verified on a synthetic Android 14 emulator; not owner accepted and not connected to SAF/UI. Recovery hooks simulate interruption after durable writes but do not prove real process death or power-loss durability. M20-02 remains partial. 2026-11-03. Related: [ADR-011](ADR-011-storage-migration-candidate.md), [ADR-012](ADR-012-keystore-wrapping-key-rotation.md), [ADR-017](ADR-017-portable-backup-container.md).

## Context

`PortableBackupImporter` returns a file candidate only after outer AEAD, inner manifest/digest and isolated Room/SQLCipher validation succeed. It deliberately does not replace the active database. The active database uses a Keystore-wrapped SQLCipher key; its encrypted file and key envelope must move as a coordinated pair. Room callers may retain references to the current singleton, so activation cannot continue in the current process after closing/swapping the DB.

An explicit restore replaces current application data with the chosen snapshot. The UI must describe this effect and ask for confirmation immediately before activation. Merely opening a file picker, validating a file, or entering a password must not activate it. There is no cloud/server or cross-user restore.

## Decision

1. Run all cryptographic parsing, schema/integrity checks and a Room close/reopen readback against disposable files before arming cutover. Candidate schema must be supported exactly; no downgrade or implicit destructive migration.
2. Keep the app's active DB filename stable. Copy the already-validated encrypted candidate to a fixed, same-directory sibling candidate path and fsync it. Preflight available disk space and refuse if insufficient; never copy the existing active DB because it is atomically renamed to rollback.
3. Before any active-file rename, persist an app-private `AtomicFile` restore journal containing only protocol version and phase. Persist a separately Keystore-wrapped candidate SQLCipher key; preserve the existing encrypted key envelope in an app-private rollback envelope. Do not put passwords, SQLCipher keys or personal data into the journal/log.
4. Close the singleton and refuse activation if the active connection cannot close cleanly or unexpected SQLite sidecars remain. The current process must not perform further database reads/writes once cutover is armed. Stop and reopen the app to ensure all remembered Room/repository references are discarded; startup recovery runs before any `AgentDatabase.open` and before workers can access the DB.
5. Recovery phases are monotonic and idempotent: `PREPARED`, `OLD_DB_MOVED`, `CANDIDATE_INSTALLED`, `NEW_KEY_PROMOTED`, `VERIFIED`. In same-directory atomic rename operations, retain the old encrypted DB and old key envelope until the candidate has opened and been closed/reopened under its staged key. Only then promote the new wrapped key and mark `VERIFIED`.
6. At startup, inspect the phase and actual fixed files, not only the last phase write: interruption before the old-file rename leaves the original untouched; interruption after moving old DB restores it unless a candidate can be opened with the staged key; candidate/key/promotion/open failure always restores the old DB+old envelope. If both generations fail, fail closed and preserve every remaining encrypted recovery artifact for diagnostics/recovery.
7. Delete rollback DB, previous key envelope, pending candidate and journal only after the new DB has passed the same Room schema/integrity/reopen checks and startup opens it successfully. Cleanup is retryable. This is app-private rollback protection; file deletion is not a promise of physical secure erase on flash.
8. SAF/Compose UI must offer preview metadata only after validation, explicit replacement confirmation, AZ/RU/EN messages, wrong-password/corrupt/unsupported-schema/out-of-space/interrupted-recovery states, and must delete its own partial output document when export fails. It must never include transient audio/dialogue in the backup.

## Required fault and acceptance tests

- Synthetic instrumentation injects interruption at PREPARED, OLD_DB_MOVED and CANDIDATE_INSTALLED and reopens through the production startup recovery entry point; candidate-verification refusal proves rollback, and a second interruption after restoring the old DB validates rollback-cleanup recovery. Still simulate real Android process death around every journal write/rename/key promotion, and validate at API 26/33/34 before claiming restart durability.
- Seed two isolated SQLCipher DBs with different synthetic sentinels. Success activates only the chosen candidate; every injected failure before `VERIFIED` retains/reopens the original; no DB row, plaintext marker, password or key enters logs.
- Verify Room close/reopen/readback, empty/unsupported/corrupt/wrong-key candidate, sidecar refusal, short write, rename failure and disk-full preflight/late failure. Retry cleanup after another process restart.
- Instrument on API 26, API 33 and API 34; do not claim hard power-loss durability from force-stop tests. Physical owner-device and UX acceptance are additional gates.

## Consequences

The existing database path and all domain repositories remain in place; only the selected encrypted DB file and local Keystore envelope change after a user-confirmed restore. The cutover protocol and startup recovery are implemented; SAF flow is not. Restore remains unreachable from normal user workflow and is synthetic-only. M20-02 and release readiness stay partial.
