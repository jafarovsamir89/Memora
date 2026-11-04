# Memora AI — Memory, provenance and trust specification

**Проектное дополнение ACT-0039:** [руководство агента](AI_AGENT_IMPLEMENTATION.md), разделы 5, 9 и 12, задаёт общий поиск, историю беседы отдельно от канонической памяти, версии evidence, сопоставление и удаление контекста/производных. Ссылка на существующий источник сама по себе не доказывает правильность свободного ответа модели; числа и результаты действий сверяются с инструментами. Архив не передаётся Gemini по умолчанию; новое согласие на контекст и проверки относятся к P0/P5/P7. Это не реализованная общая память.

**Уточнение 2026-10-31:** владелец поручил убрать вымышленные записи и использовать локальную память в повседневной работе, с AZ как главным языком. Реализуемый путь — проверенный пользователем текст → шифрованный источник с версиями → локальный поиск и открытие исходника/истории. Отсутствие совпадений не становится сгенерированным ответом о личной истории. Перенос пользовательской реплики Live в память требует отдельного просмотра; текст ответа AI не включается. Это частичная реализация: извлечение утверждений, постоянные evidence-ссылки, разрешение сущностей и полноценное доверие к фактам ещё открыты. [Решение и приёмка](AZERBAIJANI_VOICE_PLAN.md).

**Этапность, уточнённая владельцем 2026-10-29:** сначала все слои памяти и доменная логика реализуются в нативном Android с локальным хранением. После тестирования и подтверждения всего функционала приложение адаптируется к серверной инфраструктуре с сохранением источников, ID, связей, версий и истории. Серверные операции и распределённые проверки ниже относятся к этому последующему этапу; приёмка локальной памяти не доказывает серверную изоляцию. Основание — раздел 1.1 [ENGINEERING_PLAN.md](ENGINEERING_PLAN.md).

## 1. Память — источник проверяемого контекста, не мешок embeddings

Нельзя сохранять только summary LLM: модель может ошибиться, потерять имя/условие, изменить число или «забыть» важную временную оговорку. Слои:
1. **Capture ledger:** оригинал аудио/текста/файла, автор, источник, время получения, технические метаданные, права и retention.
2. **Transcript:** версия распознавания, язык, альтернативы, confidence, ручные правки.
3. **Proposed assertions:** атомарные гипотезы AI о фактах и намерениях, с цитатами/позициями источника.
4. **Canonical domain state:** факты/объекты, подтверждённые или низкорисково сохранённые доменной логикой; сначала локальной в Android, после перехода — серверной; версия и область видимости.
5. **Immutable-ish event history:** журнал добавления, исправления, отмены, возврата, изменения срока и автора с детерминированным пересчётом состояния; требуемое удаление персональных данных имеет приоритет над обычным audit-retention.
6. **Derived search index:** embeddings + полнотекстовые индексы + ссылки и граф; полностью пересоздаваем из разрешённой канонической базы.
7. **Response layer:** результат поиска с evidence, интервалом времени и признаком полноты; при необходимости LLM только формулирует, а не придумывает.

## 2. Схема факта

Рекомендуемые поля: fact_id, tenant_id, owner_id, subject_id, predicate, value_typed, unit/currency, valid_from, valid_to, observed_at, recorded_at, provenance {capture_id, transcript_id, spans, extractor_version}, certainty, confirmation_state, privacy_scope, supersedes, status, created_by, audited_events. Предпочтения пользователя как отдельный класс фактов с отдельными правами и сроком хранения.

**Время:** отличаем когда событие произошло, когда пользователь о нём сообщил и когда система узнала. «Вчера» раскрывать относительно captured_at и timezone, а не текущего времени ответа.

**Конфликт:** два источника могут сообщать разные числа — хранить оба как заявления; объяснить разницу, запросить подтверждение и в агрегатах использовать только проверенное применимое состояние.

## 3. Entity resolution и связывание

- Отдельные сущности People, Projects, Asset, Account, Obligation, Event, Task.
- Использовать кандидатов с вероятностью и явным merge при неоднозначности. Два человека по имени Эльчин не одно лицо.
- Псевдонимы, номера и организации не объединять только из-за совпадения векторной близости.
- Новая фраза «он заплатил половину» должна быть связана с обязательством лишь при доказанном контексте (последний активный долг, один уникальный человек, валюта). Иначе уточнение.
- Поддерживать split ранее ошибочно слитых сущностей и восстановление зависимых ссылок.
- При исправлении транскрипта предложить корректировку созданных объектов без незаметной перезаписи истории.

## 4. Деньги: событийная достоверность

Пример: исходное обязательство Эльчина перед владельцем 500 AZN. Возврат 200 AZN. Остаток 300 AZN. Срок остатка изменён на следующую пятницу.

