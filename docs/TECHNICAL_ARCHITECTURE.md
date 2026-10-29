# Reference Production Architecture

## Порядок реализации — решение владельца от 2026-10-29

Сначала создаётся **нативное Android-приложение с локальными данными и доменной логикой**. Весь его функционал тестируется и подтверждается владельцем; только после этого приложение адаптируется к серверной инфраструктуре. Прежнее предложение о собственном backend как части начального фундамента заменено. Основание и контрольные точки — раздел 1.1 [ENGINEERING_PLAN.md](ENGINEERING_PLAN.md), MEM-14.09–MEM-14.14 в [TODO](../TODO.md).

В нативной фазе Android содержит UI, доменные команды/валидацию, локальную БД, файлы, историю, поиск и фоновые задания. Успешное сохранение подтверждается локальной транзакцией. Домен, хранилище, задания и AI отделяются интерфейсами, чтобы позже подключить серверные реализации и API без изменения подтверждённой семантики. Конкретные библиотеки и схема доступа к AI ещё требуют ADR; нативность не означает обязательную работу AI на устройстве.

После полной нативной приёмки переносим согласованные обязанности на сервер и мигрируем существующие данные, сохраняя источники, идентификаторы, связи и историю. Android остаётся нативным клиентом с локальной сохранностью и очередью. Нужны отдельные проверки миграции, регрессии, серверных прав и синхронизации; локальные проверки не доказывают распределённую надёжность.

**Следующие разделы описывают целевую серверную архитектуру после этого перехода.** Модульный монолит с обработчиками — кандидат серверного этапа, а не начальное окружение Android.

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

## Engineering clarification — proposals pending ADRs

Согласованный порядок работы: разработку ведут владелец проекта и AI-ассистент; первая рабочая сборка — Android, первая проверка полезности — на повседневных задачах владельца, затем у похожих пользователей. iOS и веб остаются в полном целевом объёме и реализуются последовательно. Проверки Android выполняются на реальном устройстве, включая закрытие приложения, отсутствие сети и восстановление очереди. План и статус решений: [ENGINEERING_PLAN.md](ENGINEERING_PLAN.md).

Следующие положения — инженерные предложения, а не принятые ADR или реализованные механизмы:

- Начать с **модульного монолита и отдельных фоновых обработчиков**. Логические домены выше не означают обязательный набор микросервисов. Разделение развёртываний обосновывается измеренной нагрузкой, надёжностью или независимым жизненным циклом.
- AI создаёт типизированные предложения; доменные команды применяет сервер после проверки прав, версии, инвариантов и необходимых подтверждений. Подтверждение связано с точным содержимым и версией команды; изменение получателя, суммы или области доступа требует повторной проверки.
- Для результатов одной записи определить группы связанных действий: какие применяются атомарно, какие независимо и какие ждут уточнения. Фоновая обработка не должна скрывать частичный результат за общим статусом «готово».
- Предусмотреть transactional outbox, обработку повторов и сверку внешних эффектов. «Exactly-once domain effects» выше — целевой инвариант применения доменной команды, а не гарантия транспорта или внешнего API. Неопределённый результат внешней операции требует сверки перед повтором.
- Перестроение проекций использует сохранённые разрешённые события и версии; оно не запускает AI заново. Новый разбор источника создаёт новые предложения и явное сравнение изменений.
- Синхронизация использует версии и доменные правила конфликтов; финансовые изменения нельзя разрешать универсальным «последняя запись победила». Отзыв доступа не гарантирует мгновенного удаления ранее скачанной копии с отключённого устройства.
- Изоляция охватывает пользователя и пространство во всех слоях: БД, вложения, поиск, кэш, фоновые задания и контекст модели. Область доступа проверяется заново перед выполнением отложенной команды.

До production-кода оформить ADR по архитектуре и границам транзакций, offline/sync, identity/auth и пространствам, денежному журналу, retention/delete/restore, маршрутизации провайдеров и разрешениям AI-инструментов. Для каждого ADR нужны альтернативы, последствия, проверяемые инварианты и соответствующие сценарии приёмки. Конкретный механизм очередей, авторизации и размещения остаётся открытым до этих решений.
