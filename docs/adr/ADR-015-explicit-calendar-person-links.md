# ADR-015: explicit calendar-to-person links

**Status:** accepted engineering direction for the local Android phase, 2026-11-03. This extends the owner-confirmed relation pattern in ACT-0138; it does not define server sync or automatic identity matching.

## Context

M20-03 needs a person card that can show canonical calendar events. Calendar events currently carry no canonical person reference; display labels are not trustworthy identity evidence. An event may concern several people, and a person may participate in many events.

## Decision

- Store calendar/person associations in separate SQLCipher tables keyed by the existing event ID and canonical `DomainObjectRow` PERSON ID. Do not copy or modify calendar event content.
- Create a link only after an explicit owner action. Model output, `personLabel`, title similarity, or a prior case participant never creates the association.
- Allow multiple people per event and multiple events per person. Keep links scoped and validate both active records in the same scope on every write.
- Preserve provenance (`confirmedBy=owner`), optimistic version, timestamps, soft retirement and append-only relation revisions. A later confirmation can restore a retired link. Reads expose active links only, and cancelled events do not appear as active person-calendar items.
- Use an additive Room migration. Existing calendar events stay unlinked; there is no backfill from text fields.
- A future server migration must preserve the explicit link and re-check identity, scope and permission. This ADR does not grant cross-user sharing.

## Consequences

The person card can show direct calendar relations without treating an ambiguous label as identity. The extra tables and owner confirmation are intentional; related event records remain the source of truth. Deleting a person cascades its links; calendar cancellation leaves relation history stored while removing the event from active reads. Full person timeline ordering and broader event correction propagation remain separate acceptance work.

## Verification

Required before treating the slice as complete: repository tests for owner-only create, scope/kind/status validation, idempotency, retire/restore and revision readback; migration tests proving old events remain and no links are inferred; person-card UI tests for explicit selection and removal; SQLCipher probe through the new schema preserving a synthetic sentinel. Physical owner database and field acceptance remain separate.
