**Current checkpoint ACT-0230 (2026-11-06):** owner stopped the Azerbaijani synthetic live Gemini campaign after 44 connected text turns: 41 passed and 3 failed because an episode summary omitted one required amount. Added a localized Settings model picker and rate-limit shortcut, with local persistence through the existing `GeminiAgentModelGateway`. Options are Gemini 3.5 Flash Lite (default), 3.1 Flash Lite and 3.6 Flash; 3.6 replaced 3.8 after the prior test-key probe showed 3.8 HTTP 503 twice and 3.6 HTTP 200. Full Gradle on final code: 71 suites / 357 tests, 0 failures/errors, 1 opt-in live test skipped; debug/AndroidTest builds and AndroidTest Kotlin compilation passed; lint 0/75/3. APK v88 `1.1.66-gemini-model-selection`, SHA-256 `52aae5fd5bbb9f705fd97f82c0b6220ab300a52d947abc267b143a1dd346ea45`. The phone is currently absent from ADB, so physical install/UI acceptance is not claimed. This is not completion of the 1,000-turn campaign, audio validation or release readiness. See ACT-0230 in diary, action log, TODO and `docs/voice-evaluation/README.md`.

**Previous checkpoint ACT-0228 (2026-11-05):** created a deterministic 1,000-row synthetic voice-script corpus: 250 each for AZ/RU/EN/mixed, 10 intent/safety categories, 800 development + 200 reserved rows. Corpus SHA-256 `9dec464ea8d29a9a1d6cdcbdacd9a04a55563b9111bcbec3078acd72331b52bf`. Generator `--check` passed. `VoiceScenarioCorpusTest` checks all 1,000 rows for balanced/unique fixtures, tool catalog, no-write expectations, exact money/date slots, locale policy and production transcript buffering. Full Gradle: 68 suites / 350 tests, 0 failures/errors/skips; debug + AndroidTest builds and AndroidTest Kotlin compile passed; lint 0/73/3. This is a text-fixture and app-contract regression only: no audio, microphone or Gemini request was used; it does not measure STT/WER, intent accuracy, answer/TTS quality or model reliability. The reserved split is not speaker-separated and does not close MEM-02.02/03/05/06 or MEM-13.09. No runtime code/APK/device state changed in this package. See `docs/voice-evaluation/README.md`, ACT-0228 diary/log and TODO.


**Current implementation package ACT-0210 (2026-11-04):** local `search_memory` filters finance/task/calendar activity by inclusive explicit ISO date ranges: EN `between … and …` / `from … to …`, RU `между … и …` / `с … по …`, AZ `… ilə … arasında`, or `YYYY-MM-DD – YYYY-MM-DD` / `YYYY-MM-DD..YYYY-MM-DD`. Maximum range is 366 inclusive days; invalid dates, reversed and longer ranges are rejected (no endpoint-only fallback). Profile timezone and same-scope boundaries remain. Full Gradle: 63 suites / 337 tests, 0 failures/errors/skips; debug/AndroidTest APKs and AndroidTest Kotlin compile; lint 0/73/3. APK v78 / `1.1.56-memory-date-ranges`, SHA-256 `8a8906fff096068bd1354e9fca6ce045cab1dc65d46d4f026b1574de56f1e135`. No schema migration; implementation and host verification were synthetic. Natural-language month/day parsing, broader judged cross-domain retrieval, TalkBack/owner acceptance and M20-04 remain open. See ADR-034/S72 and diary.

**Verified package ACT-0198 (2026-11-04):** Person task picker now promotes tasks whose saved `personLabel` exactly matches the person’s current name or active owner-confirmed alias, using the alias normalization rule. Suggestions are labeled as unconfirmed; the existing owner-confirm dialog remains the only path that writes a revisioned `task_person_link`. Other tasks remain available separately. Full Gradle: 63 suites / 329 tests, 0 failures/errors/skips; debug/AndroidTest builds and AndroidTest Kotlin compile passed; lint 0/73/3. APK v72 / `1.1.50-person-task-link-suggestions`, SHA-256 `9a02e9fbde5da7b6815dd2c8bfd75cceede669353c1eeac57d40d260d059b251`. No schema change; ADB empty, no device/TalkBack/owner acceptance. See ADR-028/S66; M20-03 remains partial.

**ACT-0182 (2026-11-04):** P3 profile memory permits direct owner-authored facts without model extraction. Source-less provenance is explicitly `user_entered`; same-category normalized duplicates are rejected. Full Gradle passed 63 suites / 321 tests, lint 0/73/3; APK v64 SHA-256 `b3c817d02e65211b34866d9e1563ab34a504156d6522e4bc966ef4d1784b8526`. Synthetic host evidence only; owner-device/TalkBack/broad P3 acceptance remain open. See ADR-020, S58 and TODO.

**ACT-0178 update (2026-11-04):** P3 archive paging now fetches bounded batches of conversation messages rather than one SQL query per turn. Search edits clear the previous page state and stale in-flight responses are ignored. Full suite 60/310; debug/AndroidTest build and compile passed; lint 0/72/3. APK v62 SHA-256 `3533b9ddbe6f5cd7f9bfa7b45cdd51d63cd9fbf0f30638c95c57030727579903`. No device acceptance; P3/P4 remain open.

**Historical ACT-0172 (2026-11-04; superseded by ACT-0173):** P4/MEM-12.14 now has an initial read-only linked view for active same-scope PERSON/PROJECT/NOTE records. `user_table.viewSpecJson` stores versioned source-kind metadata through additive Room 18→19; UI and `read_table_rows` project canonical values directly, with stable source IDs and no materialized duplicate rows. Row writes and schema edits are rejected for linked views. Synthetic behavior, full host suite and lint passed. APK v58 launched on emulator over v57 without clearing data; no user database readback or Redmi/owner acceptance. P4 remains partial; P3/MEM-12.13 remains open. See ACT-0172 diary/action log for evidence and limitations.

