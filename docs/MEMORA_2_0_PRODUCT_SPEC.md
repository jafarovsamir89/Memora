**Latest implementation snapshot ACT-0230 (2026-11-06; evidence only, not a change to the target product contract):** owner stopped a live Gemini campaign after 44 Azerbaijani synthetic text turns (41 passed, 3 episode-summary amount assertions failed). A local Android Settings picker selects Gemini 3.5 Flash Lite (default), 3.1 Flash Lite or 3.6 Flash; it retains the choice between launches and uses the existing gateway/domain tools. Gemini 3.6 replaced the initial 3.8 option after the project's synthetic API probe returned 503 twice for 3.8 and 200 for 3.6. Full Gradle passed (71 suites / 357 JVM tests; lint 0/75/3); APK v88 was built. Physical install was blocked because ADB sees no phone; install/launch are not claimed. The 1,000-turn campaign, voice acceptance, native acceptance and release readiness remain incomplete. This spec remains the full product vision, not proof that requirements are implemented.

**Verified ACT-0206 (2026-11-04):** `search_memory` supports bounded AZ/RU/EN ISO-week and explicitly qualified weekday date filters over local finance, task and calendar activity. It uses profile timezone, retains ambiguous bare weekdays as lexical text, and leaves storage unchanged. Synthetic cross-domain/scope tests; full Gradle 63 suites / 334 tests, lint 0/73/3, debug/AndroidTest builds and Kotlin compile passed. APK v76 / `1.1.54-memory-week-search`, SHA-256 `b7f28653670e1608416b1e46243047e57f8cd6154a9317bc149491438da24416`. Host-only; no device/TalkBack/owner acceptance or performance benchmark. See ADR-032/S70; bounded explicit ISO ranges were added in ACT-0210. Semantic retrieval and M20-04 remain partial.

**Current implementation checkpoint ACT-0204:** Cases screen supports 30-item pages with older/newer controls for ranked search and no-query/status-filtered lists; search order is deterministic and hard results boundaries are disclosed. Full Gradle: 63 suites / 333 tests, 0 failures/errors/skips; debug/AndroidTest builds and Kotlin compile passed; lint 0/73/3. APK v75 / `1.1.53-cases-pages`, SHA-256 `39251e14c8ca38a267d963f245a75dc8ebb506ebacb8ed622712baf6170ca72f`. No schema change. Synthetic host only; ADB empty, no device/TalkBack/owner acceptance or performance benchmark. See ADR-031/S69; M20-05 remains partial.

**ACT-0180 implementation checkpoint:** P4 table transfer now has a user-facing SAF JSON workflow for existing tables, explicit preview/confirmation, `memora-table-v2` exact schema/view binding, legacy editable v1 import, bounded UTF-8/size validation, and one-transaction chunked idempotent merge. Linked views transfer their read-only definition without copying source records. Editable export includes active rows; deleted-row history remains in full backup. Money aggregate UI displays exact minor-unit totals separately by currency. Full Gradle passed: 63 suites / 318 tests, 0 failures/errors/skips; lint 0/74/3; debug/AndroidTest builds and Kotlin compile passed. APK v63 SHA-256 `15ac1d3492684ba841d9fe71590f3e69f8034434828931b2c19952dbb76e0ddd`. Physical SAF/TalkBack/owner acceptance and P4 remain open.

**ACT-0168 update:** local cross-domain memory retrieval now searches canonical finance, task and calendar records by an explicit ISO date (`YYYY-MM-DD`), normalizing timed sources to the local profile timezone and excluding adjacent dates. Full Gradle: 60 suites / 304 tests, 0 failures/errors/skips; debug and AndroidTest APKs built, AndroidTest Kotlin compiled, lint 0 errors/63 warnings/3 hints. Debug APK SHA-256 `f4c2be8377e607902dd572fb1f1a7c18b4eddbd6c7b237cc1df1640bdb2fcc3b`. This is a lexical exact-date slice only; natural-language date expressions/ranges, broader search corpus, device performance and owner acceptance remain open; M20-04 remains partial.

**ACT-0169 update:** bounded relative-day query words are now resolved against the profile-local date for canonical finance/task/calendar activity: Azerbaijani `bugün` / `bu gün`, `sabah`, `dünən`; Russian `сегодня`, `завтра`, `вчера`; English `today`, `tomorrow`, `yesterday`. Synthetic fixed-clock tests cover the three “tomorrow” variants and adjacent-day exclusion. Other natural-date expressions (weekdays, “next week”, ranges), semantic search, device performance and owner acceptance remain open; M20-04 is partial.

