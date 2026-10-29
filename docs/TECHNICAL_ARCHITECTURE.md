# Reference Production Architecture

## Components
Android Kotlin/Jetpack Compose; iOS Swift/SwiftUI; React/TypeScript accessible responsive dashboard; FastAPI backend with modular domain services; PostgreSQL + pgvector, object storage, Redis queue/workers, search indexes, FCM/APNs, provider gateway. These are proposed choices, validated during technical design.

## Logical services
Identity & tenant authorization; Capture & Upload; Speech Pipeline; AI Orchestration; Claims/Memory; Finance Ledger; Obligations; Tasks & Calendar; Contacts & Projects; Search/RAG; Reminder Scheduler & Delivery; Notifications; Billing & Entitlements; Audit; Integrations; Admin/Observability.

## Flow
Device captures audio → encrypted upload with idempotency key → object storage quarantine/validation → asynchronous job → STT → claims extraction → entity resolution → domain validation/approval → committed events → indexes/aggregates → notifications. API returns job state; clients subscribe/poll until terminal state. Retries with at-least-once transport and exactly-once domain effects.

## APIs
Versioned REST/OpenAPI; token-based authentication, per-object permission checks and rate limits. Types explicit for money and timezone. Structured responses with status, source references, confidence, confirmation requirement and operation identifiers. Push and realtime updates do not replace server state.

## Security boundaries
No direct client API credentials for model providers; secrets managed server-side; signed short-lived media URLs; strict workspace authorization before queries, vector retrieval and file reads; object scanning; least privilege. External calendar/email writes require scope and human confirmation.

## Offline and synchronization
Encrypted local cache/queue, durable upload IDs, deterministic dedupe, optimistic concurrency/version conflicts, explicit UI state. No offline claim that remote reminder is scheduled unless acknowledged.

## Scalability and ops
Stateless API horizontal scaling; independent queues for STT/LLM/delivery, concurrency controls and quotas; PostgreSQL backups/PITR, object lifecycle, provider circuit breakers, retry with jitter, dead-letter and operations dashboard. Observability traces separate PII; no raw transcripts in logs.

## Integrations
Adapters with isolation, OAuth token vault, per-user enable/disable, revoked credentials, differential sync and reconciliation. WhatsApp is not a dependency or baseline functionality.

## Engineering boundaries
Monorepo recommended apps/android, apps/ios, apps/web, services/api, workers, packages/contracts, infra, tests. Actual directories appear only when implementation begins. Start with domain contracts and test fixtures, not hard-coded vendor coupling.