**ACT-0165:** UI-driven Android14 SAF round-trip with a synthetic Inbox note completed the Room replacement and startup recovery but failed whole-app content readback: after deleting the test note, import did not restore it. `EncryptedCaptureStore` persists it in `captures-prototype.bin`, outside the Room18 export. `EncryptedTaskStore` and `VoiceMoneyStore` also have active separate file-backed paths; not individually round-trip tested. The disposable profile was removed. This is an M20-02 scope gap. The selected all-store bundle, atomic activation and acceptance direction is [ADR-013](adr/ADR-013-portable-backup-all-stores.md); detailed gap/acceptance: `PORTABLE_BACKUP_SCOPE_GAP.md`. Inventory and implementation remain open.

**ACT-0161:** retrieval test now reports P@1, labeled P@returned and average result count, and rejects duplicate source IDs. Focused baseline: Recall@5 `1.00/1.00/1.00`; P@1 `1.000/1.000/0.967`; P@returned `0.412/0.809/0.396`; mean returned `2.90/1.73/2.77`; 0 scope/provenance/duplicate errors. This clarifies that ACT-0156 fixed-denominator P@5 `0.20` cannot be read as four irrelevant results among five returned because lists are often shorter than five; remaining labels are unjudged. Runtime ranking unchanged.

**ACT-0164:** Added one explicitly negative BMW/payment near-miss per AZ/RU/mixed class to ACT-0163's fully judged 30-task corpus. Focused test passed at 32 queries/class (31 positive + 1 negative): positive Recall@5 `1.000/1.000/0.984`; P@returned `0.409/0.790/0.386`; each negative query returns 3–4 irrelevant IDs. This confirms lexical false positives but is not a threshold or reason to tune ranking yet. Full Gradle passed: 60 suites / 303 tests, 0 failures/errors/skips; debug and AndroidTest builds/Kotlin compile passed; lint 0 errors/63 warnings/3 hints. No production behavior changed.

**ACT-0163:** M20-04's 30-source synthetic retrieval universe is now fully judged per query, and each AZ/RU/mixed class includes one broad query with multiple relevant tasks. Focused test passed at 31 queries/class: Recall@5 `1.000/1.000/0.984`; P@returned `0.423/0.815/0.399`; false-positive IDs `58/22/54`; one relevant mixed ID missed at top-5. No production ranking behavior changed. Full Gradle outcome is documented after the run. These labels establish precision only within this narrow synthetic task corpus; semantic retrieval, wider query/domain coverage and target-device measurements are unproven.

**ACT-0162:** A real Android 14 emulator SAF export→OpenDocument→authenticated import→explicit confirm→active SQLCipher cutover→startup recovery succeeded in a disposable empty synthetic profile. It exposed and fixed a Compose lifecycle bug: `onDispose` read mutable candidate state and closed the newly assigned candidate; the cutover then correctly refused the missing candidate at preflight. The effect now captures and closes its own candidate, with a focused Compose regression. Full Gradle passed: 60 suites / 303 tests, 0 failures/errors/skips; debug/AndroidTest builds, AndroidTest Kotlin compile passed; lint 0/63/3. Post-restart checks found the active DB/key only, with no journal/rollback/import artifacts. Emulator-tested APK SHA-256 `e0db7d28bec2d44f202ece3a9933da118e6a93183d56135db33b77194624846b`; final regression-tested build `292687f8e4a268fba376b81c9cd9cbc71c6b2670e52a093a0ba7dd4a7868f8af`. Empty fixture means no distinct-content readback. Force-stop/power-loss, physical Redmi/owner acceptance, alternate SAF providers/API matrix and KDF timing remain open; M20-02 stays partial.

**ACT-0160:** Android 14 emulator confirmed the v57 backup screen launches the platform DocumentsUI import picker and returns to Memora after cancellation without selecting a file. This was picker open/cancel evidence only; no URI was read and no database was imported or changed. ACT-0162 later completed real SAF flow in a disposable empty profile.

**ACT-0159:** A temporary lexical cutoff requiring 75% of distinct query terms reduced Recall@5 to `0.933/0.833/0.867` and Precision@5 to `0.187/0.167/0.173` for AZ/RU/mixed. The implementation was reverted; baseline focused test passed after revert. Do not tune ranker until the full relevance corpus and k are sound.

**ACT-0158:** portable backup/restore is now reachable from More via AZ/RU/EN Compose UI and Android SAF. Export confirms matching 12+ character password; import requires a bounded known URI size, authenticates before parsing, validates isolated Room18 snapshot, previews schema/time/table count and requires final confirmation before cutover. Partial output deletion is attempted; restore starts a clean process for startup recovery. Compose host route/password validation and full synthetic Android14 storage probe passed. Full host suite 60/302, lint 0/63/3; APK SHA-256 `7bbf131f80e4f21e47e609639c56b37aaf5a802364cd1f0231989cfc1ff0b333`. Real SAF URI transfer, physical Redmi, owner acceptance, force-stop/power-loss and KDF timing are still open. M20-02 stays partial.

**ACT-0157:** recoverable active DB cutover and startup recovery were added after design review. Synthetic Android 14 validates successful activation, rollback on candidate verification failure, and recovery at PREPARED / OLD_DB_MOVED / CANDIDATE_INSTALLED plus interrupted rollback cleanup; full suite 60/301, lint 0/60/3. This is exception-injected state recovery, not real process death or power loss. The API remains internal and not wired to SAF; URI/UI, target-device and owner acceptance remain next for M20-02.

**ACT-0184 implementation checkpoint (2026-11-04):** P4 editable tables now support owner-reviewed additive optional-column changes, schema revision history, and confirmed restore/redo as a new append-only revision. Restored schemas replace only the active column projection; row JSON values stay intact and hidden values are preserved when a row is edited. Linked views remain read-only. Full Gradle passed: 63 suites / 324 tests, 0 failures/errors/skips; debug and AndroidTest APK builds, AndroidTest Kotlin compilation passed; lint 0 errors / 73 warnings / 3 hints. APK v65 / `1.1.43-table-schema-history`, SHA-256 `c63e99cda02c55f9c416de85a15b95284260a0c9001589c0b0391728f0da71b8`. Synthetic host tests only; no device attached. P4/P3 remain open.

# Memora — инженерный план и дополнения к концепции