События: obligation_created(500 AZN), payment_recorded(200 AZN), due_date_changed(ISO date). Остаток вычисляется детерминированно. Повтор payment_recorded по одному idempotency_key не учитывается второй раз. Отмена платежа — отдельное compensating событие.

Долг НЕ является полученным доходом до фактической оплаты. Выданные взаймы деньги и полученные возвраты корректно категоризируются; перевод между собственными счетами не меняет доход. Несовпадение валют не пересчитывать произвольно без конкретного FX-источника и даты.

## 5. Query planner / RAG

1. Intent gate: factual search, list, calculation, proposal, write action.
2. Authorization and privacy scope before retrieval.
3. Structured query for суммы, статусы, даты и владельцев; semantic+keyword retrieval для свободных текстов; graph neighborhood для взаимосвязей.
4. Re-rank by relevance + temporal validity + evidence quality; bounded context; no cross-tenant contamination.
5. Для вычислений — детерминированные запросы/агрегации: сначала локально в Android, после переноса — на сервере; не свободный ответ LLM.
6. Возвратить evidence links, time window, uncertainty, missing records. «Нет сведений» ≠ «события не было».
7. Защититься от prompt injection внутри пользовательских документов/транскрипций: извлечённый контент — данные, не инструкции модели.

## 6. Privacy lifecycle

Пользователь может выбрать «не сохранять», удалить отдельный факт, аудио или весь аккаунт; при этом изменения распространяются на кэш, embeddings, производные отчёты и, по заявленной политике, бэкапы. Возможность хранить текст после удаления аудио должна требовать понятного отдельного выбора.

## 7. Память о человеке и непринудительная персонализация

Храним только полезное и объяснимое. Секретарь может выучить предпочтение «показывай финансы в AZN» по прямому указанию, но не должен скрыто выводить высокочувствительные характеристики и превращать их в профиль. Пользователь видит «Что ты обо мне помнишь?» и может редактировать/запретить любую запись.

## 8. Проверяемые инварианты

- Каждый агрегат воспроизводим из разрешённых доменных событий.
- Любой денежный факт содержит типизированную валюту и точную сумму.
- Нельзя создать внешнее действие только из неподтверждённого AI-текста.
- Исправление не стирает проверяемую историю (кроме согласованного удаления).
- Все retrieval queries tenant-scoped.
- Изменения семантики даты не происходят после изменения timezone пользователя.
- Unknown не приравнивать к false.
- Источник можно открыть или честно указать, что он удалён.

## 9. Engineering clarification — evidence, confirmation and recomputation

Предложения ниже уточняют вопросы для ADR и тестовых контрактов, а не объявляют механизмы реализованными. Общий план: [ENGINEERING_PLAN.md](ENGINEERING_PLAN.md).

### Основание утверждения и статус обработки

Различать основания: **сообщено пользователем**, **извлечено AI как кандидат**, **подтверждено пользователем**, **подкреплено внешним источником**. Эти признаки могут сочетаться и не обязаны быть одним линейным статусом. Подтверждение означает принятие конкретного содержания записи; оно само по себе не доказывает, что событие произошло в реальном мире. Внешнее свидетельство также имеет происхождение, ограничения и возможные противоречия.

Отдельно определить жизненный цикл предложения: предложено, требует уточнения, подтверждено, применено, отклонено, заменено исправлением. Низкорисковое автоматическое сохранение не помечается как явное подтверждение пользователем. Значение confidence модели не считается измеренной вероятностью правильности без отдельной проверки.

### Источник, исправление и зависимые результаты

- Evidence указывает на точную версию текста/транскрипта и диапазон исходного материала; новая версия не должна менять смысл старой ссылки.
- Исправление транскрипта показывает затронутые предложения и объекты. Новый разбор сравнивается с сохранённым состоянием; изменение суммы, человека или действия не наследует прежнее подтверждение автоматически.
- Сохранённые доменные события воспроизводятся детерминированно. Повторный запуск AI является новой интерпретацией, а не способом восстановить исторический факт.
- Исправление, удаление источника или отзыв доступа инвалидирует зависимые поисковые представления, сводки и кэши. Правила сохранения отдельно подтверждённого факта после удаления источника нужно определить явно; нельзя ни незаметно удалить его, ни сохранить вопреки выбранной пользователем области удаления.
- После восстановления резервной копии удалённое содержание не должно снова появляться в поиске или ответах. Точный механизм и проверка относятся к ADR retention/delete/restore.
- Ответы и агрегаты отражают полноту доступных записей и действующую область доступа. Личная и общая память не объединяются только потому, что модель нашла тематическое сходство.

## 10. Implementation checkpoint ACT-0115 — source-backed episodic layer

