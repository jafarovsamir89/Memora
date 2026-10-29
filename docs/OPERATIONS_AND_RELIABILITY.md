# Operating Memora as a commercial service

## Environments
Development, staging with synthetic/redacted data, production. IaC, secret manager, automatic migrations with rollback, CI/CD with tests/security scans, staged release + feature flags.

## Observability
Metrics: ingestion success, STT latency, transcription critical errors, extraction valid schema, ambiguous action rate, retrieval evidence coverage, reminder scheduling/delivery outcomes, token/audio usage, provider latency/failure, storage growth, queue age, spend per tenant, active subscription counts, churn and conversion. Logs are redacted; tracing identifiers not user text.

## Reliability
Multiple provider configurations and breakers; durable queue and DLQ; back-pressure and per-user quotas; circuit breakers on cost/runaway retries; idempotent worker side effects; redundant backups and point-in-time recovery; recovery exercises; region and data residency considerations. Critical reminders not guaranteed by best-effort push alone; distinguish schedule vs delivery.

## Billing
Store-validated subscriptions (Google Play, Apple, web as appropriate), server-side entitlements, webhook idempotency, grace periods, chargeback/refund reconciliation. Monthly audio minute/advanced model limits; transparent displays and warnings, non-destructive fallback when quota reached. Provider prices versioned and daily cost alerts.

## Support and incident response
Public status, support contact, incident severity and on-call rotation, audit requests, safe redacted reproduction, DPA/subprocessor list, retention and deletion request procedures. Access to private memory requires scoped permission and is never a standard support shortcut.

## Disaster recovery and lifecycle
Document backup schedule, encryption, RPO/RTO, restore rehearsals, export and irreversible deletion workflows; policies for logs/cache/embeddings/archives after user deletion; third-party provider retention disclosed.

## Governance
Design reviews for prompts/tools/data model, change log for AI model switches, regression and legal checklist, independent security assessments before broad launch, release notes and monitored rollback.