**Предыдущий проверенный пакет ACT-0146 (2026-11-03):** M20-03 synthetic fake-provider case joins an owner-confirmed finance/domain PERSON, canonical PROJECT, exact `memory_revision`, 300 AZN obligation and one allocated 100 AZN receipt; outstanding amount remains 200 AZN and is not income. Dedicated Android 14 SQLCipher migration probe v1→18 closes/reopens its synthetic encrypted DB and confirms case, exact source IDs/revision, identity and one canonical row per source. Full host Gradle: 58 suites / 292 tests, 0 failures/errors/skips; debug/AndroidTest, Kotlin compile and lint passed (0/60/3). No product code/schema/APK change; v57 SHA unchanged and not installed. Owner/live Gemini acceptance remains open. M20-02 protected export/import and M20-04 semantic/hybrid search remain next distinct packages.

**ACT-0148:** password-protected portable payload plus a SQLCipher snapshot helper have host/JCA checks and synthetic Android 14 Room v18 export/wrap/reopen readback (45 tables, one synthetic fact, schema18). The helper remains disconnected from user export/import UI and atomic restore/cutover; M20-02 remains partial. M20-04 stays lexical until a privacy-preserving on-device model passes synthetic AZ/RU/mixed relevance, license and target-device resource checks.

**ACT-0149:** the exact-term M20-04 AZ/RU/mixed lexical baseline passes; full Gradle reports 59 suites / 296 tests, lint 0/60/3. This only establishes current normalization/ranker behavior. Next: expand the synthetic corpus with paraphrases, inflections, near misses and scope distractors, then compare lexical vs a rights-cleared local model and measure on Redmi-class Android. No embedding model or vectors are selected/stored yet.

**ACT-0150:** the SQLCipher snapshot helper now rejects unsupported Room schema versions and validates both database integrity plus complete user-table/row-count parity after export. Synthetic Android 14 probe passed (45 tables, schema 18, marker read back after reopen). Full Gradle passed: 59 suites / 296 tests, lint 0/60/3; APK v57 SHA-256 `c9693e2919a9828169a6b924a3fbc8bf017434a3cae9d657365004d0dddda772`. This helper is still not wired to user export/import or atomic restore.

**ACT-0151:** an inner encrypted-payload manifest records supported Room schema, app version, creation time, ciphertext length and SHA-256; strict parsing rejects unsupported or damaged payloads before exposing snapshot/key bytes. Host tests and the synthetic Android 14 wrapped export/reopen probe pass. Full Gradle: 60 suites / 298 tests, lint 0/60/3; APK v57 SHA-256 `37f17b81e3383af6ca8befffd0f3fa5f5d86cb5727563cdd8bad6f2e054ad579`. Still no end-user import/export or atomic active-database cutover.

**ACT-0152:** M20-04 baseline now includes an explicit lexical negative: `car servicing meeting` does not retrieve Azerbaijani task `BMW təmir görüşü`; exact AZ/RU/mixed terms continue to pass. Full suite remains 60 / 298; APK unchanged. This fixes the current quality baseline but does not add semantic retrieval.

**ACT-0153:** a parsed encrypted snapshot can now be opened in an isolated UUID-named Room/SQLCipher staging DB, checked, closed/reopened and cleaned without touching the active singleton. Wrong-key rejection/cleanup and the synthetic happy path passed on Android 14. Full Gradle: 60 suites / 298 tests, lint 0/60/3; APK v57 SHA-256 `30e6d2911c058284d8a431bf805e94d9e09b53c19295083a15858c1d0bab85ff`. Atomic replacement/rollback and end-user picker/UI remain unimplemented.

**ACT-0154:** M20-02 now has a bounded-memory streaming export coordinator from active SQLCipher snapshot through authenticated manifest/AES-GCM envelope into caller-owned output; private intermediate output commits through `AtomicFile` and cleans on success/failure. Synthetic Android 14 end-to-end probe passed including manifest/Room18/44-table validation, wrong-key refusal, plaintext absence and cleanup after destination failure. Full Gradle: 60 suites / 299 tests, lint 0/60/3; debug APK SHA-256 `f7bf6ddcfdd4f6d7f1ff5b0297eb89957c7b245687eee8cc24092fe6d82be24b`. Still no SAF UI/import or active DB cutover/recovery; caller destination cleanup, KDF physical-device timing and owner acceptance remain open. Next: SAF integration only after streaming authenticated import and recovery state machine are designed together.

**ACT-0155:** the matching bounded-memory authenticated importer now decrypts into app-private `AtomicFile`, verifies the GCM tag before parsing, validates the inner manifest/digest and extracts the SQLCipher snapshot into an `AutoCloseable` disposable candidate. The candidate passes the existing isolated Room v18 validator; wrong password/key and temp cleanup passed on a synthetic Android 14 emulator. Full Gradle: 60 suites / 300 tests, lint 0/60/3; debug APK SHA-256 `d0b497621ff5a5be07f057e76eacf3bfd5ba8c4949dc39ceb44b31abad4ee03d`. SAF, URI/disk-full UX, crash-safe active DB cutover/rollback and device acceptance are next; M20-02 remains partial.

**ACT-0156:** host evaluation measures 90 synthetic AZ/RU/mixed query variants across 30 cases per language class, including exact, inflected, transliterated and alternate word-order cases. Recall@5 is 1.00 in all groups; fixed-denominator P@5 is 0.20; MRR is 1.00/1.00/0.983; scope and source/revision provenance checks have zero errors. ACT-0161 shows lists average 1.73–2.90 results and adds labeled P@returned; the single-source judgments remain incomplete. Full suite 60/301, lint 0/60/3; APK unchanged. Semantic retrieval and device performance remain open.

**Архивный checkpoint ACT-0113:** APK v34 / 1.1.12-attention, Room schema 13; историческая проверка M20-07 и связанный статус оставлены ниже для журнала.

**Актуальное дополнение ACT-0093 (2026-11-03):** исправлена сквозная запись Gemini в SQLCipher и task event readback; gateway использует `gemini-3.5-flash-lite`, а квотная ошибка отображается как `model_rate_limited`. APK versionCode 24 / `1.1.2-field-secretary` собран и установлен поверх данных на Redmi Android 13. 31 целевой unit test, lint и synthetic migration probe прошли. AZ/RU mutate E2E и локальные readbacks после process restart прошли на APK v23; AZ Gemini restart search прошёл, RU search ограничен подтверждённой квотой. Полный Gemini mutate на установленной v24 после квотного отказа не повторялся. Field acceptance и release gates остаются открытыми.

