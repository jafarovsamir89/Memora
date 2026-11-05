# Domain Model and Data Semantics

**P3 profile-memory slice ACT-0182:** the owner can create a fact directly from the local Memory screen. Repository stores it with `user_entered` provenance, a nullable `sourceTurnId` left null and a revision record; it does not fabricate a conversation message. Owner edits continue as `user_edited` revisions. Exact normalized duplicates within the same scope/category fail closed instead of silently merging. `identity` is now a supported owner-selected profile-fact category. No schema migration or automatic sensitive-trait inference was added. See ADR-020 and S58.

**Проектное дополнение ACT-0039:** целевые AgentTurn/Command/CommandResult/Confirmation, таблицы UserTable/Column/Row/Cell, единый доступ к источникам и миграция отдельных хранилищ определены в [руководстве агента](AI_AGENT_IMPLEMENTATION.md), разделы 4–6 и 10. Это требования будущих схем; существующие схемы APK не изменены. Linked view не создаёт вторую финансовую истину, суммы и evidence остаются каноническими. Детали закрепляются в P0 до production-кода.

**P4 implementation slice ACT-0172:** schema 19 adds nullable `user_table.viewSpecJson` via additive Room migration 18→19. The version-1 view spec currently permits only active same-scope PERSON, PROJECT or NOTE sources. Repository/UI/agent reads project canonical object IDs and current values directly; no linked row is materialized in `table_row`. Linked-view row mutations, schema edits and export/import preview fail closed. This is an initial read-only projection, not the full production `viewSpec` contract: filtering, sorting, pagination, all-day data sources, round-trip serialization and owner/device acceptance remain open.

**Текущая нативная реализация, 2026-10-31:** просмотр/поиск памяти используют существующие `CaptureEntry` → `TextSource` → неизменяемые `TextRevision`. Найденный результат связывается с точной версией через `TextEvidence`; это локальный буквальный поиск с AZ-регистром, не готовый семантический индекс. Голосовая заметка проходит просмотр и исправление, затем становится явно принятой текстовой записью; аудио в файл не сохраняется. Ответ модели не записывается как факт автоматически. Схема v5 не менялась; отдельные типы аудиоисточника и постоянное хранилище evidence остаются открытыми в MEM-07.01.

**Нативный M20-03 slice ACT-0138:** `task_person_link` связывает scoped `taskId` с существующим domain `PERSON.objectId` только после явного подтверждения владельцем. Сохраняются отношение, версия, timestamps, soft-retire и строки `task_person_link_revision`; карточка человека выбирает задачи из локального журнала и не сопоставляет `personLabel`/названия. Room 16→17 additive migration создаёт только две новые таблицы и индексы; старые записи задач не меняются. Это прямая связь между задачей и человеком, но не полная унификация identity между календарём, проектами, памятью и ledger, и пока не device acceptance.

**Нативный M20-03 slice ACT-0141:** `calendar_person_link` связывает существующий calendar `eventId` с активным canonical `PERSON.objectId` в той же scope после выбора и отдельного подтверждения владельцем. Связь может быть many-to-many, revisioned, soft-retired и восстановлена; cancelled events не выдаются как active links. `calendar_person_link_revision` сохраняет action/relation/version and owner confirmation. Room 17→18 создаёт новые relation tables/indexes без backfill из `personLabel`, названий или case участников. Карточка человека показывает выбранные события и позволяет снять связь, не удаляя календарное событие. Synthetic SQLCipher migration v1→18 проверила пустые новые relation tables и journal sentinel; реальные данные пользователя не читались. Это ещё не полная PersonIdentity унификация, проектные/episodic-memory связи, BMW acceptance или device acceptance.

## Размещение данных по этапам

Решение владельца от 2026-10-29: сначала нативное Android-приложение с локальной канонической БД и доменной логикой; после тестирования и подтверждения всего функционала — адаптация к серверной инфраструктуре. Сущности, точные деньги, происхождение, версии и история нужны с локального этапа. Серверные FK/RLS, аккаунты и синхронизация ниже — последующая реализация по разделу 1.1 [ENGINEERING_PLAN.md](ENGINEERING_PLAN.md). До появления сервера `received_at` серверного получения отсутствует, а не заполняется вымышленным значением. Будущий перенос сохраняет ID, связи, источники, даты и версии; повтор миграции не повторяет доменные эффекты. Локальный доступ и серверные права проверяются отдельно.

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

## Engineering clarification — contracts to resolve before implementation

