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