**Предыдущее дополнение ACT-0089 (2026-11-02):** пункты меню «Люди» и «Проекты» подключены к защищённому локальному хранилищу агента; доступны просмотр, локальный поиск и подробности сохранённых записей без демонстрационных данных. APK versionCode 20 / `1.0.8-field-secretary` установлен поверх текущих данных и запущен.

**Предыдущее дополнение ACT-0087 (2026-11-02):** Gemini finance tool принимает сумму как точное целое/десятичное число либо строку; однозначная запись не требует даты или причины, omitted date становится сегодняшней датой Asia/Baku. В prompt закреплено немедленное сохранение долга, после подтверждённой записи можно задать необязательный follow-up. APK versionCode 19 / `1.0.7-field-secretary` собран, установлен поверх текущего приложения и запущен. Проверка реальной записи на v19 ещё ожидается; release readiness не заявляется.

**Предыдущий срез ACT-0084:** единый Gemini runtime, SQLCipher data domains и unified finance ledger; текущий статус обновлён в следующем блоке.
**ACT-0085, дополнение полевого среза:** защищённый разговорный архив теперь доступен локальному search tool в пределах 240 последних сообщений; в Gemini передаются только ограниченные найденные фрагменты, и только при отдельном согласии. Calendar screen видим в меню, показывает реальные локальные события; Gemini может прочитать, отредактировать либо soft-cancel одно найденное событие. Версия `1.0.2-field-secretary` / versionCode 14 собрана, установлена через `adb install -r` и запущена на Android 13. Остальные release gates и полная полевая приёмка остаются открытыми.

**ACT-0086, финансовая целостность и Gemini diagnostics:** сохранённое поступление можно атомарно связать с точным долгом; модель получает сумму, уже зачтённую из receipt, а UI показывает привязанные выплаты и актуальный остаток. Отмена такого поступления append-only сторнирует все связанные распределения в одной транзакции. APK `1.0.4-field-secretary` / versionCode 16 собран, установлен поверх данных и запущен. Для текущего отказа «Alinmadi. Yenidən cəhd edin» добавлена privacy-safe классификация ошибок. Автоматические suites/lint и live Gemini запрос не запускались по указанию владельца; повторный запрос с новой диагностикой ожидается.

**Уточнение владельца 2026-10-31:** основной язык UI и механизма — азербайджанский, с RU и смешанной RU/AZ речью без подмены турецким. Встроенные примеры удаляются из рабочего пути, развивается локальная память для повседневного использования владельцем. Инженерные API-пробы — только синтетические, пока только Gemini; другие провайдеры/оплата не подключаются. Пакет 0.5 не закрывает полную память или нативную приёмку: [результаты и языковые ограничения](VOICE_EVAL_2026-10-31.md), ACT-0035. Порядок Android → подтверждение всего функционала → сервер остаётся прежним.

**Зафиксировано:** 2026-10-28, America/Los_Angeles. Это дата обсуждения с владельцем; исходные документы имеют baseline 2026-10-29.
**Статус:** план и требования, не отчёт о реализованном приложении. Текущий ход работы — [дневник](AGENT_DIARY.md).

**Исполнение:** основной [TODO.md](../TODO.md) раскрывает этапы E0–E7 в задачи с постоянными ID, зависимостями и условиями приёмки. Здесь хранится обоснование и порядок; там — фактические галочки; в дневнике — контекст, проверки и следующий шаг. Сохранение списка не означает выполнения будущих работ.

**Historical ACT-0173 (2026-11-04; superseded by ACT-0174):** P4 linked views now have filter/sort/25-row UI paging, active-source totals and a visible 500-source limit. The agent read tool supports bounded offset/limit and reports truncation. Synthetic regression plus full Gradle passed: 60 suites / 308 tests, lint 0 errors / 72 warnings / 3 hints. APK v59 hash and limitations are in TODO and the validation matrix. P3/P4 remain open; no Redmi or owner acceptance.

**ACT-0174 (2026-11-04):** P4 linked views now page and search across the full active source set: DAO reads 200-row chunks inside one Room transaction, filtering in Kotlin preserves AZ/RU/EN case-insensitivity, and a bounded priority queue retains only the requested locale-sorted window. UI pages 25 records; agent accepts exact offset/limit. Full Gradle 60 suites / 308 tests, lint 0/72/3; APK v60 hash is in TODO and validation matrix. Offset cap 100,000 is explicit. P3/P4 remain partial; no owner/device acceptance.

**ACT-0186 (2026-11-04):** P3 profile-memory source evidence now supports optional locally verified exact excerpt offsets (Room 19→20, nullable UTF-16 ranges); the quote itself is not duplicated. Missing/false quotes remain source-linked without claiming exactness. Full Gradle passed 63 suites / 325 tests, lint 0/73/3; APK v66 SHA-256 `b918987cbf98b6389360f5c825db2936a6fe8bb234e1481dab842103df0682c5`. Next: apply broader source/provenance semantics to canonical entities/cases and continue memory-diary UX/recovery proof. Owner device/TalkBack and migration probe remain open; this is not P3 closure.

**ACT-0188 (2026-11-04):** case revisions now support optional locally verified exact-source offsets (Room20→21); portable SQLCipher backup exports schema21 and accepts current21 or Room18 legacy imports, staged forward to21. Full host suite 63/326, lint 0/73/3; APK v67 hash `9954707dfbbf0b3d747718f1379cfab224d6b811849cd090cfc1ee1f82c3eabb`. Android migration/legacy import probe was compiled but not executed; device/owner/TalkBack acceptance and all-store backup remain open. See diary and ADR-023/S61.

## 1. Основание и принятые решения

Полное видение из [README](../README.md), [VISION](VISION.md) и [FULL_PRODUCT_SPEC](FULL_PRODUCT_SPEC.md) сохраняется: независимый AI-секретарь, управляемая пользователем долгосрочная память, голос/текст/документы, люди и проекты, деньги и обязательства, задачи и календарь, поиск, проактивность, интеграции, совместные пространства, Android/iOS/веб, RU/AZ/EN.