**ACT-0170 update:** custom editable tables can now declare typed `PERSON_REF` / `PROJECT_REF` columns. Local UI selection stores the stable object ID; repository validation rejects missing, wrong-kind, archived/deleted or cross-scope targets during row save/import. The row view resolves the current entity name; stale links show an unavailable state. Synthetic repository checks passed. A Compose UI integration test could not get Robolectric idle during test text entry, so UI interaction, accessibility and field acceptance remain unverified. This is a partial P4 implementation, not a general linked view; read-only `viewSpec`, schema preview/undo, data export/import and aggregates remain open.

**ACT-0172 update:** an initial read-only `linked_view` now supports active same-scope PERSON/PROJECT/NOTE sources. Room schema 19 stores a versioned source-kind spec additively; UI and agent reads resolve canonical source IDs/current values directly, without copying facts into table rows. Row/schema edits and export/import preview are refused in this mode until linked-view round-trip semantics are implemented. Full typed view filters/sort/pages, other source kinds, schema preview/undo, aggregates, accessibility and owner/device acceptance remain open; P4 is not closed.

**ACT-0166 update:** completed a read-only source inventory for portable backup. In addition to Room, captures, tasks and legacy voice-money, stable agent scope/actor IDs and language/theme settings need explicit archive handling. Cloud consent, Firebase/App Check installation tokens, Android permission grants, install lineage and scheduled notifications are device-bound; restore must not transfer consent and must recompute notifications. See [PORTABLE_BACKUP_STORE_INVENTORY.md](PORTABLE_BACKUP_STORE_INVENTORY.md). No runtime behavior changed; M20-02 remains partial.

**ACT-0165 update:** a user-facing Android 14 SAF round-trip in a disposable profile restored the SQLCipher Room database but did not restore a saved synthetic Inbox note. Capture history is stored separately in `captures-prototype.bin`, outside the current Room-only backup. Separate task and voice-memory file stores also exist, though they were not round-trip tested in this package. Whole-app portable backup completeness is therefore an open M20-02 gap; see [PORTABLE_BACKUP_SCOPE_GAP.md](PORTABLE_BACKUP_SCOPE_GAP.md). The disposable profile was removed; no owner data was read or changed.

**ACT-0158 update:** portable backup is connected to an AZ/RU/EN screen using Android SAF. The user sets a 12+ character password, selects a document, verifies import metadata and confirms replacement separately. Size is checked before KDF; failed output attempts partial-document deletion; restore reopens the application through startup recovery. Full host suite 60/302 and lint 0/63/3; synthetic Android14 storage/cutover probe passed. SAF provider interoperability and physical owner acceptance remain open, so M20-02 is partial.

**ACT-0157 update:** active SQLCipher restore cutover and pre-Room startup recovery now exist behind an internal confirmation-gated API. Synthetic Android 14 passed a successful replacement, candidate-validation rollback, and injected restart recovery at three journal phases plus interrupted rollback cleanup; full suite 60/301, lint 0/60/3. The API is not connected to SAF/UI. Injected callback interruption does not prove force-stop or hard power-loss durability. M20-02 remains partial pending user workflow, device/owner acceptance and broader fault matrix.

**ACT-0184 implementation checkpoint (2026-11-04):** P4 editable tables now support owner-reviewed additive optional-column changes, schema revision history, and confirmed restore/redo as a new append-only revision. Restored schemas replace only the active column projection; row JSON values stay intact and hidden values are preserved when a row is edited. Linked views remain read-only. Full Gradle passed: 63 suites / 324 tests, 0 failures/errors/skips; debug and AndroidTest APK builds, AndroidTest Kotlin compilation passed; lint 0 errors / 73 warnings / 3 hints. APK v65 / `1.1.43-table-schema-history`, SHA-256 `c63e99cda02c55f9c416de85a15b95284260a0c9001589c0b0391728f0da71b8`. Synthetic host tests only; no device attached. P4/P3 remain open.

# MEMORA 2.0 — полное продуктовое видение и техническое задание

**Решение владельца:** 2026-11-03  
**Статус:** целевой продуктовый контракт, НЕ реализованный функционал.  
**Baseline на момент утверждения:** Android Memora 1.1.4, ACT-0095, репозиторий jafarovsamir89/Memora.  
**Основное правило:** никогда не переписывать проект с нуля ради этой концепции; сохранять пользовательские данные, работающие инструменты, миграции, аудит и тесты.

**Проверенная реализация ACT-0200:** локальный `search_memory` находит точный активный owner-managed person alias внутри запроса, если он присутствует как целая непрерывная фраза. Ответ содержит canonical person ID/name и происхождение алиаса. Retired/cross-scope aliases excluded. Никаких fuzzy совпадений или автоматических связей. Проверено синтетически на хосте; это не полная приёмка person/project/time retrieval.


