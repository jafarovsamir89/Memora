**Historical implementation checkpoint ACT-0158 (2026-11-03):** M20-02 now exposes portable backup in More → Portable backup: AZ/RU/EN copy/password UI, Android SAF create/open document, 12-character minimum and confirmation on export, input size bound before KDF, authenticated import into isolated Room18 candidate, metadata preview, explicit irreversible replacement confirmation, startup recovery and app restart. Failed export attempts to delete its caller-owned partial document; stale private import candidates are removed on entering the screen. Full Gradle: 60 suites / 302 tests, 0 failures/errors/skips; lint 0 errors / 63 warnings / 3 hints; synthetic Android 14 exporter→importer→validator→cutover/rollback/interruption probe passed. APK SHA-256 `7bbf131f80e4f21e47e609639c56b37aaf5a802364cd1f0231989cfc1ff0b333`. UI navigation and password validation were host-tested; Android SAF providers, real force-stop/power-loss and physical-device/owner acceptance were not. M20-02 remains partial; no user DB/device, Gemini or billing was used.

**ACT-0166 design checkpoint (2026-11-03):** read-only portable-backup inventory found additional durable state that must be preserved (stable agent scope/actor IDs and language/theme settings) and device-bound state that must not transfer (cloud consent, SDK/device grants, install lineage, Keystore keys and alarm objects). Consent on a restored device must start revoked; reminders are recomputed under current permissions. See [inventory](docs/PORTABLE_BACKUP_STORE_INVENTORY.md), [ADR-013](docs/adr/ADR-013-portable-backup-all-stores.md) and [diary](docs/AGENT_DIARY.md). No runtime behavior changed; M20-02 remains partial.

**ACT-0167 design follow-up:** repository contains a synthetic file-store→SQLCipher migration helper, but it is not called by normal app flows and only accepts an empty database. Existing users' populated upgrade and screen/source cutover are unproven, so file stores remain part of portable-backup scope.

**ACT-0168 M20-04 implementation checkpoint:** `search_memory` now finds finance, task and calendar activity by explicit ISO date using the local profile timezone, and filters out adjacent dates. Full Gradle passed: 60 suites / 304 tests; lint 0/63/3. APK SHA-256 `f4c2be8377e607902dd572fb1f1a7c18b4eddbd6c7b237cc1df1640bdb2fcc3b`. Natural date language, ranges, semantic retrieval and owner/device acceptance remain open.

**ACT-0169:** exact local activity search also resolves a bounded set of unambiguous relative-day words in Azerbaijani, Russian and English: bugün / bu gün, sabah, dünən; сегодня, завтра, вчера; today, tomorrow, yesterday. The reference day comes from the profile timezone. Full host suite/builds passed; see diary and action log. Other relative expressions and ranges are not implemented.

**Historical implementation checkpoint ACT-0157 (2026-11-03):** M20-02 recoverable active SQLCipher DB cutover is implemented behind an internal explicit-confirmation API and startup recovery runs before opening Room. A same-directory candidate copy, AtomicFile phase journal, Keystore-wrapped incoming passphrase and rollback DB/key envelope protect the old generation until Room18 integrity/table-count/close-reopen verification passes. Synthetic Android 14 instrumentation passed exporter→authenticated importer→validator→cutover, a deliberately rejected candidate rollback, and interrupted recovery at PREPARED / OLD_DB_MOVED / CANDIDATE_INSTALLED plus interrupted rollback cleanup; 44 user tables, one synthetic profile fact, and cleanup were verified. Full Gradle: 60 suites / 301 tests, 0 failures/errors/skips; lint 0 errors / 60 warnings / 3 hints; APK SHA-256 `1780c0cf8bb0d95a41a587753cb37cd51eac9ce014c5cbde191270ae0ed49f31`. These are injected restart-state simulations, not force-stop/power-loss or physical-device evidence. SAF UI, URI/disk-full/error UX, KDF timing on target phone, owner restore acceptance and release readiness remain open; M20-02 remains partial. No user DB/device, Gemini or billing was used. User-owned ADR-012 remains untouched.