Прямые решения владельца:

| Решение | Последствие для разработки |
| --- | --- |
| Сначала реальные повседневные задачи владельца, затем похожие пользователи | Первые сценарии и ежедневная приёмка опираются на его опыт; позже проверяем переносимость на других людей |
| Первая рабочая сборка — Android | Android становится первым эталонным клиентом; iOS и веб сохраняются в полном плане |
| Работаем вдвоём: владелец и AI-ассистент как ведущий инженер | Нужны простая эксплуатация, короткие завершённые изменения и воспроизводимые проверки |
| Ценовые вопросы полностью отложить | Не начинать сейчас бюджеты, тарифы, ценообразование и экономические расчёты; прежние документы сохраняются, расходы этим решением не разрешены |
| Сохранять мысли, этап и решения в репозитории; перечитывать дневник | `AGENTS.md` задаёт рабочие правила, `AGENT_DIARY.md` хранит состояние и историю |
| Уточнение 2026-10-29: сначала нативное Android-приложение, после тестирования и подтверждения всего функционала — адаптация к серверной инфраструктуре | Первые данные, доменные операции и фоновые задания локальны; собственный backend реализуется после приёмки нативного этапа |

Рекомендации ниже — зафиксированное направление проектирования. Они не заменяют ADR и не означают, что все детали утверждены владельцем или реализованы. Сроки и количественные показатели качества ещё не установлены.

### 1.1. Нативный Android → подтверждение функционала → серверная инфраструктура

**Решение владельца, 2026-10-29, Asia/Baku:** «начальная разработка это нативное приложение для андроида», которое «после теста и подтверждения всего функционала» будет переделано под серверную инфраструктуру. Это уточнение заменяет прежнюю последовательность, где собственные API, серверная БД и обработчики были частью фундамента до первого сквозного Android-пути. Исторические записи дневника сохраняются.

1. **Нативный этап:** приложение Android хранит канонические данные, источники и историю локально, выполняет доменную валидацию, поиск, расчёты и фоновые операции через локальные реализации. Полный функциональный объём сохраняется; первый сквозной путь — промежуточный результат, а не разрешение немедленно перейти к backend.
2. **Обязательная приёмка перед сервером:** весь функционал Android-приложения должен быть реализован, протестирован и подтверждён владельцем на реальном устройстве. Нужен реестр функций и сценариев с результатами, версиями сборки, известными ограничениями и отсутствием блокирующих ошибок. Контрольная точка — MEM-14.09. Отсутствие ответа, успешный пример, заглушки или закрытие одного текстового пути не заменяют подтверждение.
3. **Серверный этап после приёмки:** адаптировать приложение к серверной инфраструктуре, перенести согласованные доменные обязанности и канонические данные на сервер, подключить API, аккаунты, серверную обработку и синхронизацию. Android остаётся нативным клиентом. Миграция не должна терять источники, ID, связи, версии, историю, точные суммы или менять подтверждённую семантику времени.
4. **Повторная проверка:** сравнить результаты нативной и серверной реализации на одинаковых сценариях, проверить перенос существующих данных, сбои, повторы и возврат к рабочей копии. Серверная изоляция пользователей, распределённая синхронизация, общие пространства и внешняя доставка имеют собственные реальные проверки после появления инфраструктуры; локальная имитация не доказывает эти свойства. Их место в реестре приёмки и границы нативной проверки нужно определить в ADR и MEM-01.12, не выдавая непроверенное за подтверждённое.

**Инженерное предложение для выполнения решения:** с начала отделить Android UI, доменные правила, хранилище, задания и AI-адаптеры интерфейсами. Сначала реализовать локальные адаптеры; затем заменить необходимые реализации серверными. Это не обещание перенести Kotlin-код на сервер без изменений: контракты, данные и сценарии сохраняются, серверная реализация может потребовать отдельного кода.

Нативный этап не означает, что STT/LLM/TTS обязательно работают на устройстве или без сети. Безопасный способ доступа к внешнему AI, хранение ключей и разрешённые маршруты данных остаются вопросами ADR-007; общий секрет провайдера нельзя встраивать в распространяемый APK. Это решение не разрешает расходы и не возобновляет ценовую тему. Коммерческий раздел остаётся отложенным; его отсрочка не является подтверждением реализации коммерческих функций.

## 2. Инженерная оценка и главная задача

**Порядок владельца:** сначала APK нативного UI-каркаса и дизайн, затем функциональные задачи TODO. Каркас установлен, дизайн утверждён 2026-10-30; полная техническая проверка на телефоне в MEM-03.12 ещё открыта. Оформлены ADR-001–009, начаты [доменный контракт](DOMAIN_CONTRACT_DRAFT.md), [модель задач](TASK_DOMAIN_CONTRACT.md) и [тестовый список](TASK_STORAGE_CONTRACT.md); остальные контракты E1 предстоят. Прототип не допущен для личных данных. Подробности — [UI_SHELL.md](UI_SHELL.md). Порядок полного продукта и контрольная точка MEM-14.09 сохраняются.

Сильная часть идеи — связанная история реальных обязательств и событий с источниками, версиями и исправлениями. Ценность должна проявляться в ежедневном цикле: записал после события → увидел, что понято → получил полезное напоминание → нашёл контекст → отметил или исправил результат.

Существующая документация хорошо описывает конечные возможности и принципы доверия. До реализации необходимо уточнить исполняемые правила: когда гипотеза становится записью, кто разрешает изменение, какие операции связаны, как исправление распространяется на результаты и как восстановить состояние после сбоя.

Основные риски: незаметно неверный факт; неверное направление долга или выбранный человек; расхождение отчёта и обязательства; потеря записи без сети; лишние подтверждения, мешающие ежедневному использованию; непроверенная смешанная речь; нарушение приватности; слишком широкая параллельная разработка. Красивый интерфейс и успешный единичный пример сами по себе эти риски не закрывают.

## 3. Этапы реализации полного продукта