**M20-05 implementation slice ACT-0192:** the Cases screen now has AZ/RU/EN local scope-bound search over all non-deleted case/source text and filters for all/open/waiting/completed/cancelled. Search scans in bounded pages and returns up to 30 ranked matches; the no-query view is limited to the newest 100 records for a status. This improves navigation to current and past work; it does not complete premium UI/UX or accessibility acceptance.

**Current implementation checkpoint ACT-0146:** M20-03 synthetic fake-provider and Android 14 SQLCipher close/reopen coverage now joins a manually owner-confirmed finance/domain PERSON, canonical project, exact memory revision, 300 AZN obligation and one allocated 100 AZN receipt; the 200 AZN balance remains outstanding rather than being recorded as income. The original source rows and case links survive the encrypted-database reopen without duplicate project/memory/ledger rows. Full host suite: 58 suites / 292 tests passed; debug/AndroidTest build, Kotlin compilation and lint passed. This was synthetic emulator evidence only; APK/schema remain v57/schema18, owner device/DB and live Gemini acceptance remain open, and M20-03 is partial.

**M20-02 baseline ACT-0148:** a host-tested AES-256-GCM/PBKDF2-HMAC-SHA-256 container and an opt-in Android 14 probe export an isolated Room v18 SQLCipher DB, wrap the encrypted snapshot/key, then reopen and verify 45 total tables, `user_version=18`, and one synthetic profile fact. Later packages add table/integrity checks, manifest and staged validation; the helper remains unwired to the app user workflow, import UI or atomic restore, so recovery is partial. M20-04 remains lexical; official local embedding model options were reviewed, but no model was selected or bundled pending AZ/RU/mixed evaluation, Android resource measurements and license review.

**ACT-0149 update:** exact-word synthetic retrieval queries in Azerbaijani, Russian and mixed AZ/EN all resolve their expected task sources under the current `ranked_lexical` mode. Full host suite: 59 suites / 296 tests, no failures/errors/skips; Android debug/test builds, test Kotlin compile and lint passed. This is a normalization baseline only; it does not establish semantic or paraphrase recall, and M20-04 remains partial.

**ACT-0150 update:** the synthetic-only SQLCipher snapshot helper refuses a source DB outside Room schema 18, runs integrity checks on source and export, and compares every user table and row count. Android 14 synthetic export/wrap/reopen passed for 45 tables and one marker row. Full host suite: 59 suites / 296 tests, 0 failures/errors/skips; lint 0/60/3; debug and AndroidTest builds plus AndroidTest Kotlin compile passed. APK v57 SHA-256 `c9693e2919a9828169a6b924a3fbc8bf017434a3cae9d657365004d0dddda772`. No user-facing backup/import or database activation was added, so M20-02 remains partial.

**ACT-0151 update:** portable inner payload now carries an authenticated manifest for Room schema, app version, creation timestamp, snapshot length and SHA-256 digest; strict parser rejects unsupported/corrupt inputs. Host tests and synthetic Android 14 wrapped snapshot round-trip passed. Full suite: 60 suites / 298 tests, lint 0/60/3; APK v57 SHA-256 `37f17b81e3383af6ca8befffd0f3fa5f5d86cb5727563cdd8bad6f2e054ad579`. This does not implement the user import/export workflow or active DB cutover; M20-02 remains partial.

**ACT-0152 update:** current retrieval now has an explicit negative paraphrase check. Exact AZ/RU/mixed wording finds the expected synthetic task; a paraphrase with no shared terms returns no task result. This confirms the current search remains lexical and does not satisfy the semantic-memory requirement in M20-04.

**ACT-0153 update:** a parsed portable snapshot can be validated in an isolated UUID-named SQLCipher/Room staging DB, including integrity/table readback and close/reopen, with cleanup after both success and wrong-key refusal. Full suite: 60 suites / 298 tests, lint 0/60/3; APK v57 SHA-256 `30e6d2911c058284d8a431bf805e94d9e09b53c19295083a15858c1d0bab85ff`. Active DB is not switched and user restore UI is not implemented; M20-02 remains partial.

**ACT-0154 update:** the encrypted SQLCipher backup export path now has a bounded-memory streaming coordinator, with temporary encrypted envelope committed privately before copying to a caller-provided stream. Synthetic Android 14 probe, full 299-test JVM suite and lint passed. The coordinator is not yet wired to user-facing SAF export; import/cutover/recovery and real-device acceptance remain open. M20-02 remains partial.

