# Domain Model and Data Semantics

## Shared columns
Все объекты: uuid, tenant_id, space_id, owner_id, created_at, updated_at, deleted_at nullable, version, provenance_id, privacy_scope. FK and row-level access restrictions обязательны; уникальный idempotency_key на источник/операцию в пределах tenant.

## Core entities
User, Device, Session, Workspace, Membership, Permission, Subscription, UsageRecord; Capture, Attachment, Transcript, TranscriptRevision; Assertion, EvidenceSpan, Entity, Alias, Relationship, FactVersion; Person, ContactIdentity, Project, Asset; Account, Category, LedgerEntry, TransactionGroup, Budget; Obligation, ObligationEvent, Settlement; Task, TaskEvent, Reminder, DeliveryAttempt; CalendarEvent, EventParticipant; ProviderJob, AIUsage, AuditEvent, Consent, IntegrationCredential.

## Financial invariants
Суммы DECIMAL / minor units, валюта ISO 4217, математические операции детерминированны. Income ≠ receivable; expense ≠ planned expense; self-transfer ≠ revenue; debt principal + adjustments − settlements = balance. Нельзя повторно применить одну выплату. Возврат и сторно ссылочно связаны с исходной операцией. FX требует курса, времени, источника и сохранения исходной суммы.

## Time and language
Сохранять created_at в UTC, event_local_datetime, IANA timezone, temporal_precision, interpretation_basis. «Завтра» относительно времени сообщения и local timezone; не подставлять вымышленный час события. Languages BCP-47; исходное многоязычное содержание и нормализованные структурные значения хранятся отдельно.

## Event-sourced state
Денежные движения, изменения обязательств, задачи и внешние действия пишутся как auditable events с ревизией. Проекции для dashboard можно регенерировать. Corrective event вместо тихой перезаписи; право на удаление PII соблюдается отдельно.

## Example
Input «Эльчин должен мне 500 AZN» → claim, Person candidate, Obligation principal 500, debtor Person, creditor User, outstanding 500, source.
«Вернул 200» → candidate Settlement, reject if multiple possible creditors/debtors; confirmed settlement 200, outstanding 300.
«В пятницу вернёт остальное» → due-date proposal on existing obligation, not received income.

## Data lifecycle
Original audio retention user-configurable, transcript retention separately configurable. Export everything in documented portable formats; deletion removes/invalidates vector embeddings, derived aggregates, cached summaries and attachments under the published retention policy. Backups encrypted with time-bound deletion.