| Этап | Работа и результат | Условие перехода |
| --- | --- | --- |
| E0. Фиксация и сценарии | Зафиксировать решения, дополнить S01–S24, получить обезличенные примеры владельца; для каждого определить вход, ожидание, изменения и последующий запрос | Ближайшие сценарии имеют однозначный ожидаемый результат; неизвестное явно отмечено |
| E1. Инженерные контракты | ADR, словарь сущностей, состояния команд/данных, права, деньги, время, подтверждения, исправления, API и тестовые контракты | Сложные случаи разрешены правилами; есть карта «требование → сценарий → проверка» |
| E2. Нативный фундамент | Android-каркас, локальная БД/миграции, файлы, фоновые задания, локальная защита, тестовое окружение и CI; контракты будущего сервера | Воспроизводимая нативная сборка без собственного backend; локальная сохранность и защита проверены |
| E3. Первый сквозной путь | Текст → предложения → проверка/подтверждение → сохранение → поиск с источником → исправление, доступные из Android | Пользователь проходит весь путь, история воспроизводима, повторы не повреждают состояние |
| E4. Связанные домены | Деньги, обязательства, люди, задачи и напоминания: долг → частичная оплата → новый срок → исправление | Баланс, события, отчёты и напоминания согласованы при отменах и конкурентных изменениях |
| E5. Голос и мобильная надёжность | RU/AZ/EN и смешанная речь; долговечная локальная очередь; перезапуски, слабая сеть, поздняя отправка, разрешения уведомлений | Проверена цепочка от аудио до данных на устройствах и отдельном контрольном наборе; ограничения задокументированы |
| E6.A. Полный функционал нативного Android | Проекты, документы/OCR, поиск вложений, календарь, обзоры, аналитика и интеграции; реестр всех функций Android и результаты испытаний | Весь функционал нативного приложения протестирован и подтверждён владельцем; MEM-14.09 завершена |
| E6.B. Серверная адаптация | Только после E6.A: backend, перенос данных/обработки, API, аккаунты, синхронизация, серверная проверка прав и общие пространства | MEM-14.10–MEM-14.14: миграция без потерь, функциональная регрессия и серверные свойства проверены отдельно |
| E6.C. Остальные клиенты | iOS и веб на проверенной серверной инфраструктуре с общими контрактами | Каждый клиент проходит собственную приёмку; общий backend не заменяет испытания клиента |
| E7. Расширение использования и готовность эксплуатации | Ежедневное использование владельцем, затем похожими пользователями; восстановление, экспорт/удаление, отзыв доступа, миграции, отказ провайдеров | Нет открытых блокирующих дефектов; результаты испытаний подтверждены, известные ограничения видимы |

Исследование голосового тракта начинается параллельно с ранними контрактами: AZ и смешанная речь — существенная неопределённость. Приёмка голоса и офлайн-поведения остаётся обязательной на E5. Ежедневное использование начинается с подходящей рабочей сборки, а не только на E7.

Безопасность, доступность, локализация, диагностика, удаление и восстановление учитываются с первых этапов. Последние этапы доказывают готовность, а не впервые добавляют эти свойства. Описанная последовательность не сокращает полный продукт. Коммерческая инфраструктура остаётся в полном видении; ценовые решения не входят в текущую работу.

## 4. Предлагаемая архитектурная отправная точка

Для начального этапа — модульное нативное Android-приложение с локальной БД, файлами и заданиями. Доменные правила проверяются приложением; локальная фиксация является успешным сохранением без требования серверного подтверждения. Статусы сетевых AI-заданий отображаются отдельно. Следующие пункты описывают **будущий серверный этап E6.B**, а не предпосылки E2/E3:

- Модульный монолит с отдельными фоновыми обработчиками. Логические домены не обязаны сразу становиться независимо развёртываемыми сервисами; выделение обосновывать измеренной необходимостью.
- Android первым; предложенный стек из технической спецификации остаётся кандидатом. Версии, библиотеки, ограничения платформ и провайдеров проверять по актуальной документации перед интеграцией.
- Основная структурированная база хранит каноническое состояние и события; файлы — в отдельном хранилище; поисковые индексы и сводки пересоздаются из разрешённых источников.
- AI формирует типизированные предложения с указанием источников. Доменная логика проверяет права, значения, версии и условия подтверждения; у модели нет общего права записи в базу.
- Для каждого домена определить источник истины и восстановление. Не строить универсальный event-sourcing-фреймворк для всех таблиц без обоснования. Пересборка состояния не должна заново вызывать недетерминированное AI-извлечение.
- Предусмотреть замену AI-провайдера через общий контракт. Первоначально проверить основной маршрут и поведение при отказе; сложная динамическая маршрутизация вводится после измерений. Резервный маршрут также проходит проверку качества и политики данных.
- Модель личных/общих пространств и границ доступа проектируется заранее; пользовательский интерфейс совместной работы реализуется по этапам.

## 5. Решения ADR, необходимые перед production-кодом

Идентификаторы ниже — запланированные документы, пока не созданные и не принятые ADR.

| ID | Предмет решения | Что требуется определить |
| --- | --- | --- |
| [ADR-001](adr/ADR-001-native-architecture.md) | Архитектура и клиенты | Нативный Android с локальными реализациями, границы домена/адаптеров, будущий сервер, условия и план перехода после полной нативной приёмки |
| [ADR-002](adr/ADR-002-memory-provenance.md) | Память и происхождение | Кандидат/сообщённое/подтверждённое, версии, источники, конфликты, проекции |
| [ADR-003](adr/ADR-003-access-and-ownership.md) | Авторизация и пространства | Владение, роли, изоляция, перенос/отзыв доступа, локальный кэш |
| [ADR-004](adr/ADR-004-money-and-ledger.md) | Денежный учёт | Начальные остатки, движения, обязательства/погашения, проводки, FX, округление, корректировки |
| [ADR-005](adr/ADR-005-commands-and-sync.md) | Офлайн и синхронизация | Сначала локальная сохранность/очередь и версии; после приёмки — протокол миграции и межустройственной синхронизации, повторы и сверка внешних эффектов |
| [ADR-006](adr/ADR-006-retention-and-deletion.md) | Хранение, удаление, восстановление | Оригиналы/текст/факты/индексы, сроки, зависимости, экспорт и защита от возврата удалённого после restore |
| [ADR-007](adr/ADR-007-ai-routing.md) | AI и провайдеры | Схемы ввода/вывода, разрешённые маршруты данных, ограниченные повторы, версии моделей, отказ и оценка качества |
| [ADR-008](adr/ADR-008-ai-actions.md) | Команды и разрешения AI | Матрица риска, подтверждение точной версии, отмена/коррекция, составные действия, внешние операции |
| [ADR-009](adr/ADR-009-time-semantics.md) | Время и напоминания | Время события/записи/получения, неполные даты, TZ/DST, повторения, фиксированное/местное время, просроченная очередь |