**ACT-0155 update:** a bounded-memory importer now verifies the outer AES-GCM tag before reading its app-private temporary payload, validates the manifest/digest, and extracts a closeable SQLCipher candidate for isolated Room/SQLCipher validation. Synthetic Android 14 probe and full 300-test JVM suite passed; failed password/key paths clean temporary artifacts. This does not activate or replace the live database and has no SAF/user restore UI; M20-02 remains partial.

**ACT-0156 update:** local `ranked_lexical` retrieval was measured on 90 synthetic AZ/RU/mixed query variants. Recall@5 was 1.00 in all groups, Precision@5 0.20 with one expected source labeled per query, and MRR 1.00/1.00/0.983; no scope leaks or missing/stale task provenance occurred. The metric does not establish semantic recall, full relevance quality or Android-device performance. No embedding model was selected or bundled; M20-04 remains partial.

**Implementation checkpoint ACT-0115 (2026-11-03):** добавлен частичный source-backed episodic memory slice на Android, Room/SQLCipher schema 14. Сводки привязаны к сохранённым user-turn IDs, имеют локальный поиск и историю ревизий, доступны из экрана Memory и удаляются при удалении/очистке/retention исходных реплик. Синтетический Android SQLCipher probe прошёл Room migration v1→14 на отдельной БД. Живой Gemini synthetic finance chain прошёл пять шагов и restart-readback отдельно на AZ и RU; это не подтверждает автоматическое создание episodic summaries, mixed-language input либо сценарии подарка, бизнес-анализа и BMW. Profile memory, semantic/cross-domain retrieval, непрерывная реальная база устройства и качество всех остальных решений Gemini открыты. Debug APK v35 собран, установлен поверх найденной v26 установки без очистки данных и запущен. Полный статус критериев продукта остаётся частичным.

**Implementation checkpoint ACT-0116 (2026-11-03):** live synthetic Gemini также выбрал archive search и сохранил AZ episodic summary с проверенными source IDs; после force-stop episode прочитан из отдельной SQLCipher DB. Выявлен provider type mismatch: source IDs теперь массив строк в версионированном tool contract v2, а source validation остаётся локальной. Revoke-consent gate passed. Один live recall после search/source read timeout-нулся до final answer, хотя предыдущий verify завершился; поэтому response reliability и acceptance остаются открытыми. APK v35 обновлён и установлен поверх v35 через `adb install -r`, без доступа к реальной пользовательской БД. Полный статус остаётся частичным; см. `docs/ACTION_LOG.md` ACT-0116.

## 1. Наша идея — навсегда

Memora — **личный ИИ-секретарь, друг по тону общения и настоящий помощник в действиях**. Ориентир — польза и контекстность J.A.R.V.I.S., а не его фантастические возможности. Это не «бухгалтерия с прикрученным Gemini», не очередной AI-чат, не CRM-форма и не поисковый агрегатор.

Человек свободно общается голосом и текстом. Memora понимает ситуацию, советует, исследует актуальную информацию, анализирует сохранённые данные, выполняет разрешённые действия, помнит отношения, текущие дела и историю совместной работы. Не каждую реплику требуется записывать в базу. Подходящий инструмент выбирается по смыслу речи, без заученных команд.

**Основная ценность:** пользователь не обслуживает Memora и не соединяет вручную множество таблиц — Memora помогает человеку жить, организуя всё за кадром. Красивые разделы, графики, карточки людей, отчёты, прошлые задачи и история ПРИ ЭТОМ ОБЯЗАТЕЛЬНЫ: ими должно быть приятно пользоваться и исследовать собственное прошлое.

Memora не всезнающая и не всегда точнее человека. Она обязана признавать неопределённость, отличать факты от догадок, не выдумывать деньги/цены/даты/источники, не действовать за пользователя без допустимых прав. Добрый тон без лести; при необходимости аргументированная критика и юмор, но не унижение.

## 2. Обязательные продуктовые сценарии

### S2-01. Живой разговор
Приветствие, шутка, совместное обдумывание идеи, совет, инженерный вопрос — осмысленный диалог без принудительного создания заметки, клиента, задачи или платежа. Тон адаптируется к выбранному стилю и языку.

### S2-02. Подарок Руслану
«Мне завтра на день рождения; что подарить Руслану?» — агент понимает просьбу о совете, не запись в календарь. Уточняет неоднозначное «мне/у Руслана» только когда это важно. При разрешении ищет Руслана и проверенные сведения о его интересах, не выдумывает знакомство/предпочтения. Предлагает конкретные варианты, спрашивает один существенный вопрос (например бюджет). При наличии разрешённого инструмента ищет актуальные предложения, цены, магазины, показывает ссылки/время; без веб-инструмента не имитирует поиск. Предложить напоминание можно, создавать без согласия нельзя. Никакого автоматического заказа.

