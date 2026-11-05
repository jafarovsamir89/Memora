# ADR-019: Typed table JSON transfer

**Status:** Engineering decision for the local Android P4 table transfer slice (does not change product/owner decisions)
**Date:** 2026-11-04

## Context

The app has repository-level JSON export/import preview for editable tables, but no user-facing file workflow. Linked views were blocked because a naive row export would flatten a live canonical projection into copied rows or restore an empty editable table. Imports also accepted at most 1,000 rows while the current commit path allowed only 100 rows, so larger valid previews could not be committed atomically.

## Decision

1. Use `memora-table-v2` JSON for new exports. It binds the file to the owner scope and table ID, includes mode, schema version, ordered typed column definitions and row versions/values. Editable exports include active rows only; soft-deleted rows and deletion history stay in the full protected backup. Linked views include their versioned `viewSpec` and an empty `rows` array; no projected source record is copied.
2. Keep `memora-table-v1` import compatibility for editable tables. V2 imports must match the current target's scope, table ID, mode, schema version, exact ordered column definition and, for linked views, exact supported view spec. Unsupported or stale schema fails closed.
3. Import is an additive, idempotent merge into an existing table. It never creates/replaces a table, overwrites a changed row, or mutates the canonical records referenced by a linked view. Stable derived row IDs preserve retry safety. Linked-view import only verifies that the definition matches and returns no row mutations.
4. Apply a validated editable import in one SQLCipher/Room transaction, in bounded insert batches. Keep the current 1 MB / 1,000-row file bounds and surface errors; full-app portable backup remains the route for restoring the complete DB and identity scope.
5. Expose export through Android SAF CreateDocument and import through OpenDocument. Preview the table/mode/schema/row count before the user explicitly confirms row merge. No storage permission is requested. If SAF output fails, attempt to remove the app-created partial document.

## Consequences

- JSON round-trip can idempotently merge active rows into the same existing table in the same owner scope; it is not a full DB transfer, does not carry deleted-row history, and cannot transfer records across scopes or create a table on another installation.
- Linked-view JSON preserves the read-only projection contract without duplicating its source data.
- Imports exceeding explicit bounds or targeting changed/incompatible schemas must be split/re-exported after the relevant large-data transfer work exists; they must never partially commit.
- The target remains protected by existing scope validation, `PERSON_REF`/`PROJECT_REF` validation, and SQLCipher transaction semantics.

## Verification

Synthetic repository tests cover v1 compatibility, v2 editable round-trip, >100-row atomic merge/idempotent retry, later-batch rollback, schema drift rejection, and linked-view spec export/preview without source-row copies. Compose tests verify user-facing table transfer actions and exact currency totals; SAF picker/provider invocation and import-preview confirmation UI are not automated by these tests. Strict reader tests cover AZ UTF-8, malformed UTF-8 and oversize input. Physical SAF-provider, accessibility and owner acceptance remain separate gates.