**Historical checkpoint ACT-0173 (2026-11-04; superseded by ACT-0174):** linked views now filter and sort locally, paginate 25 records per UI page, report the active source count and disclose the 500-record display boundary. Agent `read_table_rows` accepts bounded `offset`/`limit` and reports incomplete source sets. Full suite: 60 suites / 308 tests; lint 0 errors / 72 warnings / 3 hints; APK v59 hash is recorded in TODO and the validation matrix. Physical Redmi, TalkBack, beyond-500 search, linked-view backup round-trip, aggregates and full P3/P4 acceptance remain open.

**Current implementation checkpoint ACT-0174 (2026-11-04):** linked views now query the full active source set in bounded SQL pages, with Unicode AZ/RU/EN filtering, locale sorting, and 25-row UI pages. Agent read_table_rows uses the same full-source query and exact offsets. A 100,000 offset safety bound is disclosed and reported when reached. Full suite 60 suites / 308 tests; lint 0/72/3. APK v60 SHA-256 704edf0d9a3317246e11411b05bcdc4d428d29fdc11f8bd334cd389e66023997. Device and owner acceptance are not claimed; P3/P4 remain open.

# Memora AI — Your Second Memory

**Current implementation package ACT-0178 (2026-11-04):** protected Memory archive pages now batch-load messages and discard stale results when search changes. Full host suite: 60 suites / 310 tests; lint 0/72/3; debug and AndroidTest builds and Kotlin compile passed. APK v62 SHA-256 `3533b9ddbe6f5cd7f9bfa7b45cdd51d63cd9fbf0f30638c95c57030727579903`. No device attached; P3/P4 remain partial.


**ACT-0172 P4 checkpoint (2026-11-04; superseded by ACT-0173):** custom tables now have an initial read-only linked view over active saved people, projects, or notes. A typed source-kind spec is stored with an additive Room 18→19 migration; views read canonical records live, preserve source IDs, and reject row/schema writes. Full host suite 60/306 and lint 0/70/3 passed; APK v58 launched on the emulator after a data-preserving update. Export/import, preview/undo, aggregates, accessibility, device UI acceptance and complete P4 remain open.

**Historical checkpoint ACT-0139:** Android v54 / 1.1.32-ranked-lexical-memory, Room schema 17. `search_memory` ranks local lexical matches by query coverage, title match and exact phrase; it does not claim semantic or embedding retrieval. Physical owner-device migration and acceptance remained open. See docs/AGENT_DIARY.md and docs/ACTION_LOG.md.

**Historical implementation checkpoint ACT-0154 (2026-11-03):** M20-02 streaming protected SQLCipher export coordinator passed synthetic Android 14 end-to-end validation; full JVM suite 299/299; lint 0/60/3; debug APK SHA-256 `f7bf6ddcfdd4f6d7f1ff5b0297eb89957c7b245687eee8cc24092fe6d82be24b`. SAF picker/UI, streaming import, atomic active DB cutover/recovery and physical owner-device acceptance remain open. See docs/AGENT_DIARY.md and docs/ACTION_LOG.md for scope and evidence.

**Historical implementation checkpoint ACT-0155 (2026-11-03):** bounded-memory authenticated SQLCipher backup importer now produces a disposable candidate validated in isolated Room/SQLCipher; synthetic Android 14 probe passed. Full JVM suite 300/300; lint 0/60/3; debug APK SHA-256 `d0b497621ff5a5be07f057e76eacf3bfd5ba8c4949dc39ceb44b31abad4ee03d`. SAF UI and crash-safe active DB cutover/recovery remain open. See docs/AGENT_DIARY.md and docs/ACTION_LOG.md.

**Current evaluation checkpoint ACT-0156 (2026-11-03):** local lexical search measured against 90 synthetic AZ/RU/mixed query variants; Recall@5 1.00 per language class, Precision@5 0.20 under current one-source relevance labels, MRR 1.00/1.00/0.983. No embedding model was added; this is not semantic-search or device-performance evidence. See [retrieval evaluation](docs/MEMORY_RETRIEVAL_EVALUATION.md).

**Previous implementation checkpoint ACT-0127:** Android v42 / 1.1.20-person-aliases, Room schema 16. People support explicit exact aliases with revision history; finance resolution requires an existing owner-confirmed identity link. 278/278 JVM tests passed according to fresh XML reports; app and AndroidTest APK builds and AndroidTest Kotlin compile passed. Lint was stopped at the owner’s request and remains unverified. APK backup hash matches; v42 installed and launched on Redmi Android 13 without clearing data. Live Gemini, database migration/readback and overall M20 acceptance remain open. See [validation matrix](docs/M20_08_VALIDATION_MATRIX.md), [diary](docs/AGENT_DIARY.md) and [action log](docs/ACTION_LOG.md).

