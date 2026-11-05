# ADR-021: Preview and restore versioned table schemas

**Status:** Engineering decision for the local Android P4 table workflow
**Date:** 2026-11-04

## Context

Custom tables already store append-only schema revisions. The UI could create a table and edit rows, but could not add fields to an existing table, inspect schema history, or return to an earlier schema. Schema edits were intentionally additive: deleting or changing an existing column was rejected to avoid losing row values.

## Decision

1. Expose only additive creation of optional columns in this UI slice. The owner reviews the exact field names and types before applying. Existing columns and rows are unchanged.
2. Show all stored schema revisions for an editable table. Restoring a prior revision is an explicit confirmed operation guarded by the current schema version and owner scope.
3. A restore appends a new monotonically increasing schema revision; it never rewrites or deletes history. It replaces the active `table_column` projection inside one SQLCipher/Room transaction.
4. Row JSON is never rewritten during schema restore. Values belonging to columns absent from the restored schema remain stored but hidden; restoring a schema that contains those stable column IDs makes them visible again. Explain this behavior before confirmation.
5. Linked views remain read-only and do not expose schema mutation or restore controls. Existing schema/version checks continue to reject stale writes.

## Consequences

- This provides preview, revision history, undo and redo for additive optional-column changes without a database migration or destructive row rewrite.
- It does not add column rename, removal, required fields, type/config changes, default migration, or schema change tooling for the agent. Those need a separate preview/migration contract.
- A table’s schema revision history is scoped through the owning table lookup; foreign-scope requests fail closed.

## Verification

Synthetic repository tests prove additive revision, undo and redo, row JSON preservation through edits, append-only version numbering, stale-version/scope rejection and linked-view rejection. Compose test proves add-fields preview and revision/restore preview are reachable. Full Gradle passed 63 suites / 324 tests; lint 0/73/3. Device, TalkBack and owner acceptance remain separate.