Room schema 14 adds a local derived episode with a stable identity based on owner scope and 1–8 retained user-turn IDs. The repository rejects unavailable/foreign/assistant-only sources, stores each changed summary as a numbered revision, and confirms source/revision readback in a transaction. Unchanged repeats are idempotent. Search is owner-scoped lexical matching and verifies every source and the complete revision chain before returning an episode as `derived_not_canonical`; invalid derivatives are removed rather than served. The Memory UI exposes the summary, source turns and previous revisions, and distinguishes deleting a summary from deleting its original conversation. Source delete, archive clear and retention prune remove dependent summaries and their revision history in the same transaction. Local tools require `PERSISTED_MEMORY` consent.

This implementation does not yet create summaries automatically, model editable profile preferences, perform semantic/hybrid search, aggregate financial/task/calendar/case/table evidence into a single memory result, or demonstrate live Gemini selection. Host migration/runtime tests pass. APK v35 was installed with `adb install -r` and launched, but the SQLCipher schema/readback/restart path could not be observed after ADB disconnected; device migration acceptance remains open.

## 11. Implementation checkpoint ACT-0116 — live episodic tool format

Live Gemini testing exposed that `save_memory_episode` declared source turn IDs as a JSON string even though the provider could return a string array. The invalid first attempt could be followed by a successful retry while `AgentRuntime` still reported a partial write. Tool contract v2 now declares `source_turn_ids` as a typed string array, and the local handler still verifies 1–8 retained USER turns in the same scope before commit. Tool descriptions and system instructions distinguish an archived episode summary from a standalone current-message note.

Synthetic live mutate passed: archive search and episode save applied, exact source-link readback passed, and memory-consent revocation blocked subsequent access. A separate process reopened the synthetic SQLCipher database and verified persistence. One live retrieval completed with `search_memory` and source reads; a later repeat timed out before the final answer after archive/source reads. All synthetic DBs were cleaned. Targeted host tests passed 10/10; AndroidTest compiled. The actual app database was not inspected; profile memory and M20-04/08 remain partial.

## 12. Implementation checkpoint ACT-0118 — editable source-backed profile facts

Room schema 15 adds `profile_fact`, `profile_fact_source` and `profile_fact_revision`. The local repository accepts explicit user-stated facts in bounded categories, creates deterministic per-scope identities, links each revision to a retained USER turn, supports idempotent saves, correction with expected revision, manual edit, lexical retrieval and deletion. Source-turn deletion and retention pruning remove derived facts and revision history transactionally. Runtime tools are gated by `PERSISTED_MEMORY`; model-supplied source IDs are not trusted—the current request's exact user turn is attached locally. The Memory screen shows profile facts with sources/revisions and lets the owner edit/delete them. Name, timezone and direct identifiers remain in local profile settings, not cloud-retrievable profile facts.

Host tests cover repository lifecycle, fake-provider tool execution, stale revision conflict, consent revocation and local profile editing. An opt-in Android instrumentation probe migrated a separate synthetic SQLCipher fixture through schema 15 and verified profile save/correction/readback plus source-linked deletion. Live Gemini selected profile save/search/edit and verified revision-2 source readback; consent revocation blocked tools, and a new process after force-stop retrieved the corrected profile fact. A later delete call returned APPLIED, but the follow-up was rate limited and the runtime returned PARTIAL; the probe did not claim delete post-readback or a complete final answer, and explicitly cleaned the synthetic database. No content was emitted in diagnostics. ACT-0120 ran 271 JVM tests, assembled app and test APKs, compiled AndroidTest Kotlin, and lint reported 0 errors / 57 warnings / 3 hints. This does not read or verify the owner's app database. M20-04/08 remain partial pending reliable delete/final-answer behavior, broader retrieval, actual app DB migration/readback, field acceptance and other scenarios.

## 13. Implementation checkpoint ACT-0122 — bounded cross-domain lexical retrieval

search_memory now gathers bounded, owner-scoped evidence from domain records, cases, ledger events, tasks, calendar entries, custom-table rows, retained conversation turns, source-backed episodes and explicit profile facts. Results carry canonical IDs/revisions/source references where available; exact finance totals remain with finance analytics tools. Persistent-source retrieval requires the existing PERSISTED_MEMORY consent. Ledger rows are included only when finance-source migration/configuration is available (financeEnabled); this path does not define a separate finance-consent category. Azerbaijani normalization folds diacritics/transliterations and common inflected prefixes to improve lexical matching. It is not semantic or embedding retrieval and must not be described as such.

The fake-provider E2E regression verifies cross-source retrieval, scope isolation, provenance, no writes and bounded output. Full host validation passed at 272/272, with lint 0 errors/57 warnings; the v38 debug APK was installed without clearing app data. No live Gemini cross-domain call or user DB inspection occurred. The feature is partial until live tool selection, semantic/hybrid evaluation, continuity and owner acceptance pass.