### S2-03. Честный разговор о бизнесе
«Как думаешь, дела в бизнесе плохи или надежда есть?» — Memora проверяет доступные финансы, долговые обязательства, поступления, расходы, проекты/заказы и сравнимые периоды. Различает поступившие деньги, оборот, прибыль и долги. Точные итоги считает детерминированный движок. Неполноту учёта сообщает, а не выдаёт ложную полную аналитику. Может тактично, но прямо указать на ошибки, дать обоснованные выводы и 1–3 практических шага. Без данных обсуждает ситуацию и предлагает, что собрать, не фантазирует.

### S2-04. Одно событие — одно связанное дело
«Эльчин оставил BMW на ремонт, согласовали 300 ₼, он дал 100 аванса, запчасти придут в пятницу». Сформировать одного человека, дело ремонта, точно трактовать фактически полученные 100 ₼, сохранить договорённую цену и не путать ожидаемые 200 ₼ с полученными, зафиксировать ожидание деталей без вымышленного часа. Связать устойчивые ID, исходные источники, финансовые и временные события. Если пользователь затем спрашивает «Как там BMW Эльчина?», собрать единый контекст. Предложить напоминание, но не включать без согласия. Другая персона с именем Эльчин не должна автоматически сливаться.

### S2-05. Память после долгого перерыва
«О чём мы говорили про подарок на прошлой неделе?» — поиск по архиву и долговременным summaries, ссылки на оригинальные реплики, различение слов пользователя и ответов модели. Не обрезать всю личную память без предупреждения просто потому, что текущая conversation window маленькая. Пользователь может просматривать, редактировать, экспортировать или удалять сохранённое и производные индексы/сводки.

### S2-06. Исправление
«Не Эльчин, а Али», «не 100, а 80» — find exact object ID, preview affected links, исправить транзакционно с аудитом, обновить проекции, карточки, расчёты, память и сводки. Не создавать дубликаты и не терять исходный факт.

## 3. Помощник: стратегии без навязанных режимов

Внутренние стратегии, НЕ обязательные пользовательские вкладки:
- Conversation — свободный разговор и помощь в формулировании мыслей.
- Advisor — советы с учётом достоверных предпочтений, целей и контекста.
- Research — проверяемый внешний поиск с датой и источником, если инструмент разрешён/доступен.
- Analyst — расчёты по полным выборкам, прозрачный период, валюта, охват и неопределённость.
- Executor — CRUD и actions через разрешённый ToolCatalog, readback и подтверждение.
- Follow-through — незавершённые обещания, ожидания, сроки, разумные следующие шаги.
- Memory — проверяемое извлечение знаний о пользователе, участниках, решениях и истории работы.

Модель выбирает стратегию по смыслу. Важные действия проходят программную валидацию, policy, consent и аудит. Не превращать каждую фразу в команды. Поддержать выбор экономной/более способной модели в зависимости от сложности по измеренным показателям, не полагаться на одну Gemini конфигурацию.

## 4. Долговременная память — несколько слоёв, единый смысл

Сохранить существующие SQLCipher, money ledger, domain objects/revisions/links, task events, calendar, tables, captures и audit.

Добавить поверх, не переписывая канонические источники:
1. Conversation archive — прозрачная политика хранения, поисковый доступ, не искусственно считать последние 240 сообщений всей памятью.
2. Episodic memory — summaries с доказательствами: о чём говорили, что решили, что осталось незавершённым; привязка к исходным turn IDs и ревизиям, изменение/отзыв/удаление.
3. Personal profile memory — как обращаться, язык, стиль, устойчивые предпочтения, цели, привычные контексты; явное vs предполагаемое, редактируемое, без тайного чувствительного профилирования. Когда факт выведен из текущей реплики, UI должен по возможности показывать проверяемый точный источник; span цитаты сверяется приложением с оригиналом, а отсутствие span не должно маскироваться как точная цитата.
4. Canonical facts/actions — деньги, события, задачи, календарь, лица, проекты, подтверждения и версии. Междоменный поиск по делу по возможности включает точные, локально проверенные выдержки из исходных реплик; цитата подтверждает формулировку, но не достоверность интерпретации. LLM не считается источником истины.
5. Hybrid retrieval — keyword+semantic when implemented, entities, time, relationships, provenance, context budget, relevance, no-answer when absent.
   - Current partial slice: `search_memory` recognizes literal ISO calendar dates and the bounded relative-day words documented in ACT-0169, matching finance/task/calendar activity using the profile timezone. Bounded ISO-week and explicitly qualified weekday expressions in AZ/RU/EN are supported (ACT-0206); general natural-date parsing remains out of scope for this parser; explicit ISO date intervals up to 366 days are supported (ACT-0210), while longer intervals are rejected.