Документировать достаточно для реализации ближайшего этапа, сохраняя явный список оставшихся решений. Не заменять разработку бесконечным расширением деклараций.

## 6. Дополнения к поведению продукта

| ID | Дополнение или уточнение | Ожидаемое поведение |
| --- | --- | --- |
| P01 | Начальная память и остатки | «На сегодня мне должны 500» создаёт исходное состояние с источником, без вымышленной выручки и истории платежей |
| P02 | Карточка последствий записи | Показать каждое извлечённое действие, его статус и источник; разрешить частичный выбор и понятный частичный успех |
| P03 | Очередь уточнений | Неоднозначные имена, суммы и даты можно разобрать позже; неподтверждённое не выглядит принятым |
| P04 | Управляемое исправление | Показать затронутые объекты/отчёты/напоминания, сохранить корректирующие события, не притворяться, что выполненное внешнее действие отменено |
| P05 | Сверка источников и дубликатов | Различать повтор запроса, повторное сообщение об одном событии и два похожих реальных события; чек и голос могут ссылаться на одну покупку |
| P06 | Персональный словарь | Имена, прозвища, проекты, термины и произношения можно исправить; совпадение имени не разрешает автоматическое слияние людей |
| P07 | Явная семантика времени | «Завтра» привязано к моменту записи; различать встречу в фиксированный момент и «каждый день в девять по местному времени» |
| P08 | Полнота учёта | Ответ/отчёт показывает охват записей, период и нерешённые вопросы; отсутствие сведений не равно нулю или отсутствию события |
| P09 | Управление жизненным циклом памяти | Объяснить последствия удаления исходника/факта/аудио, обновить производные и не возвращать удалённое из резервной копии |
| P10 | Обратная связь на сводки | «Уже сделал», «неверно», «больше не напоминай», «полезно» имеют определённые последствия; единичное отклонение не становится скрытой постоянной настройкой |
| P11 | Онбординг через пользу | Первая собственная запись, проверка и поиск; нужные язык/валюта/TZ и имена уточняются в контексте |
| P12 | Видимость состояния памяти | Небольшой статус необработанного, конфликтов и ошибок синхронизации; сохранённое локально явно отличается от принятого сервером |
| P13 | Практическая переносимость | Экспорт позволяет понять историю, источники и исправления без приложения; формат и полнота подлежат проверке |
| P14 | Работа при отказах | Сохранить доступные исходники и понятное состояние очереди; повторить обработку безопасно; не заявлять успех при неизвестном результате |

Подробности в [UX](USER_EXPERIENCE.md), [памяти](MEMORY_AND_TRUST.md), [данных](DOMAIN_AND_DATA.md), [проактивности](PROACTIVE_ASSISTANT.md) и [сценариях](SCENARIOS.md). Эти дополнения развивают существующее видение, а не заменяют его.

## 7. Критические правила, которые нужно превратить в контракты

- Связанное погашение долга и денежное движение не должны расходиться. Независимые действия одной записи могут иметь отдельные результаты, зависимые требуют согласованного применения.
- Подтверждение относится к точному содержимому и версии действия; изменённая сумма, человек или дата не наследуют старое разрешение. Повтор одного и того же действительного подтверждения не выполняет операцию дважды.
- Исправление во время обработки делает старый результат неприменимым без проверки версии. Исправление транскрипта не переписывает молча уже принятые факты.
- Сетевой повтор устраняется идентификатором операции. Смысловое сходство само по себе не означает дубль; две одинаковые покупки допустимы.
- Доход, расход, перевод, выдача займа, возврат основной суммы и начальный остаток имеют разные правила. Переплата, частичный возврат, отмена погашения, FX и округление требуют конкретных примеров.
- Разделить время события, время записи и время получения; учитывать ошибочные часы устройства, полуночь, позднюю отправку и неоднозначное локальное время.
- Ошибочно объединённых людей можно разделить с восстановлением зависимых ссылок. Слияние не определяется одной векторной близостью.
- Внешняя операция после сетевого сбоя может иметь неизвестный результат: сначала сверить состояние, затем безопасно решать о повторе.
- Отзыв доступа должен проверяться при чтении, поиске, экспорте и выполнении фоновой работы. Нельзя обещать мгновенное стирание ранее скачанного кэша с отключённого устройства.
- Удаление распространяется на зависимости; восстановление резервной копии учитывает последующие удаления. Решение о судьбе фактов после удаления источника должно быть явным.
- Prompt injection может пытаться подменить сумму, человека или источник ответа; защита должна охватывать корректность данных, а не только запрет опасных инструментов.

## 8. Как измеряем качество

Оцениваем весь путь «аудио → текст → сущности → предложения → сохранённое состояние → ответ/исправление», отдельно по RU/AZ/EN и смешанной речи.

Метрики: точность критичных полей; ошибочные автоматические изменения; корректные уточнения/отказы; доля завершённых сценариев без ручной помощи; время на проверку/исправление; правильность источников; полезность сводок; повторное использование. Отсутствие исправления пользователем не доказывает правильность.

Самооценка confidence модели не является готовой вероятностью истины. Пороги автоматического сохранения требуют калибровки по виду операции и языку. Высокую точность нельзя получать отказом отвечать почти на всё: учитывать покрытие и нагрузку подтверждений.

Нужны разрешённый/обезличенный корпус, раздельные настроечные и закрытые проверочные наборы, версии моделей/промптов и регрессии. 10–15 фраз владельца — начальные продуктовые примеры, не достаточная выборка для заявления о production-качестве. Численные пороги, размеры выборки, условия нагрузки и ограничения фиксируются до соответствующей приёмки, а не задним числом по удачному прогону.