> **Memora 2.0 — утверждённое новое видение (2026-11-03):** [Полное ТЗ Memora 2.0](docs/MEMORA_2_0_PRODUCT_SPEC.md) · [Master-промпт для автономного кодинг-агента](docs/MEMORA_2_0_AGENT_MASTER_PROMPT.md). Основной продукт теперь строится как личный ИИ-секретарь/собеседник с единой памятью, связанными делами и карточками людей, качественным UI и сменяемым AI-провайдером. Сохраняем проверенный Android runtime, инструменты, миграции и данные. Онбординг/локальный профиль для новой чистой установки; обновление APK не стирает пользовательские записи. Серверная авторизация/синхронизация — после подтверждённого нативного этапа. **Это целевое ТЗ; реализация Memora 2.0 ещё не объявлена готовой.**


**Архивный APK-срез ACT-0111:** M20-07 имел локальный opt-in attention slice в APK v34 / 1.1.12-attention, Room schema 13; это историческое состояние до ACT-0120. Текущий и полный статус M20-07/M20-08 ведётся в матрице валидации и дневнике.

**Предыдущий этап ACT-0089:** пункты «Люди» и «Проекты» подключены к защищённой локальной БД; доступны поиск и просмотр записей без demo content. APK versionCode 20 был установлен на Android 13.

**Предыдущий этап ACT-0087:** финансовый tool принимает точные числовые аргументы Gemini, а не только текстовую сумму. Для однозначного события дата и причина необязательны: долг сохраняется сегодняшним числом Asia/Baku, после чего модель может уточнить контекст. APK `1.0.7-field-secretary` / versionCode 19 собран, установлен поверх данных и запущен на Android 13. Владелец проверяет реальную запись; автоматические suites/lint и самостоятельный live Gemini запрос не запускались. Field acceptance и release readiness остаются открытыми. Статус и ограничения — [дневник](docs/AGENT_DIARY.md), план — [руководство реализации](docs/AI_AGENT_IMPLEMENTATION.md).

Эти synthetic tests используют только вымышленные записи и отдельную временную БД; пользовательские данные не очищались и в Gemini не передавались. Реальная дневная приёмка владельцем остаётся отдельным этапом.

**ACT-0038, исправление голосового пути:** добавлены ограниченные вопросы об явно записанных долгах и озвучивание PCM `audio/l16`; долг и получение денег не отождествляются. Старые текстовые источники, произвольный диалог и полный учёт погашений в этот путь пока не входят. Подробности и подтверждённые проверки — [дневник](docs/AGENT_DIARY.md) и [контракт](docs/VOICE_FIRST_CONTRACT.md).

**Новый сценарий владельца, 2026-10-31:** главная — большая кнопка удержания для голосового секретаря: утверждения сохраняются как связанные факты, вопросы ищут ответ в памяти. Первый срез охватывает простые получения денег и вопросы по ним; он ещё не проверен на живой речи и телефоне. Что именно реализовано, ограничения и согласие на облако — [контракт](docs/VOICE_FIRST_CONTRACT.md). Полная память, исправления с историей и приёмка остаются в работе.

**Актуальный пакет 0.5, 2026-10-31:** по решению владельца главный язык — азербайджанский, с RU и смешанной RU/AZ речью. Рабочие экраны показывают локальные записи вместо демонстрационных; есть поиск источников, просмотр голосового текста перед сохранением и отдельный Gemini Transcribe Live. Ошибки смешанных имён и задержки дополнительной письменности остаются: [отчёт проверки](docs/VOICE_EVAL_2026-10-31.md). Статус — [дневник](docs/AGENT_DIARY.md), сравнение маршрутов — [AZERBAIJANI_VOICE_PLAN.md](docs/AZERBAIJANI_VOICE_PLAN.md). Это частичная реализация полной спецификации, а не готовность всего продукта.

> **Расскажи — я запомню. Спроси — я найду. Забудь — я напомню.**

**Product vision • Target: full production product • Status: native Android client with a Gemini-based voice/text secretary and encrypted local memory; the full product remains in development.**