Ниже зафиксированы предложения для будущих контрактов и ADR; они не означают готовую схему или утверждённый алгоритм. Порядок работы: [ENGINEERING_PLAN.md](ENGINEERING_PLAN.md).

### Commands, revisions and duplicate handling

- Разделить исходную запись, предложение AI, доменную команду, подтверждение и применённое событие. Хранить их связи, версии и correlation/group identifiers; точные поля определить контрактом.
- Подтверждение относится к конкретной версии и содержимому предложения. Применение проверяет текущие права и ожидаемую версию объекта; устаревшая команда не перезаписывает новое состояние молча.
- Для составной записи определить атомарные группы и независимые действия. Каждое действие имеет свой результат, а зависимое действие не применяется до выполнения необходимых условий.
- Идемпотентность сетевой операции отличается от смыслового дубля: повтор запроса с тем же ключом не создаёт эффект повторно, но две похожие записи пользователя могут описывать разные события. Смысловое совпадение даёт кандидата на уточнение, а не автоматическое удаление записи.

### Financial definitions

- Начальный остаток счёта фиксируется отдельно от дохода, с датой начала учёта и источником. Отсутствие более ранней истории должно быть видно в отчётах.
- Связать погашение обязательства и движение по счёту так, чтобы одна оплата не учитывалась дважды. Сам факт создания долга не создаёт движение денег; счёт оплаты нельзя выдумывать при его отсутствии.
- Возврат основной суммы займа не становится доходом автоматически. Оплата продажи, возврат займа, проценты, возврат покупки и собственный перевод требуют отдельных семантических правил.
- Переплата не превращает долг молча в отрицательный остаток: предложить классификацию как аванс, новое обязательство, ошибка суммы или другое подтверждённое действие. Частичные оплаты, возврат оплаты и изменение первоначальной суммы должны иметь определённую историю и пересчёт.
- В ADR денежного журнала выбрать каноническое представление суммы, точность, округление, систему проводок и политику валют. Клиент, сервер и экспорт должны использовать одинаковые правила.

### Time, ownership and deletion

Различать `captured_at` на устройстве, `received_at` на сервере, время события и точность его интерпретации. При offline-загрузке «завтра» не привязывается ко дню получения сервером; ошибка часов устройства требует явной политики. Подтверждённая дата не пересчитывается незаметно после смены часового пояса.

Зафиксировать различие владельца, автора и области доступа (`tenant`/личная запись/общее пространство), правила переноса и отзыва прав. Общая принадлежность к tenant не даёт доступа ко всем его личным записям.

Для удаления и исправлений нужен учёт зависимостей: какие факты, индексы и отчёты стали устаревшими, какие пересчитываются и какие требуют решения пользователя. Восстановление резервной копии должно повторно применять актуальные ограничения доступа и сведения об удалении до открытия восстановленных данных пользователям.


## Runtime implementation note — ACT-0093

The target finance semantics above remain authoritative. The current field agent uses structured Gemini function arguments and writes finance facts to the scoped SQLCipher domain repository; it does not re-parse the original sentence with `VoiceDebtRules` on the agent path. Exact decimal arguments are normalized before validation, and a missing event date defaults to the current local date in Asia/Baku. Synthetic device evidence on APK v23 covers receipts, obligations without optional reason/date, a repayment linked to its obligation, an append-only correction, balance readback, and local database readback after process restart in AZ and RU. A separate migration probe on v24 covers exact minor units, task history, idempotent journal/outbox, and reopening migrated stores. The full synthetic Gemini mutate sequence was not rerun on v24 after the RU search was rate-limited; field acceptance remains open. Do not treat this partial field implementation as release acceptance.

## Runtime implementation note — ACT-0095

The local SQLCipher repository has an additive `annotation` adjustment for a user's later explanation of one exact financial event. It has zero minor units and cannot alter ledger totals; it points to the source event, is scope-checked and transactionally read back, and an identical explanation is deduplicated. Finance search returns bounded annotations with explicit completeness metadata and recent events within a 10 KB JSON budget; full-ledger deterministic totals are preserved. The original event and its amount remain canonical; corrections use the append-only path and update an existing repayment allocation atomically. Device migration/readback and real synthetic Gemini AZ/RU mutation/restart flows passed; see [the evidence report](GEMINI_TOOL_E2E_2026-11-03.md). Same-name conflicts create no finance event; the runtime asks for essential identity clarification and blocks false success. Full aliases/disambiguation UX and field acceptance remain open. Annotation content remains behind the existing memory-consent gate and is treated as untrusted historical user data.