**Нельзя держать всё в одном MEMORY.md.** Developer AGENTS.md не равен runtime persona. Разделять «пользователь сказал», «модель предположила», «подтверждённый инструмент», «внешний источник».

## 5. Люди, связи, дела — самая важная перестройка концепции

**Identity Resolver:** единый логический PersonIdentity (UUID, aliases, contextual clues), который объединяет представления уже существующего finance PersonRow, domain PERSON, personLabel в задачах, упоминания в источниках и другие модули. Объединение по одному имени запрещено. Неоднозначность → предложение выбрать или уточнение; история сохраняется. Миграции недеструктивные.

Task `personLabel` is descriptive legacy text, not a canonical identity. An exact match against the selected person's current name or an owner-confirmed alias may prioritize a task in the person-link picker, but the app must label it as a suggestion and require the existing explicit owner confirmation before persisting a task/person link. Similar/fuzzy names never create or imply a confirmed association.

**Case / Дело:** продолжающаяся жизненная или рабочая ситуация: ремонт, заказ, клиент, поездка, долг, подарок, проект, семейное дело. Поля: caseId, title, status (open/waiting/blocked/completed/archived), participants, source links, exact event IDs, next action, due/precision, evidence, timeline. Для изменений по текущей реплике UI по возможности показывает проверяемый точный источник: optional quote проверяется приложением против оригинала сообщения и хранится как границы, а не дублированный текст; отсутствие совпадения не маскируется точной цитатой. Не превращать каждую заметку в дело. Case — проекция/контекст, НЕ второй денежный журнал.

**Relationships:** отношения с семантикой и происхождением: участвует, обещал, оплатил в счёт, относится к делу, ждём ответа, связано с объектом. В существующем коде domain_link имеет небольшой набор labels; расширить версионированно, не создавать произвольных ложных связей. Коррекция источника должна пересчитывать производные представления. У всех важных ссылок есть основание, доверие и актуальность.

**Карточка человека обязательна.** Контрагент Самир: имя/псевдонимы, общая сумма реальных обязательств и остаток, задачи, встречи, проекты/дела, история событий, платежи и хронология, быстрые переходы/исправления. Все суммы из единого ledger; один платеж виден во многих экранах, но существует один раз. Красивые карточки — не дамп JSON.

## 6. UX/UI Memora 2.0

**Направление:** современный, спокойный premium mobile design; красивые кнопки, качественная типографика, лёгкие анимации, светлая/тёмная тема, крупный голосовой ввод + равноправный текст, аккуратный пустой экран, интересная навигация, AZ/RU/EN и mixed AZ/RU. Контраст, accessibility/TalkBack, touch targets, reduced motion; реальные скриншоты/визуальная проверка UI обязательны перед приёмкой.

**Главные разделы:**
- **Сегодня:** текущая повестка, 1–3 обоснованных приоритета, ожидающие дела, ближайшие события, долги/оплаты по срокам, быстрый разговор голосом/текстом; если данных нет — красивое приветствие, а НЕ вымышленные карточки.
- **Секретарь:** единый продолжающийся разговор, голос+чат, живая речь, режим обсуждения/действий по смыслу, rich ActionResult cards: создано/исправлено/ждёт уточнения/ошибка, открытие объекта, source, Undo/correct. Успешную запись не скрывать из-за ошибки озвучки.
- **Дела:** open/waiting/done, next actions, объединённые люди/финансы/календарь/проекты/история по каждому кейсу.
- **Память:** единый поиск по всем доступным источникам и архиву, понятная timeline, фильтры, история изменений; существующий Capture-only экран НЕ должен притворяться всей памятью. История человека сначала показывает свежие события, но не должна молча обрезать старые: пользователь может раскрывать хронологию порциями до конца доступной истории.
- **Ещё / Обзор:** финансы, люди, таблицы, проекты, календарь, отчёты, статистика, старые задачи и настройки. Всё доступно для любопытного ручного просмотра.

**Отчёты:** графики за месяц/неделю/год, расходы, долги, дела, завершённые задачи, исторические события; период/валюта/охват/источники обязательны. Дизайн не должен требовать выбирать таблицу прежде, чем можно спросить секретаря. В первом финансовом срезе отчёт агрегирует только сохранённые ledger-события: поступления, расходы/погашения, отдельно возникшие долги и открытые остатки. Денежный поток нельзя называть прибылью; долг не является доходом. Несколько валют показываются раздельно без выдуманного курса. Если нет начального баланса, полного охвата или подтверждённых связей с проектом/категорией, UI и секретарь должны явно обозначить это и отказаться от ложного ROI/маржинального вывода.