Memora AI — независимый, многоязычный персональный AI-секретарь, превращающий свободную речь, текст, документы и пользовательские события в проверяемую, связанную, долговременную личную память. Он управляет делами, временем, людьми, деньгами, обязательствами и проектами, помогает планировать день и возвращает пользователю контекст по запросу.

**Главное:** это не диктофон с тегами, не очередной AI-чат и не отдельное приложение для финансов. Это управляемая пользователем персональная система памяти и действий с минимальным количеством ручных операций.

## Статус и границы документации

Этот репозиторий изначально содержит **целевую спецификацию полного коммерческого продукта**. Описание функции не означает её реализации, проверки или доступности. Здесь намеренно **нет MVP-сокращения**: требования описывают желаемую production-систему от начала до конца. Порядок разработки описан в [ENGINEERING_PLAN.md](docs/ENGINEERING_PLAN.md); сроки определяются отдельно.

Рабочее название **Memora AI** не означает подтверждённую доступность бренда или торгового знака. Сторонние поставщики, цены и продукты перечислены как кандидаты/предположения, пока их поддержка, условия и качество не проверены.

## Текущая организация разработки

Решения владельца: сначала проверять продукт на его повседневных задачах, затем на похожих пользователях; первая рабочая сборка — **Android**; основная команда — владелец и AI-ассистент как ведущий инженер. Ценовые вопросы полностью отложены до нового указания владельца. Полное видение всех платформ и модулей сохраняется.

**Уточнение владельца от 2026-10-29:** начальная разработка — **нативное Android-приложение с локальными данными и доменной логикой**. Сначала реализуем, тестируем и подтверждаем весь его функционал; **после этого адаптируем приложение к серверной инфраструктуре**. Собственный backend не является условием первой разработки. Android-клиент остаётся нативным; серверный этап переносит соответствующие обязанности на сервер и добавляет синхронизацию. Правила перехода и сохранения данных — в разделе 1.1 [инженерного плана](docs/ENGINEERING_PLAN.md).

Порядок реализации и инженерные дополнения зафиксированы в [плане разработки](docs/ENGINEERING_PLAN.md). Основной [TODO от идеи до production](TODO.md) содержит задачи с постоянными ID, галочками, зависимостями и условиями завершения. Текущий этап, выполненное, открытые вопросы и следующий шаг — в [дневнике проекта](docs/AGENT_DIARY.md). Перед началом работы обязательно читать [AGENTS.md](AGENTS.md), дневник и актуальные пункты TODO. По указанию владельца сначала создан нативный **UI-каркас Android**; теперь частично подключены локальная память, агентные действия, таблицы, финансы, задачи и календарь. Полнота нативного секретаря и проверка в ежедневной работе остаются открытыми.

## Первая сборка интерфейса

Исходники — [apps/android](apps/android), описание экранов, дизайн и ограничения — [UI_SHELL.md](docs/UI_SHELL.md). Текущий клиент показывает сохранённые локальные источники и задачи; демонстрационные записи удалены. Есть светлая/тёмная темы и AZ/RU/EN, основной язык нового профиля — AZ. Целевой телефон владельца — Redmi Note с **Android 13**, 6 ГБ RAM, Helio G96.

Для сборки нужны JDK 17 и Android SDK 36; указать SDK в `ANDROID_HOME` или локальном `apps/android/local.properties`. Из `apps/android`:

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

Результат: `app/build/outputs/apk/debug/app-debug.apk`. Сборка для передачи копируется в `artifacts/Memora-0.1.0-ui-debug.apk`. Артефакты генерируются локально и не включаются в Git. Полноценные функции и серверная инфраструктура по-прежнему требуют реализации и отдельной приёмки.

## Продолжение на другом компьютере

Для друга, который проверяет Gemini, передайте файл apps/android/app/build/outputs/apk/debug/app-debug.apk. Firebase-конфигурация уже включена в эту сборку; на телефоне друга скачивать google-services.json не нужно. В Firebase Console откройте App Check → Apps → ai.memora.app → Manage debug tokens → Create, передайте созданный debug token другу приватно. В APK он откроет Настройки → Firebase App Check test access, вставит токен и нажмёт Save and verify. Приложение покажет результат обмена с Firebase. Это debug APK; обычный release build продолжает использовать Play Integrity.
Для новой рабочей копии:

```sh
git clone https://github.com/jafarovsamir89/Memora.git
cd Memora
```

Если репозиторий уже склонирован, сначала проверить `git status` и сохранить свои незавершённые изменения, затем на ветке `main` выполнить `git pull --ff-only`. При расхождении истории разобраться с изменениями, не использовать принудительный сброс.

Перед продолжением прочитать `AGENTS.md`, `docs/AGENT_DIARY.md`, `docs/ACTION_LOG.md`, `docs/ENGINEERING_PLAN.md`, `docs/UI_SHELL.md` и `TODO.md`. Владелец утвердил дизайн; оформлены ADR-001–009, начаты [доменный контракт](docs/DOMAIN_CONTRACT_DRAFT.md), [правила задач](docs/TASK_DOMAIN_CONTRACT.md), [локальное хранение](docs/TASK_STORAGE_CONTRACT.md), [напоминания](docs/TASK_REMINDER_CONTRACT.md) и [голосовой ввод](docs/VOICE_INPUT_PROTOTYPE.md). [Экспериментальный маршрут Google AI](docs/VOICE_ASSISTANT_NEXT.md) получил ответ на один синтетический текстовый запрос на Redmi. Тестовая сборка предназначена для вымышленных данных; полный AI-помощник и допуск личных данных не завершены.

По отдельному уточнению владельца добавлен [тест облачного голосового диалога Gemini Live](docs/CLOUD_VOICE_TEST_CONTRACT.md): речь и аудиоответ обрабатываются Google после явного согласия. Оценка азербайджанского, русского, английского и смешанной речи на устройстве ещё требуется.

Для облачного теста на другом компьютере скачайте конфигурацию зарегистрированного Android-приложения `ai.memora.app` из проекта Firebase и сохраните её локально как `apps/android/app/google-services.json`. Файл исключён из Git. Без него приложение собирается, но облачный маршрут не настроен. Доступ тестовой сборки на устройстве также зависит от разрешённого в Firebase App Check debug-токена; не публикуйте токены. Подпись debug-сборки на другом компьютере может отличаться, и Android может отказаться обновлять установленное приложение: не удаляйте его без отдельной проверки и согласия, поскольку удаление стирает локальные данные.

Для изменений подготовлена [проверка Android в GitHub Actions](.github/workflows/android.yml): тесты JVM, debug-сборка и lint без конфигурации Firebase; артефакт предназначен только для синтетических данных и не подключает облачный AI. До первого успешного запуска в GitHub сам workflow не считается проверенным. Инструментальные тесты нельзя запускать на основном телефоне: они могут удалить приложение и его локальные данные.

## Суть продукта

1. **Запомнить:** голосом или текстом, по собственной инициативе пользователя, без обязательного выбора папки.
2. **Понять:** разделить одно высказывание на факты, расходы, доходы, долги, обещания, задачи, встречи и идеи.
3. **Связать:** людей, проекты, платежи, историю событий и версии утверждений, не придумывая недостающие факты.
4. **Действовать:** создавать и обновлять задачи, напоминания, расписание и отчёты — со строгими правами и подтверждением рискованных операций.
5. **Вернуть контекст:** отвечать с датами, источниками, уровнем уверенности и возможностью исправить память.
6. **Предупредить вовремя:** персональные утренние/вечерние сводки, просроченные обещания, предстоящие платежи и ненавязчивые рекомендации.
7. **Уважать человека:** по умолчанию не слушать в фоне, не анализировать личные мессенджеры без явного согласия, позволять экспортировать и удалить все данные.

## Документы: полная производственная концепция