## 14. Implementation checkpoint ACT-0139 — phrase-weighted lexical ranking

The unified local `search_memory` candidate rank now combines matched query-term coverage, a boost for matched terms in the record title, and a boost when the normalized query terms occur as a contiguous phrase in a searchable field. A synthetic test checks that a task titled “BMW repair pickup” ranks above “BMW engine repair” for the query “BMW repair”. The search remains bounded, scope-filtered, read-only and provenance-backed. This is a ranking improvement for lexical matches; it does not retrieve paraphrases or claim semantic/embedding search. M20-04 remains partial until semantic/hybrid evaluation, more natural AZ/RU/mixed queries, live provider tool selection, continuity and owner/device acceptance pass.

## 15. Implementation checkpoint ACT-0143 — correction invalidates derived episodes

When an explicit owner correction or manual edit changes a profile fact, the same local Room transaction now removes derived conversation episodes that cite any prior source turn attached to that fact. The original conversation, correction turn, profile-fact source links and numbered fact revisions are retained; episodes based on unrelated turns remain available. This conservative invalidation avoids presenting a stale summary as current without asking a model to rewrite it. A synthetic repository test covers affected and unaffected episodes, retained turns, and revision readback. No Room migration or provider change is required. Broader correction propagation across canonical cases/projects/domain records, semantic retrieval and live/model field acceptance remain open.

ACT-0144 extends the regression to cover the separate manual-edit path: the summary made from the correction turn is invalidated by a later owner edit, while the correction conversation and three profile-fact revisions remain. The focused repository suite passes 4/4; no product source or APK change.

### ACT-0147 — Portable export cryptography and local semantic-search progress

The first portable-backup crypto primitive is versioned AES-256-GCM with PBKDF2-HMAC-SHA-256, bounded KDF parsing and header authentication. Host and synthetic Android 14 JCA probes pass for round-trip and wrong-password rejection. A separate synthetic Room v18 database was exported with Zetetic `sqlcipher_export`, its encrypted snapshot plus separate key was wrapped in the outer container, reopened under a new local DB path, and one profile fact plus schema version were read back; no plaintext marker was found. The helper/probe is not connected to user export/import UI, URI handling, production temporary-file lifecycle or atomic restore. M20-02 remains partial.

Official model documentation was reviewed for local semantic retrieval. EmbeddingGemma 1 supports 100+ languages and on-device text embeddings but is subject to Gemma Terms. EmbeddingGemma 2 is published under Apache 2.0, while its Android deployment path is still emerging. No model or vector index is selected or added. The current memory retrieval remains lexical; next evaluation must use synthetic AZ/RU/mixed queries, verify target-class resource use and license, and keep derived vectors within SQLCipher with correction/deletion invalidation.

### ACT-0148 — Encrypted snapshot persistence evidence

On the synthetic Android 14 emulator, the SQLCipher snapshot helper exported all 45 Room v18 tables with a separate key, encrypted the payload/key in the portable container, then reopened the result and verified the source fact, schema version and encrypted-only markers. This remains a probe, not a user export/import or restore feature.


### ACT-0149 — Synthetic multilingual lexical baseline

Added a focused test that searches the same synthetic in-scope task records with an Azerbaijani query (`BMW təmir görüşü`), Russian query (`BMW ремонт встреча`) and mixed Azerbaijani/English query (`BMW təmir appointment`). All expected task sources are returned by the current lexical ranker and marked `ranked_lexical`. This is language-normalization baseline evidence, not semantic/paraphrase recall. The evaluation protocol and future local model gates are in `MEMORY_RETRIEVAL_EVALUATION.md`; M20-04 remains partial.

### ACT-0150 — Snapshot copy verification

The synthetic Room v18 snapshot helper now checks source version, `integrity_check` on both source and exported DB, and matching user table names/row counts. Synthetic Android 14 export and reopen passed. This improves snapshot validation only; it does not supply the user-facing import or atomic database cutover required to complete M20-02.

### ACT-0151 — Authenticated portable payload manifest

Portable snapshot bytes now travel with a strict inner manifest for Room schema, app version, creation time, byte length and SHA-256, under the outer AES-GCM envelope. Parser rejects unsupported metadata and altered/truncated snapshots. The synthetic Android 14 export/wrap/parse/reopen probe and 60-suite host run passed; this remains probe-only and is not an import workflow.

### ACT-0152 — Explicit semantic-search limitation

Synthetic exact AZ/RU/mixed retrieval passes, but the lexical engine returns no task for `car servicing meeting` when the stored fact says `BMW təmir görüşü`. This negative result makes clear that semantic/paraphrase search is still outstanding; the search mode remains explicitly `ranked_lexical`.