**Приоритет:** не косметическая перекраска текущих экранов, а новый coherent journey без потери рабочих функций. Информацию выводить карточками человека/дела/операции, не техническими ссылками между UUID.

## 7. Первое знакомство, чистая установка и будущая регистрация

**Нужен полноценный first-run onboarding, но НЕ фальшивая серверная регистрация.**
- Приветственное интерактивное окно: кто такая Memora, что умеет, приватность и пара простых примеров без фиктивных сохранённых данных.
- Локальный профиль: имя/обращение (необязательно), язык (AZ default), стиль разговора (дружелюбный/прямой, краткий/подробный), timezone; дополнительные интересы только добровольно.
- Отдельные понятные согласия на локальную память, облачную передачу модели, микрофон и уведомления по мере использования. Начать можно без принудительного разрешения всего.
- Язык ответа, тон и уровень подробности из локального профиля можно передавать выбранному облачному AI только после отдельного согласия, текст которого прямо раскрывает эти поля. Имя пользователя и часовой пояс остаются на устройстве, если пользователь явно не попросит передать их как часть конкретного запроса. Отзыв согласия должен немедленно остановить передачу следующих настроек; память и записи остаются под отдельным согласием.
- Сохранить версию onboarding; существующие установки не заставлять повторять и не стирать.
- При ПЕРВОЙ ЧИСТОЙ установке нового локального профиля ВСЕ пользовательские данные действительно пусты: 0 контактов, задач, финансов, проектов, заметок, историй, отчётов. Показывать примеры-подсказки, не записывать demo rows.
- **ВАЖНО: обновление APK (adb install -r, Play update) ОБЯЗАНО сохранять реальные данные.** Не выполнять очистку «при каждой установке». Reinstall/uninstall/Android Auto Backup могут восстанавливать или терять данные; определить и проверить политику Android backup/restore+Keystore, opt-in secure import/export. Никакого silent destructive reset.
- Уже сейчас: stable localProfileId, owner/scope, consent settings, nullable future remoteAccountId. «Локальный профиль» честно отличать от настоящей облачной регистрации.

**Будущий сервер:** после owner-approved checkpoint MEM-14.09. Реальная sign-up/sign-in/session/device/account, guest→cloud data linking с явным согласованием, tenant isolation, synced memory, encrypted backups, conflict/version, event IDs, server AI gateway, Android/iOS/web, возможность удалить/экспортировать. Никаких фиктивных логинов и заявлений о готовой синхронизации прежде реализации. Server path заложить в контракты сейчас, production backend не поднимать без принятия native phase.

## 8. Не зависеть от Gemini: важнейшее требование

В текущем коде существует AgentModelGateway и AgentOrchestrator — **сохранить**. Gemini — только тестовый AI provider.

Архитектура:
- AgentModelGateway/AIModelProvider interface — canonical messages, replies, tool calls, tool results, errors, cancellation, usage, capabilities.
- ProviderAdapters: существующий Gemini; впоследствии иной approved provider. Один model-independent ToolCatalog со схемами/версиями/валидаторами; provider adapter лишь преобразует tool schema/structured response API.
- STTProvider, TTSProvider — независимые швы для речи; смена LLM не обязывает сменить TTS, а смена STT не меняет ledger.
- SystemPersona и memory/context builder отдельно от vendor-specific SDK; точные AI модели/цены выбираются по тестам и с разрешения на платные подключения.
- Domain tools выполняются только приложением/сервером под policy; модель не получает SQL, shell и unrestricted actions. Server-side API keys и provider gateway в серверной фазе; секреты коммерческих поставщиков не хардкодить в APK.
- **При смене Gemini не переписывать ни один финансовый расчёт, CRUD, календарь, задачи, case logic или database.** Потребуется лишь интеграция API нового провайдера, типы вызовов и contract/e2e testing. Ввести synthetic fake second provider в unit tests как доказательство.
- Если новый провайдер не поддерживает нужные функции, заявить ограничения, не изображать успешное действие.

## 9. Аналитика, исследования и разумная инициатива

- Domain analytic queries по периодам, категориям, контрагентам/проектам, cashflow/profit отдельно, outstanding obligations, запланированные vs реальные оплаты, completion metrics; точные minor units; полнота/период/валюта/источник. Текущие snapshots задач/дел могут помогать с follow-through, но не доказывают периодную продуктивность, доход, расходы или прибыль без timestamped и связанных источников.
- Agent может делать выводы и спорить, но обязан отличать интерпретацию от проверенного факта.
- Web research tool с датой и ссылками — отдельный optional backend/provider, без разрешения на покупки или внешние изменения.
- Attention engine собирает действительные незавершённые дела, обещания, pending questions, due dates и предлагает 1–3 шага. Ежедневные/недельные сводки, напоминания, quiet hours и частота — по отдельному consent. Не слушать в фоне и не имитировать постоянное сознание.