| Документ | Содержание |
| --- | --- |
| [docs/VISION.md](docs/VISION.md) | Миссия, аудитории, ценность, позиционирование, принципиальные решения |
| [docs/FULL_PRODUCT_SPEC.md](docs/FULL_PRODUCT_SPEC.md) | Исчерпывающий функциональный контракт модулей и поведения |
| [docs/USER_EXPERIENCE.md](docs/USER_EXPERIENCE.md) | UX/UI, экраны, доступность, голосовые и текстовые сценарии |
| [docs/MEMORY_AND_TRUST.md](docs/MEMORY_AND_TRUST.md) | Контекстная память, факты, связи, происхождение, изменения, противоречия |
| [docs/AI_AND_VOICE.md](docs/AI_AND_VOICE.md) | Мультимодельная архитектура, STT/LLM/TTS, языки, проверка качества, стоимость |
| [docs/CLOUD_VOICE_TEST_CONTRACT.md](docs/CLOUD_VOICE_TEST_CONTRACT.md) | Тест облачного голосового диалога, согласие, остановка и проверка AZ/RU/EN |
| [docs/DOMAIN_AND_DATA.md](docs/DOMAIN_AND_DATA.md) | Сущности, ограничения, денежная и временная семантика, жизненные циклы |
| [docs/TECHNICAL_ARCHITECTURE.md](docs/TECHNICAL_ARCHITECTURE.md) | Клиенты, сервисы, API, фоновые процессы, синхронизация, интеграции |
| [docs/SECURITY_AND_PRIVACY.md](docs/SECURITY_AND_PRIVACY.md) | Приватность, безопасность, управление данными, угрозы и политики |
| [docs/PROACTIVE_ASSISTANT.md](docs/PROACTIVE_ASSISTANT.md) | Утренние/вечерние обзоры, планирование, персонализация, уведомления |
| [docs/QUALITY_AND_ACCEPTANCE.md](docs/QUALITY_AND_ACCEPTANCE.md) | Критерии качества, тестовые сценарии, безопасность изменений |
| [docs/OPERATIONS_AND_RELIABILITY.md](docs/OPERATIONS_AND_RELIABILITY.md) | Production-эксплуатация, наблюдаемость, биллинг, отказоустойчивость |
| [docs/BUSINESS_AND_MARKET.md](docs/BUSINESS_AND_MARKET.md) | Монетизация, unit-экономика, конкуренты, удержание и риски |
| [docs/SCENARIOS.md](docs/SCENARIOS.md) | Сквозные истории использования и ожидаемые результаты |
| [docs/ENGINEERING_PLAN.md](docs/ENGINEERING_PLAN.md) | Решения владельца, этапы E0–E7, план ADR, инженерные дополнения и приёмка |
| [docs/AGENT_DIARY.md](docs/AGENT_DIARY.md) | Обязательный дневник: контекст, этап, выполненное, вопросы и следующий шаг |
| [docs/ACTION_LOG.md](docs/ACTION_LOG.md) | Обязательный лог конкретных действий, проверок, результатов и передачи через Git |
| [TODO.md](TODO.md) | Рабочий чек-лист от идеи до production и сопровождения с доказательствами завершения |
| [AGENTS.md](AGENTS.md) | Правила для будущих AI-агентов и разработчиков |

## Продуктовые аксиомы

- **Источник истины — подтверждённые структурированные данные и события, а не ответ LLM.**
- **У любой значимой записи есть авторство, дата, источник и история исправлений.**
- **Нет бесконтрольных финансовых, внешних или необратимых действий.**
- **Секретарь обязан говорить «не знаю», когда данных нет или они противоречат друг другу.**
- **Пользователь управляет проактивностью, голосом, уведомлениями, памятью и разрешениями.**
- **Русский, азербайджанский и переключение языков в одной фразе — обязательные сценарии качества.**
- **Архитектура независима от одного поставщика AI и одного клиентского устройства.**
- **Цена, стоимость обработки и сложность не должны разрушать доверие к данным.**

## Предполагаемые платформы

Начинаем с нативного Android-приложения; Kotlin/Compose — предложенный стек. После тестирования и подтверждения всего функционала Android-приложения переходим к серверной инфраструктуре. Для этого последующего этапа предложены FastAPI, PostgreSQL + pgvector, отдельные фоновые обработчики и multi-provider AI gateway; затем — iOS (нативный клиент) и адаптивная веб-панель (React/TypeScript). Конкретные технологии требуют ADR; готовой инфраструктуры пока нет.

## Не является частью основной концепции

- Зависимость от WhatsApp или скрытый сбор личных чатов.
- Непрерывное несанкционированное прослушивание микрофона.
- Продажа персональных данных и рекламное профилирование на основе личной памяти.
- Подмена банковского приложения, врача, адвоката или финансового консультанта.
- Ложные обещания «всё помнит идеально» или «всегда действует правильно».

Полная формулировка обязательств и исключений описана в документах выше.

---

**Document baseline:** 2026-10-29. **Owner:** repository owner. **Next:** validate architecture and acceptance criteria against production feasibility.