Детерминированные инварианты проверяются автоматическими тестами, включая конкуренцию и повторы. Реальные устройства, внешние интеграции, слабая сеть, восстановление и ежедневное использование проверяются отдельно. Подробности — [QUALITY_AND_ACCEPTANCE](QUALITY_AND_ACCEPTANCE.md).

## 9. Рабочий процесс и роли

Владелец задаёт продуктовый приоритет, сообщает реальные ситуации и оценивает повседневную полезность. Ведущий инженер предлагает решения и компромиссы, реализует, проверяет, разбирает дефекты и поддерживает документацию. Существенные неопределённости выносятся с конкретными вариантами и рекомендацией; обычные решения в рамках задачи не требуют постоянного переспрашивания.

Каждое существенное изменение: ожидаемое поведение → реализация → подходящие проверки → демонстрация результата → обновление документации/дневника. Проверки соразмерны риску; не выдавать количество тестов за качество.

Поддерживать трассировку: **требование → сценарий → реализация → проверка → статус**. Статусы: описано; реализовано; проверено автоматически; проверено на устройстве; проверено в ежедневном использовании. Не пропускать этапы доказательства и не отмечать неподтверждённое как выполненное.

Сохранять пользовательские изменения, использовать обозримые изменения Git и проверки перед интеграцией. Существенные изменения моделей/промптов/данных должны иметь путь возврата и регрессионные проверки. Периодически перечитывать документацию согласно [AGENTS](../AGENTS.md).

## 10. Ближайший пакет и открытые входные данные

Следующий инженерный пакет: ADR из раздела 5, модель данных/состояний, матрица действий и подтверждений, контракт исправления/будущей синхронизации и таблица приёмки сценариев с разделением нативных и серверных проверок. После него — локальный нативный фундамент и первый сквозной Android-путь. Собственный backend не реализуется до тестирования и подтверждения всего функционала нативного приложения по MEM-14.09.

От владельца запрошены, но ещё не получены: 10–15 естественных фраз и несколько последующих вопросов к секретарю, с заменёнными именами/суммами при необходимости. Их отсутствие не блокирует подготовку контрактов на существующих синтетических сценариях. Модель Android-устройства и версия системы понадобятся перед реальными мобильными испытаниями.

Открыты: точные API/схемы, правила автоматического сохранения по риску, финансовые проводки/округление, условия хранения и удаления, конкретные разрешённые AI-маршруты, пороги качества, сроки этапов. Не выдумывать эти решения и не называть рекомендацию утверждённым ADR.

Не добавлять без отдельного продуктового основания социальную сеть, маркетплейс, автономные платежи, непрерывное прослушивание или собственную большую языковую модель. Они не следуют из текущей задачи. Полный существующий продукт остаётся обязательным ориентиром.
**Current implementation package ACT-0200 (2026-11-04):** local `search_memory` recognizes an exact active owner-managed PERSON alias as a whole contiguous phrase inside a natural query, including punctuation boundaries; result includes canonical person ID/name, alias revision and source turn. Retired, cross-scope and current canonical-name-colliding aliases are excluded; no relationship is inferred or written. Full Gradle passed: 63 suites / 330 tests, 0 failures/errors/skips; debug and AndroidTest builds/Kotlin compile passed; lint 0/73/3. APK v73 / `1.1.51-memory-alias-search`, SHA-256 `4a58bd16f39aff121c6816c32ede901a940c48fcea66a7ab80f81b84cb7f2bfb`. Synthetic host only; no device/TalkBack/owner acceptance, broad person/project/time retrieval or semantic quality claim. See ADR-029/S67; M20-03/04 remain partial.

**Verified ACT-0202 (2026-11-04):** `search_memory` supplements lexical cases with active same-scope `case_participant` records only after exact canonical PERSON/active alias resolution. Max three exact people, up to 100 recent linked-case candidates per person, and snapshots for at most 20 linked cases total; possible truncation is reported. Synthetic regression passed: a case with unrelated title/summary is found by exact person alias only through its existing participant link; unrelated and foreign-scope cases are excluded. Full Gradle 63/331, lint 0/73/3. APK v74 / `1.1.52-person-case-search`, SHA-256 `65ab7129553133cb0cc5ef7282a8c8b892cb3675e12e2a75c4471ab94460c8ae`. No fuzzy resolution or link writes; device/owner acceptance and full M20-03/04 remain open. See ADR-030/S68.

**Verified ACT-0204 (2026-11-04):** Cases screen now shows 30 records per page for ranked search and no-query/status lists, with newer/older navigation, stable case-ID tie-break, reset on query/filter/status update, and explicit hard-bound message. Repository regression pages 67 ranked records without duplicates; Compose regression opens results beyond 30 then returns to the newer page. First full pass identified the local corpus scan's offset variable shadowing the requested result offset; renamed to distinct scan/page offsets, targeted regression passed, then full Gradle passed: 63 suites / 333 tests, 0 failures/errors/skips; lint 0/73/3. APK v75 / `1.1.53-cases-pages`, SHA-256 `39251e14c8ca38a267d963f245a75dc8ebb506ebacb8ed622712baf6170ca72f`. Synthetic host only; no device/TalkBack/owner acceptance or performance benchmark. See ADR-031/S69; M20-05 remains partial.

**ACT-0206 — M20-04 date retrieval slice (2026-11-04):** provider-independent `MemoryTemporalQuery` recognizes ISO week ranges and explicitly qualified weekdays in AZ/RU/EN. Weeks are ISO Monday–Sunday; the profile timezone anchors local dates. Bare weekdays remain ambiguous. Dated local finance/task/calendar sources are filtered without model calls or writes. Synthetic tests cover locale phrases, boundaries and scope. Full Gradle passed: 63 suites / 334 tests, 0 failures/errors/skips; debug/AndroidTest builds, Kotlin compile and lint 0/73/3; APK v76 hash `b7f28653670e1608416b1e46243047e57f8cd6154a9317bc149491438da24416`. Arbitrary ranges, wider temporal language, search quality/resource benchmarks and M20-04 acceptance remain open; see ADR-032/S70.