## 10. План разработки — эволюция, не rewrite

Каждый этап заканчивается реальной проверяемой вертикалью, сохраняет старое функционирование:
- M20-00 — code/data/UI baseline, migration/backup risk inventory, gap assessment, проверка существующих тестов.
- M20-01 — provider abstraction, persona, natural conversation/advice vs action, correct tool routing; alternate fake provider contract tests.
- M20-02 — first-run onboarding, пустая новая БД, non-destructive upgrade, Android backup/restore policy.
- M20-03 — PersonIdentity/cases/relations unified bridge, карточка человека, BMW/Эльчин cross-domain timeline. Person detail retrieves cases by explicit participant identity within the active scope, and the bounded related-case list must be expandable so unrelated newer work or an arbitrary short preview does not conceal linked history.
- M20-04 — personal/episodic memory, semantic+keyword search, evidence, retention and forgetting, multi-turn continuity.
- M20-05 — premium Today/Secretary/Cases/Memory/People/Reports UI, rich result cards, screenshots/accessibility.
- M20-06 — exact analytics, advice, approved external search, gifts/business examples.
- M20-07 — follow-through, priorities, optional reminders and briefings.
- M20-08 — full native reliability, user acceptance AZ/RU/mixed, non-destructive migrations, proper release checks, all full-product native scope.
- M20-09 — backend registration/sync/multi-platform ONLY after MEM-14.09 approved. Design compatibility now, actual cloud transition then.

Не сокращать ранее зафиксированное полное видение: документы/OCR, интеграции, безопасность, документы, аналитика, мультиплатформенность и коммерческий контур остаются в долгосрочном плане.

## 11. Приёмка: не обещание, а доказательство

1. Дружеский диалог и приветствия без лишних инструментов.
2. Руслан/подарок: разумный совет по памяти с проверенным контекстом/уточнением, optional research, без выдуманного заказа.
3. Оценка бизнеса: источник, период, точная арифметика и обоснованный совет либо признание неполноты.
4. BMW/Эльчин/300/100/пятница: Person, Case, источник, finance, open loop; после перезапуска «что с BMW?» возвращает верный context.
5. People card показывает финансовые события, остатки, задачи, проекты, встречи и хронологию; одно имя не означает одного человека.
6. Today/Memory действительно объединяют данные агента и прежние captures, не показывают фальшивые demo results.
7. New clean install пустая с onboarding; upgrade поверх персональной БД НЕ очищает данные; recovery/backup behaviour проверено.
8. Gemini и fake alternate provider через одну структуру tool results с одинаковыми domain semantics; no rewrite ledger.
9. Изменение/удаление факта пересчитывает downstream cards/summaries/reports, сохраняет provenance и право удаления.
10. Tests, lint, migration/device checks, AZ/RU/EN, приватность, полномочия, продуктовая UI-проверка, документация и Git commit с доказательствами. Серверный stage не считать готовым до его фактической реализации.

## 12. Правила реализации и связанные файлы

Повторно использовать существующие AgentRuntime, GeminiAgentModelGateway, LocalMemoryTools, ProtectedDomainRepository, ProtectedMemoryRepository, ProtectedConversationRepository, AgentDatabase, AgentVoiceTurnProcessor, VoiceFirstScreen, AgentChatScreen, MemoraApp, WorkingScreens, SavedObjectsScreen, FinanceScreen, TablesScreen, TaskPrototype, миграции и тесты. Рефакторить только адресно, с regression tests и readback.

**Это утверждённый MEMORA 2.0 product contract.** Файл docs/MEMORA_2_0_AGENT_MASTER_PROMPT.md — подробное поручение исполняющему кодинг-агенту. Исторические docs/VISION.md, FULL_PRODUCT_SPEC.md, USER_EXPERIENCE.md, DOMAIN_AND_DATA.md, SECURITY_AND_PRIVACY.md, ENGINEERING_PLAN.md, PROACTIVE_ASSISTANT.md и TODO.md сохраняют полный scope и safety gates; этот документ приоритетен для нового UX и миссии, НЕ ослабляет безопасность/приватность.

Никаких автоматических стираний реальных данных, массовых объединений людей без доказательств, несанкционированных платных подключений или заявлений о готовности без приёмки.
