# Memora — полный рабочий чек-лист от идеи до production

**Последний пакет ACT-0218 (2026-11-05):** после физического `stt_failed` основной голосовой ввод переведён с собственного WebSocket-вызова Gemini 3.5 Transcribe Live на поддерживаемый Firebase AI Logic SDK `generateContent` с аудиомоделью `gemini-3.8-flash`; App Check передаёт SDK. Добавлены безопасные коды этапа ошибки без записи аудио/текста/секретов. Полный Gradle: 64 suites / 340 tests, 0 failures/errors/skips; debug/AndroidTest APK и AndroidTest Kotlin compile; lint 0/73/3. APK v81 `1.1.59-firebase-audio-transcription`, SHA-256 `bcdb693294520eee36edb4ae7d47f513e95811a23cf46a8f1005bf0834ac1a9b`; установлен поверх v80 на Redmi Android 13 без очистки, firstInstallTime прежний. После смены транспорта физический AZ голос ещё не проверен; MEM-13.08/09 открыты. Подробности — ACT-0218 и дневник.

**Последний проверенный пакет реализации ACT-0210:** `search_memory` фильтрует локальную finance/task/calendar активность по включительному ISO-интервалу дат в AZ/RU/EN и ISO separator forms; максимальная длина — 366 дней, неверные/обратные/длинные интервалы явно отклоняются. Полный Gradle: 63 suites / 337 tests, 0 failures/errors/skips; debug/AndroidTest APK, AndroidTest Kotlin compile; lint 0 errors / 73 warnings / 3 hints. APK v78 `1.1.56-memory-date-ranges`, SHA-256 `8a8906fff096068bd1354e9fca6ce045cab1dc65d46d4f026b1574de56f1e135`. Схема не менялась; synthetic host only, ADB connected only for the subsequent ACT-0212 acceptance. См. ADR-034/S72 и дневник. M20-04 остаётся частичным: естественные месячные даты, ранжирование и native acceptance открыты.

**Последний проверенный пакет ACT-0198:** Person task picker now promotes tasks whose saved `personLabel` exactly matches the person’s current name or active owner-confirmed alias, using the alias normalization rule. Suggestions are labeled as unconfirmed; the existing owner-confirm dialog remains the only path that writes a revisioned `task_person_link`. Other tasks remain available separately. Full Gradle: 63 suites / 329 tests, 0 failures/errors/skips; debug/AndroidTest builds and AndroidTest Kotlin compile passed; lint 0/73/3. APK v72 / `1.1.50-person-task-link-suggestions`, SHA-256 `9a02e9fbde5da7b6815dd2c8bfd75cceede669353c1eeac57d40d260d059b251`. No schema change; ADB empty, no device/TalkBack/owner acceptance. See ADR-028/S66; M20-03 remains partial.

**Предыдущий пакет ACT-0196:** Person timeline reveals all loaded entries in batches of 40 instead of silently truncating at 40. Full Gradle 63/329, lint 0/73/3; APK v71 `1.1.49-person-history-pages`, SHA-256 `6c04e21787f7978dbe7e63d8fbaa68bd5c32dd57288972334b0bb671b78269d0`. Synthetic Compose only; no device/TalkBack/owner acceptance. M20-05 remains partial; see ADR-027/S65.
**Предыдущий проверенный пакет ACT-0194:** Person-linked case history исправлена через same-scope SQL query по participant и локализованное раскрытие up to 100 записей. Полный Gradle 63/329, lint 0/73/3; APK v70 `1.1.48-person-case-story`, SHA-256 `4579ec698f10928135ed3dbe8051ad58670da99a57a60cd46254bb05ac376398`. Synthetic host only; no device/TalkBack/owner acceptance; M20-03 partial. См. ADR-026/S64 и дневник.

**Исторический проверенный пакет ACT-0184:** P4: добавление необязательных полей с preview; история схем и подтверждённое undo/redo как новая ревизия; скрытые значения строк сохраняются и при редактировании. Полный Gradle: 63 suites / 324 tests, 0 failures/errors/skips; debug/AndroidTest APK и Kotlin compile прошли; lint 0 errors / 73 warnings / 3 hints. APK v65 `1.1.43-table-schema-history`, SHA-256 `c63e99cda02c55f9c416de85a15b95284260a0c9001589c0b0391728f0da71b8`. Synthetic host only; физического устройства нет. P3/P4 остаются открытыми; S59/ADR-021.

**Предыдущий пакет ACT-0180:** P4 получил SAF JSON export/import существующей таблицы, v2 typed schema/viewSpec validation, editable v1 compatibility, preview с подтверждением, атомарный идемпотентный импорт 125 строк, strict UTF-8/1 MB и лимит 1,000 строк, linked-view definition-only transfer и точные денежные итоги по валютам. Полный Gradle прошёл: 63 suites / 318 тестов, 0 failures/errors/skips; debug и AndroidTest APK собраны, AndroidTest Kotlin compilation прошла, lint 0 errors / 74 warnings / 3 hints. APK v63 `1.1.41-table-transfer-aggregates`, SHA-256 `15ac1d3492684ba841d9fe71590f3e69f8034434828931b2c19952dbb76e0ddd`. Synthetic evidence only; физического устройства нет. Deleted-row history переносится только полным backup. Schema preview/undo, SAF-provider/device, TalkBack, полный backup round-trip и owner acceptance остаются открыты; MEM-12.14/P4 не закрыт. Сценарий S57 и ADR-019 описывают границы.

**Historical checkpoint ACT-0165:** synthetic UI-driven SAF round-trip restored Room18 but did not restore a saved Inbox note because `captures-prototype.bin` is outside the Room-only bundle. Active task and voice-memory file stores also require explicit coverage tests. Isolated emulator profile removed; owner data untouched. M20-02 remains partial; all-store backup design and acceptance: [PORTABLE_BACKUP_SCOPE_GAP.md](docs/PORTABLE_BACKUP_SCOPE_GAP.md). ACT-0164 M20-04 metrics: 32 queries/class (31 positive + 1 negative), positive Recall@5 `1.000/1.000/0.984`, P@returned `0.409/0.790/0.386`; near-miss false candidates `4/3/4`. Full Gradle 60/303; lint 0/63/3. Ranker unchanged.

Предыдущие ACT-0156/0161 метрики — исторический incomplete-label baseline; их precision не описывает текущую полную разметку synthetic corpus. M20-03 acceptance и M20-02/03/04/release readiness остаются незакрытыми.

**Исторический срез ACT-0139:** APK v54 / `1.1.32-ranked-lexical-memory`, schema 17. `search_memory` получил локальное лексическое ранжирование по покрытию запроса, совпадению в заголовке и точной фразе; семантического/embedding поиска ещё не было. Подробности исторической проверки — в дневнике.
**Создан:** 2026-10-28. **Предыдущий подробный срез ACT-0125:** M20-00 audit завершён; M20-01/02/03/04/05/06/07 частичны. В v40/schema 15 карточка человека показывает кликабельные task/calendar источники только если они явно привязаны к делу, где человек указан участником; источник открывается и возвращается в том же окне. Связи не выводятся по совпадению имён, schema не менялась. Полный host прогон: 54 suites / 274 JVM tests, 0 failures/errors/skips; debug и AndroidTest APK builds, AndroidTest Kotlin compile; lint 0 errors/57 warnings/3 hints. APK SHA-256 A892D58646BC09C47C6B98D68365B606D48325633831BEE7BD877310F3A61757; matching backup C:\memora-backups\Memora-v40-1.1.18-person-sources-2026-11-03.apk. Redmi Android 13 обновлён с v38 через adb install -r, приложение запущено, versionCode 40 подтверждён; установленный до обновления APK сохранён. Содержимое пользовательской БД не извлекалось и не просматривалось; данные приложения не очищались. Владелец пока не может выполнить live Gemini-проверку; Gemini и причина App Check на втором устройстве не подтверждены. Синтетические host screenshots 20–21 просмотрены, но не заменяют device/owner/TalkBack acceptance. M20 и release readiness остаются частичными.

**Предыдущий срез ACT-0127:** APK v42 / `1.1.20-person-aliases`, Room schema 16. Реализованы точные aliases для PERSON, локальное управление и история add/retire/restore; finance tool переиспользует существующую finance identity только через owner-confirmed identity link. Полный JVM suite по свежим XML-отчётам: 55 suites / 278 tests, 0 failures/errors/skips; debug/AndroidTest APK и AndroidTest Kotlin compile прошли. Lint прерван по просьбе ускориться и не заявляется успешным. APK SHA-256 `7AFBE0476AB2AB4A8BE05BDD4A0EF29A0523B292A480407CC17805E5301BFC1C`; matching backup `C:\memora-backups\Memora-v42-1.1.20-person-aliases-2026-11-03.apk`. `adb install -r` и запуск на Redmi Android 13 подтверждены без очистки; личная БД не открывалась/не читалась. Live Gemini и acceptance владельцем остаются открытыми; M20 и релиз частичны.

Это основной список выполнения. [Инженерный план](docs/ENGINEERING_PLAN.md) объясняет этапы E0–E7 и решения, [дневник](docs/AGENT_DIARY.md) хранит текущий контекст, [полная спецификация](docs/FULL_PRODUCT_SPEC.md) определяет продукт. Правила работы — [AGENTS.md](AGENTS.md).

## Как загибаем пальцы

- `[ ]` — не завершено. `[x]` — результат существует и проверен. Начатая или заблокированная задача остаётся `[ ]`; её состояние, причина и следующий шаг записываются в дневник по ID.
- ID `MEM-00.01` постоянны: не перенумеровывать существующие задачи. Новые пункты добавлять с новым ID; отмену или изменение объёма объяснять, не выдавать за выполнение.
- При закрытии пункта добавить рядом короткое свидетельство: путь к результату/проверке или запись дневника с версией кода и условиями. Наличие плана не закрывает задачу реализации.
- По умолчанию инженерная работа за ведущим инженером. `[Владелец]` означает необходимые сведения или решение владельца; `[Совместно]` — совместную продуктовую приёмку. Рутинные решения не превращать в постоянные запросы разрешений.
- Не вести десятки активных задач: одна главная сквозная цель, независимые исследования — параллельно. Перед началом читать дневник, выбрать ID, проверить зависимости; после работы обновить галочки, доказательства и дневник.
- Каждая функция получает соответствующие риску проверки, состояния ошибок/офлайн, права, локализацию и доступность, историю/источники там, где это относится к данным. Для документации достаточно проверок содержания и ссылок; для приложения нужны исполняемые проверки и подходящие испытания.
- Не считать долю галочек процентом готовности продукта: задачи разного размера. Этап закрывается по своему условию выхода, а не по красивому счётчику.
- Крупный пункт перед реализацией раскладывается на проверяемые дочерние задачи с новыми постоянными ID. Родитель закрывается только после завершения всех необходимых дочерних пунктов и общей приёмки.
- Android первым; сначала повседневные задачи владельца, затем похожие пользователи. iOS, веб и все модули остаются в полном продукте. Ценовые вопросы полностью отложены; раздел 22 сейчас не выполняется.

## Где мы сейчас и что делаем следующим

**Активны M20-02/03/04/05/08; M20-01/06/07 частичны.** M20-02 ACT-0165 доказал, что SQLCipher backup восстанавливает Room, но пропускает отдельный Inbox capture store; active task/voice-memory files тоже не включены и требуют тестирования. Нужны all-store bundle design, atomic multi-store recovery, process-death/provider/API/KDF checks и owner acceptance. M20-04 ACT-0164: 32 queries/class, 31 positive + 1 negative; positive Recall@5 `1.000/1.000/0.984`, P@returned `0.409/0.790/0.386`, near-miss BMW/payment queries produced `4/3/4` false candidates; ranker unchanged. Broader synthetic person/project/time/paraphrase corpus and device metrics remain open. M20-03 ACT-0146 synthetic BMW continuity valid; owner/live Gemini acceptance open. Не объявлять M20-02/03/04 или release readiness завершёнными. См. [матрицу проверок](docs/M20_08_VALIDATION_MATRIX.md).

Аудит подтвердил рабочие Gemini tools, AgentRuntime, SQLCipher ledger, capture/task/calendar/table функции и отсутствие production demo seeding. Provider catalog теперь держит все 31 tool contract без Firebase типов; Gemini gateway строит из него декларации и результаты, а fake provider проверяет разговор без записи и запись точной суммы в существующий ledger. Выявлены отдельные источники задач, заметок и людей, отсутствие onboarding/local profile/cases, а также незавершённая переносимость Keystore-защищённой базы при reinstall/restore. Полная карта и ограничения — [MEMORA_2_0_AUDIT.md](docs/MEMORA_2_0_AUDIT.md). M20-01 не закрывается до повторного synthetic Gemini probe после адаптерного рефакторинга.

| M20 checkpoint | Состояние на 2026-11-03 | Следующий проверяемый результат |
| --- | --- | --- |
| M20-00 Audit | Завершён для исходного v26 / schema 8 baseline; текущие изменения отражены в audit. | Повторная сверка после следующих миграций/переходов источника данных. |
| M20-01 Provider-neutral secretary | ACT-0131 на Redmi: synthetic Gemini text и одна function-calling цепочка с результатом и финальным ответом прошли за 14.2/26.5 с. v48 сохраняет неудачный текстовый запрос как черновик без авто-повтора. | Повторить разные AZ/RU и mixed intent chains, подтвердить friend-device поведение, квоты/частичные исходы и стабильность текста/голоса; это не полная приёмка. |
| M20-02 First run/profile | Onboarding and editable local profile exist. ACT-0142 verifies Android backup disabled and SQLCipher key envelope placement. ACT-0147–0158 built a Room-only portable backup workflow; ACT-0188 advances exports to current Room21 and preserves Room18 as legacy input migrated forward in isolated staging. ACT-0162 completed the Room-only UI transfer on an empty synthetic profile. ACT-0165 proved the Room-only backup omits a normal UI-created Inbox note in `captures-prototype.bin`; active task and voice-memory stores are separate and not round-trip tested. ACT-0212 verified blank first run. ACT-0214 updated v78→v79 with `adb install -r` and retained the same `firstInstallTime`; the profile had no user records, so populated-data retention is still unproven. | Remains partial: versioned all-store bundle with key envelopes and recoverable all-or-nothing activation; test synthetic markers per store through UI export/delete/import/cold restart; process death/power-loss at durable phases; alternate providers/API levels; target-device KDF; physical owner acceptance; populated upgrade/restart and clean-install zero-state evidence beyond the initial empty-screen smoke test. See `docs/PORTABLE_BACKUP_SCOPE_GAP.md` and `docs/device-acceptance/ACT-0212/` / `ACT-0214/`. |
| M20-03 People/cases | Partial, current Room schema 21: owner-confirmed finance bridge, cases/evidence, person journey, scoped aliases, direct owner-confirmed task/person and calendar/person links with revision history, and person-story dedup by canonical source identity. ACT-0133/0137 verify task and finance sources linked through multiple cases appear once; ACT-0138/0141 verify task and calendar links can be added, removed and restored without name/label matching. ACT-0145 verifies exact PROJECT and `memory_revision` links and Compose source detail without copies. ACT-0146 extends the fake-provider case with owner-confirmed finance/domain person identity, 300 AZN obligation, one 100 AZN receipt allocated to it and 200 AZN outstanding; a synthetic Android 14 SQLCipher migration probe closes/reopens the encrypted DB and verifies all four canonical case sources and identity bridge survive without duplicate rows. ACT-0202 exact person-name/alias queries retrieve cases only through existing active same-scope participant links (bounded, source-backed, no writes). ACT-0208 adds explicit-link retrieval for a unique exact canonical PROJECT title through active same-scope domain-object case evidence; duplicate titles suppress traversal, without fuzzy resolution or link writes. No fuzzy merge. | Live natural-language Gemini/owner acceptance; broader identity bridge and BMW/Эльчин acceptance on the owner’s device remain open. Keep partial. |
| M20-04 Long-term memory | Partial, current Room schema 21: archive/retention, source-backed episodes, editable source-linked profile facts, Memory UI, and bounded consent-gated lexical cross-domain search across canonical finance, tasks, calendar, custom tables, cases, domain records, conversation, episodes and profile facts. ACT-0139 adds title/exact-phrase weighting; ACT-0143 invalidates derived episodes from superseded profile sources. ACT-0149/0152 cover exact AZ/RU/mixed and lexical negative paraphrase. ACT-0164: 96 synthetic queries against fully judged 30-task universe, 32/class (31 positive + 1 negative); positive Recall@5 `1.000/1.000/0.984`, fixed P@5 `0.206/0.206/0.194`, P@1 `0.969/0.969/0.906`, P@returned `0.409/0.790/0.386`, false-positive IDs `62/25/58`; each BMW/payment near-miss returned `4/3/4` false candidates. ACT-0168/0169 add exact ISO-date and bounded relative day word search across canonical finance/task/calendar activity, using profile-timezone normalization and adjacent-day exclusion. ACT-0200 adds exact embedded active person-alias resolution; ACT-0202 follows exact identities to explicit linked cases; ACT-0206 adds deterministic AZ/RU/EN ISO-week and explicitly qualified weekday filters for local dated finance/task/calendar retrieval (ADR-032/S70); ACT-0210 adds inclusive explicit ISO-date intervals through 366 days (ADR-034/S72). Full host suite 60 suites / 304 tests; lint 0/63/3. | Open: expand independent labels and intent categories (person/project/time), natural-language dates beyond bounded ISO intervals, paraphrases, inflections, near misses, realistic multi-source queries and scope distractors; define/accept k; measure p50/p95 latency and peak memory on target Android; then evaluate rights-cleared local semantic/hybrid retrieval. Persist future vectors only inside SQLCipher and invalidate/rebuild on source correction/deletion. Live Gemini selection, robust save/edit/delete under quota/partial failures, automatic episode continuation, broader correction propagation, app DB upgrade/readback, owner natural-query acceptance remain open. Current ranker remains lexical; old truncated turns cannot be restored. |
| M20-05 Premium experience | Screens include Today/Secretary/Cases/Memory/Reports and People/Projects/Notes; case/source navigation and action-result cards exist. ACT-0132 keeps failed text requests as drafts without auto-replay; ACT-0133 deduplicates canonical sources across cases; ACT-0134 localizes deletion failure and prevents model false success; ACT-0135 repeats warning on the action card; ACT-0136 reports a locally applied delete accurately if Gemini times out afterward; ACT-0140 verified the Cases empty-state route; ACT-0192 adds all-record local case search/status filters; ACT-0204 pages search and no-query/status results in stable 30-row chunks with newer/older controls. ACT-0212/ACT-0214 captured real Redmi screens; v79 fixes large-font AZ navigation label crowding. Landscape report source and following sections are reachable by scroll, confirmed on phone and in a phone-landscape UI regression. | Owner visual review, TalkBack/44–48dp target review, keyboard behavior, full people journey, real DB migration/restart, richer source/action summaries and explicit safe correction/undo acceptance remain open. ACT-0214 screenshots/regressions are review evidence, not owner acceptance; see `docs/device-acceptance/ACT-0214/`. |
| M20-06 Analytics/advice | Частично в v33 / schema 12: помесячный exact local ledger report, `analyze_finance_period`, `summarize_work_status`, status/count evidence по SQLCipher tasks/cases, person-scoped activity и coverage caveats. 250/250 JVM tests; fake-provider совместный finance/work read-only path проверен с scope isolation и без записей. | Требуется synthetic Gemini API routing, gift-context retrieval from seeded evidence и device acceptance; глубже связать категории/проекты только при наличии подтверждённых источников. Не выводить прибыль, ROI или периодную продуктивность из несуществующих полей. |
| M20-07 Proactivity | Частично реализован v34/schema 13: opt-in local attention из задач, календаря и открытых дел; quiet hours, snooze/dismiss, dedupe, reboot/time-change reconcile. v46 использует точный AlarmManager при выданном Android exact-alarm access, возвращает честный fallback `scheduled_inexact`, показывает отдельную настройку и перепланирует после выдачи доступа. | 5 целевых тестов и v46 install прошли; факт новой доставки владельцу и Xiaomi battery restrictions ещё открыты. На Redmi Android 13 package manager сообщает разрешённые POST_NOTIFICATIONS и SCHEDULE_EXACT_ALARM. Долги/pending questions не имеют источников. |
| M20-08 Native validation | ACT-0143: product APK v57 / versionCode 57 built; 58 suites / 292 tests, 0 failures/errors/skips; AndroidTest APK assembled, Kotlin compiled, lint 0 errors/60 warnings/3 hints. ACT-0141 synthetic SQLCipher migration v1→18 passed on Android14 and preserved sentinel. ACT-0212 checked the empty first-run path and five main tabs on Redmi. ACT-0214 updated v78→v79 without uninstall or clear; ACT-0218 installed v81 over v80 with unchanged `firstInstallTime`, then launched MainActivity. Physical owner testing found `stt_failed`; v81 changes STT to Firebase AI Logic-supported `gemini-3.8-flash`, but voice was not retested after this change. | Remains partial: the v78→v79 upgrade had an empty profile, so preservation of populated records is untested; no synthetic domain flows, reminder delivery, post-v81 live Gemini/text/audio and mixed RU/AZ acceptance, TalkBack, keyboard, actual populated DB migration/restart, owner daily use or full owner acceptance. Package manager reported RECORD_AUDIO granted after v81 update; no audio was captured during ACT-0218. Notification permission remains as previously set. See `docs/device-acceptance/ACT-0212/` and `ACT-0214/`. |
| M20-09 Future server | Design only after native acceptance. | Keep IDs/scope/contracts portable; do not add signup/sync before MEM-14.09 approval. |

Существующие этапы остаются применимыми: E0 — идея и основания (раздел 00), E1 — контракты (01–03), E2 — нативный фундамент (локальные части 04–06), E3 — первый сквозной результат (07–09), E4 — связанные домены (10–12), E5 — голос и надёжность (13–14), E6.A — весь нативный функционал / MEM-14.09, E6.B — сервер после принятия нативного этапа (MEM-14.10–14), E6.C — остальные клиенты, E7 — контролируемый выпуск (23–25), после выпуска — поддержка (26). M20 описывает целевой продукт и не закрывает прежние IDs автоматически.
## 00. Идея, границы и личные сценарии — E0

**Основание:** [VISION](docs/VISION.md), [SCENARIOS](docs/SCENARIOS.md). **Зависимости:** нет.

- [x] **MEM-00.01** Зафиксировать назначение, ценность и полное видение Memora. Свидетельство: [VISION](docs/VISION.md), [FULL_PRODUCT_SPEC](docs/FULL_PRODUCT_SPEC.md).
- [x] **MEM-00.02** Зафиксировать границы: управляемая память, источники, отсутствие скрытого прослушивания и самовольных внешних действий. Свидетельство: [README](README.md), [SECURITY_AND_PRIVACY](docs/SECURITY_AND_PRIVACY.md).
- [x] **MEM-00.03** Зафиксировать решения владельца: личные задачи, Android, команда из двух участников, отложенные цены. Свидетельство: [дневник](docs/AGENT_DIARY.md).
- [x] **MEM-00.04** Создать инженерный план и правила восстановления контекста. Свидетельство: [план](docs/ENGINEERING_PLAN.md), [AGENTS](AGENTS.md), коммит `05fca21`.
- [x] **MEM-00.05** Описать исходные и дополнительные сценарии S01–S37. Свидетельство: [SCENARIOS](docs/SCENARIOS.md); это описания, не пройденные тесты.
- [ ] **MEM-00.06** [Владелец] Добавить 10–15 обезличенных естественных фраз и вопросы, которые возникают вечером или через неделю.
- [ ] **MEM-00.07** [Совместно] Выбрать первые ежедневные сквозные сценарии и описать конкретную пользу/критерии успеха каждого.
- [x] **MEM-00.08** Составить реестр неизвестного: качество смешанной речи, неоднозначность действий, привычка использования, ограничения устройств и интеграций; для каждого определить способ проверки. Свидетельство: [реестр](docs/UNCERTAINTY_REGISTER.md); оценки и реальные испытания остаются открытыми.
- [ ] **MEM-00.09** [Владелец; до первой установки] Уточнить модель Android-устройства и версию ОС для первого реального испытания.

**Выход:** первые сценарии имеют ожидаемый результат и нерешённые вопросы; отсутствие личных примеров явно отмечено, а не заполнено выдуманными данными.

## 01. Архитектурные решения и контракты — E1

**Основание:** [план ADR](docs/ENGINEERING_PLAN.md), [архитектура](docs/TECHNICAL_ARCHITECTURE.md). **Зависимости:** документальная основа 00. До production-кода нужны применимые решения этого раздела, тестовые контракты и приёмка.

- [x] **MEM-01.01** Оформить ADR-001: нативный Android с локальными данными/логикой, границы модулей и адаптеров; последующий сервер и его альтернативы, контракты и переход после полной нативной приёмки. Свидетельство: [ADR-001](docs/adr/ADR-001-native-architecture.md); это проектное решение, не реализация.
- [x] **MEM-01.02** Оформить ADR-002: слои памяти, основания утверждений, версии, происхождение, конфликт и восстановление проекций. Свидетельство: [ADR-002](docs/adr/ADR-002-memory-provenance.md); проверки являются будущими.
- [x] **MEM-01.03** Оформить ADR-003: аккаунты, владение, личные/общие пространства, роли, отзыв доступа и офлайн-кэш. Свидетельство: [ADR-003](docs/adr/ADR-003-access-and-ownership.md); реализация и испытания предстоят.
- [x] **MEM-01.04** Оформить ADR-004: денежные типы, начальные остатки, движения, долги/погашения, округление, FX и корректировки. Свидетельство: [ADR-004](docs/adr/ADR-004-money-and-ledger.md); реализация и испытания предстоят.
- [x] **MEM-01.05** Оформить ADR-005: локальная очередь, версии, идемпотентность, конкуренция, частичные результаты, сверка внешних эффектов. Свидетельство: [ADR-005](docs/adr/ADR-005-commands-and-sync.md); реализация и испытания предстоят.
- [x] **MEM-01.06** Оформить ADR-006: хранение оригиналов/текста/фактов, экспорт, удаление зависимостей и восстановление без возвращения удалённых сведений. Свидетельство: [ADR-006](docs/adr/ADR-006-retention-and-deletion.md); реализация и испытания предстоят.
- [x] **MEM-01.07** Оформить ADR-007: схемы AI, разрешённые маршруты данных, смена модели, ограниченные повторы, отказ и оценка качества; без ценовых исследований. Свидетельство: [ADR-007](docs/adr/ADR-007-ai-routing.md); реализация и испытания предстоят.
- [x] **MEM-01.08** Оформить ADR-008: матрица риска, разрешения инструментов, подтверждение точной версии, отмена и связанные действия. Свидетельство: [ADR-008](docs/adr/ADR-008-ai-actions.md); реализация и испытания предстоят.
- [x] **MEM-01.09** Оформить ADR-009: время события/записи/получения, TZ/DST, неполные даты, повторения и опоздавшие напоминания. Свидетельство: [ADR-009](docs/adr/ADR-009-time-semantics.md); реализация и испытания предстоят.
- [ ] **MEM-01.10** Создать словарь сущностей, схемы данных, состояний команд и событий; определить ограничения и переходы на примерах. Частично: [первый путь](docs/DOMAIN_CONTRACT_DRAFT.md), [правила задач](docs/TASK_DOMAIN_CONTRACT.md); полные схемы открыты.
- [ ] **MEM-01.11** Описать версионируемые API и ошибки, права каждого действия, совместимость клиентов и правила частичного результата.
- [ ] **MEM-01.12** Создать таблицу трассировки S01–S37 и P01–P14 на ожидаемые события, состояние, UI, будущие тесты и задачи; составить полный реестр функций Android для MEM-14.09 и отдельно отметить серверные проверки, которые нельзя подтвердить локальной имитацией.
- [ ] **MEM-01.13** Провести согласованную проверку контрактов: исправление суммы/имени во время обработки, два устройства, зависимый платёж, удаление и повтор; записать принятые решения и открытое.

**Выход:** существенные правила определены; рекомендации имеют статус решений или открытых вопросов, а ближайшие сценарии допускают однозначную проверку.

## 02. Раннее исследование речи и критериев AI — E1 / начало E5

**Основание:** [AI_AND_VOICE](docs/AI_AND_VOICE.md), [QUALITY](docs/QUALITY_AND_ACCEPTANCE.md). **Зависимости:** 00; допустимо параллельно 01, как исследование, без заявлений о production-готовности.

- [x] **MEM-02.01** Определить правила получения, обезличивания, хранения и удаления исследовательских записей; не помещать личное аудио в публичный репозиторий. Свидетельство: [правила](docs/VOICE_RESEARCH_DATA_RULES.md); сбор личных записей и реализация хранилища не разрешены этим документом.
- [ ] **MEM-02.02** Собрать корпус RU/AZ/EN и смешанной речи: имена, суммы, отрицания, направление долга, даты, шум и технические термины.
- [ ] **MEM-02.03** Разметить эталоны и разделить настроечный/контрольный наборы с учётом говорящих и похожих записей; версионировать наборы.
- [ ] **MEM-02.04** Проверить актуальные технические возможности кандидатов STT/LLM/TTS, ограничения форматов и допустимость передачи данных. Частично: по прямому запросу от 2026-10-31 сопоставлены поддержка AZ/RU, задержки и публичные цены Gemini Transcribe/Scribe/Groq; [решение](docs/AZERBAIJANI_VOICE_PLAN.md). Прочие ценовые вопросы отложены, платные подключения не разрешены; фактическая сравнительная точность не измерена.
- [ ] **MEM-02.05** Провести воспроизводимый сравнительный эксперимент на разрешённых маршрутах; зафиксировать модели, настройки, задержки и характер критических ошибок.
- [ ] **MEM-02.06** Установить заранее проверяемые пороги для критичных полей, ошибочных автоматических действий, уточнений и покрытия; указать размеры выборок и неопределённость.
- [ ] **MEM-02.07** Выбрать обоснованный основной маршрут и поведение при отказе; определить, какие результаты требуют ручной проверки или запрещают автоматическое сохранение.

**Выход:** ограничения известны и измерены; выбор основан на результатах, а самооценка confidence не выдаётся за вероятность истины.

## 03. Пользовательские пути и дизайн — E1

**Основание:** [USER_EXPERIENCE](docs/USER_EXPERIENCE.md). **Зависимости:** первые сценарии 00, состояния/разрешения из 01; уточняется вместе с контрактами.

- [ ] **MEM-03.01** Нарисовать карту навигации Android: главная, инбокс, диалог, дела, финансы, люди/проекты, память и настройки.
- [ ] **MEM-03.02** Спроектировать первый запуск через запись и поиск; ввод существующих обязательств/остатков без вымышленной истории.
- [ ] **MEM-03.03** Спроектировать карточку результата каждого действия, очередь уточнений, частичное подтверждение и понятные статусы.
- [ ] **MEM-03.04** Спроектировать исправление с перечнем последствий, историей и границами Undo; проверить неоднозначное имя и сумму.
- [ ] **MEM-03.05** Подготовить состояния пустоты, загрузки, офлайн, ошибки, запрещённых разрешений и удалённого источника для первых экранов.
- [ ] **MEM-03.06** Определить визуальные токены, компоненты, светлую/тёмную темы, масштабирование текста, контраст и доступную навигацию.
- [ ] **MEM-03.07** Подготовить RU/AZ/EN-тексты, форматы сумм/дат и эквивалентный текстовый путь без обязательного голоса.
- [ ] **MEM-03.08** [Совместно] Пройти интерактивный прототип первых сценариев; зафиксировать непонятные места и исправить их до расширения экранов.
- [x] **MEM-03.09** Создать нативный UI-каркас Android: навигация, основные демонстрационные экраны, компоненты, светлая/тёмная темы и переключение RU/AZ/EN; явно отделить примеры от настоящих данных. Свидетельство: [исходники](apps/android/app/src/main/java/ai/memora/app), [дизайн и границы](docs/UI_SHELL.md), дневник 2026-10-29.
- [x] **MEM-03.10** Собрать устанавливаемый debug APK каркаса, закрепить Gradle wrapper/версии и инструкцию воспроизводимой сборки; целевой телефон владельца — Android 13, Redmi Note, 6 ГБ RAM, Helio G96. Свидетельство: Gradle assembleDebug, проверка APK-подписи, успешная установка и запуск на модели 2201117SG / API 33; [дневник](docs/AGENT_DIARY.md).
- [x] **MEM-03.11** Проверить UI-каркас автоматически: навигация/поиск/источник, черновик и восстановление UI, темы/языки, состояния без AI/микрофона; получить изображения экранов и проверить lint, небольшой экран/крупный текст и альбомную компоновку. Свидетельство: [UiShellTest.kt](apps/android/app/src/test/java/ai/memora/app/UiShellTest.kt), 9/9 на Robolectric API 33, 11 проверенных изображений, lint без ошибок; [дневник](docs/AGENT_DIARY.md).
- [ ] **MEM-03.12** [Совместно] Установить APK на реальный Redmi Note с Android 13, пройти экраны/клавиатуру/поворот/крупный шрифт/TalkBack, записать замечания и подтвердить дизайн. Симуляция не закрывает этот пункт. Частично: ACT-0212/v78 проверил основные вкладки; ACT-0214 установил v79 поверх v78, проверил основные экраны, крупный шрифт и landscape. Подписи меню теперь помещаются при 1.25×; landscape report прокручивается до источника и нижних разделов. Скриншоты в `docs/device-acceptance/ACT-0212/` и `ACT-0214/`. Клавиатура, TalkBack и совместное подтверждение владельца не выполнены.

**Выход:** можно пройти полный путь глазами пользователя, включая ошибку и исправление; формы и состояния согласованы с контрактами.

## 04. Репозиторий и технический фундамент — E2

**Зависимости:** решения 01; структура следует ADR, перечисленные технологии пока не считаются выбранными.

- [ ] **MEM-04.01** Создать структуру нативного Android-приложения, доменных модулей, локальных адаптеров, контрактов и тестов по ADR; серверные реализации — после MEM-14.09.
- [ ] **MEM-04.02** Зафиксировать версии инструментов/зависимостей, lock-файлы, правила обновлений и инструкцию запуска с чистого окружения.
- [ ] **MEM-04.03** Настроить форматирование, статические проверки и сборки; определить правила веток, ревью и интеграции изменений.
- [ ] **MEM-04.04** Настроить CI с подходящими автоматическими тестами, проверками секретов/зависимостей и сборочными артефактами.
- [ ] **MEM-04.05** Создать отдельные development/staging-конфигурации; секреты хранить вне кода, реальные данные не копировать в тестовую среду.
- [ ] **MEM-04.06** Подключить локальную БД Android, миграции и синтетические данные; проверить повторяемый запуск и изменение схемы без потери совместимости.
- [ ] **MEM-04.07** Подключить локальные файлы, долговечную очередь, фоновые обработчики и статусы заданий Android; проверить ограниченные повторы и неуспешные задания. Частично: прототип локального файла задач, [контракт](docs/TASK_STORAGE_CONTRACT.md); очередь и фоновые задания не реализованы.
- [ ] **MEM-04.08** Ввести идентификаторы операций, безопасные логи, трассировку, проверки состояния сервиса и технические ограничения нагрузки.
- [ ] **MEM-04.09** Подготовить воспроизводимую внутреннюю Android-сборку и установку на выбранное устройство; записать окружение.

**Выход:** другой чистый запуск воспроизводит окружение; CI и сборка работают; секретов и личных данных в репозитории нет.

## 05. Аккаунт, разрешения и безопасная основа — E2

**Зависимости:** 01, 04. MEM-05.01–05.06 дают основу для разработки 06–08 на синтетических данных. MEM-05.07–05.08 завершаются вместе с первым сквозным путём; это условие допуска реальных личных данных, а не блокировка начала синтетической разработки.

**Порядок выполнения:** в E2 сначала локальный профиль, защита данных/ключей и границы доступа в приложении по ADR. Регистрация на сервере, серверные сессии, удалённый отзыв и изоляция разных пользователей выполняются и принимаются в E6.B после MEM-14.09. До этого соответствующие пункты остаются открытыми с отдельным свидетельством локальной части; их серверная часть не является зависимостью первого нативного пути. MEM-05.08 проверяет допуск личных данных локальной сборки; при переносе допуск проверяется повторно для сервера.

- [ ] **MEM-05.01** Реализовать регистрацию/вход, выход, восстановление доступа и усиленную аутентификацию по выбранному контракту.
- [ ] **MEM-05.02** Реализовать серверные сессии, безопасное клиентское хранение токенов, их обновление и отзыв устройства.
- [ ] **MEM-05.03** Реализовать личное пространство и проверки доступа к объектам, запросам, файлам, кэшам и фоновым заданиям.
- [ ] **MEM-05.04** Написать негативные проверки изоляции двух пользователей, подмены идентификатора и доступа по старой ссылке.
- [ ] **MEM-05.05** Ввести аудит значимых изменений/доступа, ограничение запросов и безопасную обработку ошибок без раскрытия личного содержания.
- [ ] **MEM-05.06** Реализовать профиль: язык, валюта, timezone, страна, первый день недели и настройки устройств/разрешений. Частично: язык RU/AZ/EN и тема сохраняются локально с обработкой ошибок; остальные поля и приёмка профиля открыты.
- [ ] **MEM-05.07** Реализовать минимальные экспорт и удаление для первых типов записей, очистку локальной сессии и документированное хранение. Частично: экспорт и удаление тестовых задач и текстовых источников, ограниченный импорт источников, защита от повторного создания на текущей установке; очистка остальных типов, сессии и полная проверка устройства открыты.
- [ ] **MEM-05.08** Проверить модель угроз первых сценариев и допуск личных данных, включая локальную защиту MEM-06.09 и экспорт/удаление MEM-05.07; устранить критические дефекты до пилотного использования.

**Выход:** пользователь получает только разрешённые данные; отзыв, экспорт и удаление первых записей реально проверены.

## 06. Android: захват и инбокс — E2 / E3

**Зависимости:** 03–04, базовые MEM-05.01–05.06; офлайн-контракт 01. Разработка на синтетических данных не ждёт завершения MEM-05.07–05.08; реальные личные записи допускаются только после этих проверок.

- [ ] **MEM-06.01** Реализовать оболочку приложения, навигацию, темы и доступные компоненты первых экранов.
- [x] **MEM-06.02** Реализовать текстовый ввод с автосохранением черновика, явной отправкой и восстановлением после закрытия. Свидетельство: [контракт](docs/CAPTURE_STORAGE_CONTRACT.md), `CaptureUiIntegrationTest`, синтетическая проверка закрытия процесса и поворота в Android 14 emulator; личные данные, Redmi и восстановление при аварии питания не приняты.
- [ ] **MEM-06.03** Реализовать локальное долговечное хранение capture и очереди отправки с идентификатором операции, временем и timezone. Частично: шифрованный локальный прототип источников и команд; полноценная очередь и испытания устройства открыты.
- [ ] **MEM-06.04** Реализовать локальный приём записи и безопасный повтор через интерфейс capture; после MEM-14.09 добавить серверный адаптер и отдельный статус синхронизации. Успешное локальное сохранение до серверного этапа не требует ответа backend. Частично: интерфейс и синтетический локальный приём с повтором проверены на Robolectric; расширенная проверка устройства и контракта очереди открыта.
- [ ] **MEM-06.05** Реализовать инбокс и состояния обработки каждого источника/действия, повтор ошибки и открытие оригинала.
- [ ] **MEM-06.06** Добавить фильтры, поиск инбокса, архивирование и разрешённые массовые действия с понятными последствиями. Частично: поиск/фильтры и групповое архивирование тестовых текстовых источников с атомарной проверкой версий; остальные виды входящих и полная приёмка открыты.
- [x] **MEM-06.07** Реализовать настройки уведомлений/микрофона и текстовые альтернативы при отказе в разрешениях. Свидетельство: [проверки и эмулятор](docs/ACTION_LOG.md); системная доступность не гарантирует доставку, физический телефон не проверялся.
- [ ] **MEM-06.08** Проверить потерю сети, нехватку места и перезапуск до отправки: исходная запись не теряется и не объявляется сохранённой на сервере преждевременно.
- [ ] **MEM-06.09** До личного пилота реализовать защиту локального хранилища/ключей и правила системного backup по ADR; проверить отсутствие личного текста в диагностике и очистку при выходе/отзыве в пределах доступности устройства. Частично: шифрованный прототип и отключённый backup; допуск и реальные проверки телефона/очистки остаются открытыми.

**Выход:** запись переживает закрытие приложения, понятно отображает своё состояние и безопасно отправляется.

## 07. Ядро памяти, источники и исправления — E3

**Основание:** [MEMORY_AND_TRUST](docs/MEMORY_AND_TRUST.md). **Зависимости:** 01, 04, базовые MEM-05.01–05.06 и захват 06. Проверки MEM-05.07–05.08 доводятся на созданных здесь первых типах записей до личного пилота.

- [ ] **MEM-07.01** Реализовать исходники, версии транскрипта/текста, evidence spans и ссылки на точную версию источника. Частично: модель версий/диапазонов, история v5, импорт/экспорт, пользовательское исправление и рабочая память по сохранённым источникам. Голосовой текст Gemini можно проверить и явно сохранить как текстовый источник; аудиоисточник и постоянное хранилище evidence ещё не реализованы. Локальный поиск возвращает ссылку на точную текущую версию. Миграция v1–v4, история, отказы/повторы и UI проходят; проверки пакета 0.5 — ACT-0035. Полная приёмка памяти остаётся открытой. Подробности — [дневник](docs/AGENT_DIARY.md).
- [ ] **MEM-07.02** Реализовать кандидаты утверждений и отдельные признаки основания, подтверждения, актуальности и области доступа.
- [ ] **MEM-07.03** Реализовать минимальные сущности людей/объектов и кандидаты связей без автоматического слияния одноимённых людей.
- [ ] **MEM-07.04** Реализовать доменные команды, события, проверку версии, защиту от повторов и определённые группы связанных изменений.
- [ ] **MEM-07.05** Реализовать конфликт утверждений и уточнение; неполные и противоречивые сведения не попадают в достоверные агрегаты молча.
- [ ] **MEM-07.06** Реализовать предварительный список последствий исправления, корректирующие события и отказ от устаревшего результата обработки.
- [ ] **MEM-07.07** Реализовать пересчёт проекций без повторного AI-извлечения; проверить воспроизводимость состояния по событиям.
- [ ] **MEM-07.08** Реализовать просмотр истории, простое получение записи по человеку/дате и открытие разрешённого источника.
- [ ] **MEM-07.09** Проверить время действия фактов, режим «не запоминать» и управляемые предпочтения без скрытого профилирования.

**Выход:** можно объяснить происхождение и каждое изменение факта, исправить ошибку и воспроизвести состояние.

## 08. AI-разбор текста и подтверждения — E3

**Зависимости:** 01, разрешённый маршрут из 02, базовые MEM-05.01–05.06, 06–07. MEM-05.07–05.08 обязательны перед личными данными. При отсутствии разрешённого провайдера тестовые заглушки явно отделены от реальной приёмки.

- [ ] **MEM-08.01** Реализовать общий интерфейс AI-провайдера, безопасную конфигурацию нативного этапа по ADR-007 и версионирование модели/промптов; серверный AI gateway — после MEM-14.09. Частично: Firebase AI Logic, согласие и App Check; APK debug принимает Console debug token. ACT-0131 проверил synthetic text/function calls на Redmi и исправил 30-секундный внешний timeout; Gemini у друга после принятия токена всё ещё требует проверки на v47. Production-маршрут и полная оценка не готовы.
- [ ] **MEM-08.02** Реализовать классификацию запроса: поиск, расчёт, предложение изменения, исполнение разрешённой команды.
- [ ] **MEM-08.03** Реализовать извлечение нескольких типизированных действий с цитатами, людьми, суммами, валютами и временными выражениями.
- [ ] **MEM-08.04** Проверять схемы и доменные инварианты локальным доменным слоем Android; недопустимый ответ не изменяет состояние. После переноса сервер выполняет независимую проверку тех же контрактов.
- [ ] **MEM-08.05** Реализовать политику автоматического сохранения/подтверждения по риску и проверку точной версии разрешения.
- [ ] **MEM-08.06** Реализовать очередь уточнений и карточки отдельных действий; показать частичный успех без общего ложного «готово».
- [ ] **MEM-08.07** Реализовать выявление кандидатов смысловых дублей и связь нескольких источников одного события с контролем пользователя.
- [ ] **MEM-08.08** Реализовать ограниченные повторы, таймауты и понятное восстановление после отказа, сохраняя источник и состояние задания.
- [ ] **MEM-08.09** Проверить prompt injection, подмену суммы/источника, неоднозначность и регрессию текстовых сценариев на отдельном наборе.

**Выход:** модель предлагает, доменный слой проверяет; неопределённость не превращается в незаметное изменение данных. Сначала этот слой работает в Android, затем соответствующие обязанности переносятся на сервер.

## 09. Первый сквозной Android-результат — контроль E3

**Зависимости:** базовые права/сессии 05, механизмы 06–08 и UX-пути 03. Интеграция начинается на синтетических данных; к MEM-09.05 должны быть завершены MEM-05.07–05.08. Расширенные модули не блокируют эту техническую контрольную точку.

- [ ] **MEM-09.01** Соединить текстовый ввод, AI-предложения, подтверждение, память, источник и историю в одной рабочей сборке.
- [ ] **MEM-09.02** Реализовать базовый диалог: найти сохранённый факт, открыть источник и продолжить уточнение в контексте.
- [ ] **MEM-09.03** Пройти «сказал → сохранил → нашёл → исправил → снова спросил» и проверить согласованность UI с базой.
- [ ] **MEM-09.04** Пройти ошибку AI, повтор запроса, неоднозначное имя, потерю сети и отсутствие данных без выдуманного ответа.
- [ ] **MEM-09.05** [Совместно] Проверить сборку на устройстве владельца после допуска MEM-05.08; записать неудобства и блокирующие ошибки.
- [ ] **MEM-09.06** Устранить блокирующие дефекты первого пути и сохранить доказательства автоматических/реальных проверок отдельно.
- [ ] **MEM-09.07** Реализовать и проверить первый запуск «собственная запись → просмотр → исправление → поиск», контекстные настройки языка/валюты/TZ и повторный запуск без навязанного повторного онбординга.

**Выход:** первый законченный пользовательский путь работает на Android; это ещё не готовый полный продукт.

## 10. Деньги, долги и финансовые отчёты — E4

**Основание:** [DOMAIN_AND_DATA](docs/DOMAIN_AND_DATA.md), S01–S04/S09/S12/S16/S25–S31. **Зависимости:** 07–09, ADR-004. Пользовательский учёт денег не является отложенным ценообразованием самого сервиса.

- [ ] **MEM-10.01** Реализовать точное представление сумм/валют, округление и арифметические инварианты с генеративными тестами.
- [ ] **MEM-10.02** Реализовать счета/кошельки и начальные остатки с датой начала учёта без вымышленного дохода.
- [ ] **MEM-10.03** Реализовать доходы, расходы, категории/подкатегории, способы оплаты и связи с людьми/проектами/источниками.
- [ ] **MEM-10.04** Реализовать собственные переводы, выдачу/возврат основной суммы займа и возвраты покупок без искусственной выручки.
- [ ] **MEM-10.05** Реализовать обязательства: направление, стороны, первоначальная сумма, срок и история; начальный долг без вымышленной оплаты.
- [ ] **MEM-10.06** Реализовать частичное погашение, связанное движение счёта, переплату и корректировки с согласованными транзакциями.
- [ ] **MEM-10.07** Реализовать отмену/сторно и изменение первоначальной суммы с пересчётом остатков и отчётов.
- [ ] **MEM-10.08** Реализовать мультивалютность и подтверждённый курс с датой/источником; не складывать разные валюты без правила.
- [ ] **MEM-10.09** Реализовать плановые/повторяющиеся операции, графики погашения, категории бюджетов и лимиты пользователя.
- [ ] **MEM-10.10** Реализовать финансовые экраны, просрочки и сверку голосовых записей/чеков с уже учтёнными операциями.
- [ ] **MEM-10.11** Реализовать отчёты по периодам/проектам, прибыль/убыток по определённой модели, графики и экспорт CSV/PDF/табличного формата с охватом данных.
- [ ] **MEM-10.12** Проверить конкурентные погашения, повторы, отмены, границы округления и восстановление отчётов из принятых событий.

**Выход:** сумма объясняется исходными операциями; баланс, долг и отчёт согласованы даже после сбоя и исправления.

## 11. Задачи, обещания, календарь и напоминания — E4

**Зависимости:** 07–09, ADR-009; денежные напоминания подключаются после соответствующих операций 10.

- [ ] **MEM-11.01** Реализовать задачи/подзадачи, приоритеты, состояния, теги, вложения и связи с людьми/проектами. Частично: [модель задач](apps/android/app/src/main/java/ai/memora/app/domain/TaskDomain.kt), [тестовый список](docs/TASK_STORAGE_CONTRACT.md), добавление, изменение, удаление, срок и текстовые подписи; реальные связи, остальные поля и допуск личных данных открыты.
- [ ] **MEM-11.02** Реализовать обещания отдельно от задач: кто кому обещал, предмет, срок, источник и исполнение.
- [ ] **MEM-11.03** Реализовать события календаря с началом/концом, timezone, местом, участниками и обнаружением конфликтов.
- [ ] **MEM-11.04** Реализовать повторения и исключения отдельных вхождений; различать перенос одного события и всей серии.
- [ ] **MEM-11.05** Реализовать разбор относительного времени, неполных дат и уточнения; проверить позднюю загрузку, полуночь и DST. Частично: v45 принимает точный `remind_in_minutes` для задач и рассчитывает срок по часам устройства в Asia/Baku; unit tests покрывают полночь и неоднозначный DST. Разбор неполных дат и полный голосовой acceptance открыты.
- [ ] **MEM-11.06** Реализовать локальный планировщик Android без повторного эффекта одного задания; согласование с серверными напоминаниями добавить после MEM-14.09. Частично: v46 ставит точные alarm при выданном exact-alarm access; без доступа сообщает `scheduled_inexact`, сохраняет fallback и автоматически перепланирует после разрешения. Реальную доставку, закрытое приложение, перезагрузку и энергосбережение ещё нужно проверить на Redmi Android 13.
- [ ] **MEM-11.07** Реализовать каналы доставки и честные состояния планирования/отправки/известной доставки/прочтения/исполнения.
- [ ] **MEM-11.08** Реализовать snooze, выполнить, перенести, отменить, тихие часы и просроченное напоминание после офлайна.
- [ ] **MEM-11.09** Реализовать представления сегодня/неделя/месяц, приоритеты и просрочки с объяснением источников.
- [ ] **MEM-11.10** Проверить закрытое приложение, перезагрузку устройства, отказ в уведомлениях, смену timezone/устройства и отмену старой версии напоминания.

**Выход:** изменение задачи и доставка уведомления различаются; срок и состояние согласованы в локальном приложении, а после серверного перехода — также между клиентом и сервером.

## 12. Поиск, вопросы к памяти и диалог — E4 / E6

**Руководство реализации:** [AI_AGENT_IMPLEMENTATION](docs/AI_AGENT_IMPLEMENTATION.md), пакеты P0–P9 и сценарии AGT-01–AGT-38. Новые пункты ниже дополняют, а не закрывают прежние задачи памяти/финансов/календаря. Работающий голос подтверждён владельцем; полноценный агент пока спроектирован, не реализован.

**Зависимости:** базовый путь 09; точные финансовые/календарные запросы — после 10–11.

- [ ] **MEM-12.01** Реализовать структурные фильтры и детерминированные расчёты сумм/статусов/дат локально с соблюдением прав; серверную реализацию тех же контрактов — после MEM-14.09.
- [ ] **MEM-12.02** Реализовать полнотекстовый и семантический поиск, ранжирование и ограниченный контекст без смешивания областей доступа. Частично в 0.5: локальный буквальный поиск по текстовым источникам с NFC/AZ-регистром, RU/AZ запросами, архивным фильтром и без отправки архива AI; семантический индекс/ранжирование и остальные типы данных открыты.
- [ ] **MEM-12.03** Реализовать ответы со ссылками на точные источники, периодом, признаками конфликта и неполноты.
- [ ] **MEM-12.04** Реализовать многошаговый диалог, разрешение местоимений/контекста и переход от вопроса к предложению действия с проверкой прав.
- [ ] **MEM-12.05** Реализовать настройки языка, краткости, тона и озвучки ответа без вымышленных обещаний действий. Частично в 0.5: AZ — главный язык UI/инструкций, RU/EN сохраняются; Live VAD, проверка языка TTS без турецкой подмены, Gemini Transcribe Live с AZ/RU, защищённая правка письменности и просмотр исходника. 139 локальных тестов прошли, 3/3 синтетических маршрута завершились, но смешанные имена и задержка письменности не приняты; [отчёт](docs/VOICE_EVAL_2026-10-31.md). Естественность AZ, WER/CER живой речи, настройки тона и полноценная приёмка открыты; [план](docs/AZERBAIJANI_VOICE_PLAN.md), ACT-0035.
- [ ] **MEM-12.06** Реализовать «что ты обо мне помнишь», исправление/запрет предпочтений и ответы при удалённом источнике.
- [ ] **MEM-12.07** Проверить поиск по синтетической истории за месяцы, изменения имён/адресов, отсутствующие сведения, противоречия и удалённые факты.
- [ ] **MEM-12.08** Измерить релевантность, соответствие evidence, ложные ответы и отказоустойчивость при недоступности индекса/модели.
- [x] **MEM-12.09** Создать и проверить сквозное руководство реализации AI-помощника: архитектура, схемы, автономность, память, таблицы, миграция, этапы и приёмка. Свидетельство: [руководство](docs/AI_AGENT_IMPLEMENTATION.md), ACT-0039; проверены локальные ссылки, уникальность/сохранение ID, 38 сценариев, 10 пакетов и документационный diff. Закрыта только документация; реализация MEM-12.10–12.19 открыта.
- [x] **MEM-12.10** P0: закрепить ADR агента, схемы tool/command/result, policy matrix, cloud-context boundaries, retention/migration decisions и eval manifest. Свидетельство: [ADR-010](docs/adr/ADR-010-agent-contract.md), [ADR-011](docs/adr/ADR-011-storage-migration-candidate.md), [контрактный каталог](contracts/agent/v1/README.md) и [eval manifest](contracts/agent/v1/eval-manifest.json); 32/32 proposal schemas/vectors, 23/23 app-authored mutation command examples, typed results/binders, deny-by-default (32 runtime tools disabled), финансовые и табличные semantic invariants, решения владельца по retention/journal/diagnostics, inventory трёх legacy stores. Полный synthetic Python suite 66/66; 212 local Markdown links resolve; policy/schema completeness and `git diff --check` pass. Android verification remains the prior ACT-0050 run (182/182, debug builds and lint); Android code was not changed in this P0 package. Закрыты только контракты и решения: это не работающий agent, handlers, live model-quality evaluation, Android integration/device acceptance или миграция данных. Следующий этап — MEM-12.11 P1: защищённая БД и миграция только на synthetic/test data до прохождения device/backup/key gates.
- [ ] **MEM-12.11** P1: единая защищённая транзакционная БД, repositories, журнал команд/outbox и проверенная миграция трёх старых хранилищ без потери ID/источников/истории. Реализованы Room 2.8.5 + SQLCipher Android 4.20.0, schema v6, Keystore-wrapped 256-bit DB key, source-specific legacy coordinator, repositories, minimal command journal и metadata-only outbox. API 34/API 33 Redmi/API 26 synthetic probes проверяют Room 1→6; readers capture 1–5, tasks historical array/schema2, voice-money 1–2; атомарную миграцию всех stores с readback, replay, exact minor units и digest-bound retry. На Redmi/API26 проверены правильный/неверный ключ, отсутствующий wrapping key/envelope и corrupted key envelope и corrupted encrypted DB fail-closed без потери/перезаписи тестовой записи; closed SQLCipher DB без маркера. Synthetic force-stop посреди transaction на Redmi/API26 сохранил committed row и откатил uncommitted row; отдельный Redmi Android13 process force-stop после SQLCipher rekey и до envelope promotion восстановил через AgentDatabase startup staged key и synthetic marker (`rekey_recovered_after_process_force_stop=true`). Добавлен device probe: journal mode не WAL, WAL/SHM auxiliary-файлы отсутствуют или без контрольного маркера. `allowBackup=false`, cloud/device transfer exclusions подтверждены по merged release manifest; release APK/AAB собраны, APK 18 MiB/AAB 14,184,880 bytes, пакет содержит arm64-v8a/armeabi-v7a/x86/x86_64 и 12 SQLCipher .so (3 на ABI). Debug unit 199/199; Python contracts 71/71; debug/release сборки и lint прошли. **Дополнение ACT-0072:** реализованы staged key envelope, startup reconciliation и синтетический probe ротации/восстановления до и после SQLCipher rekey; `compileDebugKotlin` и `compileDebugAndroidTestKotlin`, `assembleDebug` и `assembleDebugAndroidTest` прошли. На физическом Redmi Android 13/API33 установлен и запущен APK; обновлённый synthetic storage probe прошёл, включая AgentDatabase startup recovery после rekey (`synthetic_key_rotation_recovered_and_verified=true`), 199/199 unit tests, debug/test APK сборки и lint. Старый instrumentation-only package был заменён; приложение и его данные не очищались. Gemini, микрофон и пользовательские данные не использовались. **P1 остаётся открытым:** hard-crash/power-loss/disk-fault recovery (process force-stop during transaction passed), uninstall/reinstall и operational restore, **Дополнение ACT-0076:** Google OSS Licenses plugin/SDK integrated into existing AZ/RU/EN screen; full SQLCipher notice retained. Current release runtime inventory is 176 artifacts /169 modules with complete [CSV POM/source map](docs/ANDROID_DEPENDENCY_LICENSE_MAP.csv) and [audit](docs/ANDROID_DEPENDENCY_LICENSE_AUDIT.md); Gradle generated OSS notice metadata/resources in debug and release APKs, `aapt2` confirms both resource entries. Full unit 199/199, debug/release/Android-test builds and lint pass; UiShellTest clicks viewer path; updated debug APK installed on Redmi. **ACT-0077:** fresh `:app:assembleDebug` passed; `adb install -r` returned `Success`; package versionCode 8/versionName 0.6.2-debt-voice launched into `MainActivity` on the connected Android 13 Redmi. User data was not cleared. Synthetic Redmi instrumentation also passed staged-envelope write failure/recovery: stage failure refused before rekey, old key/DB survived, promotion failure preserved staged key, startup recovered marker (`status=passed`). **P1 остаётся открытым:** manual copyright/NOTICE and Google vendor-term review, signed release, power-loss/disk-fault/uninstall/restore acceptance.  Пользовательские workflows не подключены, legacy cutover запрещён. Свидетельства ACT-0061–0076, [ADR-011](docs/adr/ADR-011-storage-migration-candidate.md), [Room schema v6](apps/android/app/schemas/ai.memora.app.data.protectedstore.AgentDatabase/6.json), [repository](apps/android/app/src/main/java/ai/memora/app/data/protectedstore/ProtectedMemoryRepository.kt), [domain foundation](apps/android/app/src/main/java/ai/memora/app/data/protectedstore/ProtectedDomainRepository.kt), [synthetic probe](apps/android/app/src/androidTest/java/ai/memora/app/data/protectedstore/DatabaseStorageProbe.kt).
- [x] **MEM-12.12** P2: изолированный исполнитель многошаговых поручений: TurnController с bounded concurrency, Orchestrator с 8 model rounds/16 tool calls/таймаутами, deny-by-default registry, policy recheck перед каждым model send/tool/commit, read-only и write handlers, статусы clarification/confirmation/partial, app-authored command IDs, idempotent CAS journal и recovery без слепого повтора; fake model, UI/cloud не подключены. Свидетельства: [runtime](apps/android/app/src/main/java/ai/memora/app/agent/AgentRuntime.kt), [Room journal](apps/android/app/src/main/java/ai/memora/app/data/protectedstore/RoomAgentRunJournal.kt), [tests](apps/android/app/src/test/java/ai/memora/app/agent/AgentRuntimeTest.kt), ACT-0063. Проверено: 198/198 Android unit tests, assembleDebug + assembleDebugAndroidTest + lintDebug, Python P0 contracts 66/66, offline API 34 arm64 instrumentation Room 1→3 и command/turn recovery across DB reopen. Все реальные инструменты выключены; это не сквозная функция приложения и не P1 physical acceptance. P1 device/key/backup/crash gates остаются открытыми и блокируют подключение agent-runtime и перенос пользовательских файлов.
- [ ] **MEM-12.13** P3: инструменты людей/проектов/заметок/связей, версии, preview/delete/restore/Undo и единый путь команд UI/агента. Agent runtime подключает scoped create/edit/link, reversible delete/restore, SQLCipher conversation history и ACT-0084 lexical evidence search. ACT-0085 добавил локальный архивный поиск по сохранённым сообщениям с временным фильтром и ограниченными источниками; Gemini получает совпавшие фрагменты только при consent. ACT-0171 проверил поиск по всему архиву из 520 синтетических сообщений; ACT-0176 добавил полную пользовательскую постраничную навигацию по сохранённым разговорам, без model-context лимита и без отправки архива Gemini. Repository test прошёл 165 совпадений за несколькими страницами, включая старейшее; Compose test загрузил вторую страницу с синтетическими данными. Полный Gradle: 60 suites / 310 tests, 0 failures/errors/skips; debug/AndroidTest builds и Kotlin compile прошли; lint 0 errors / 72 warnings / 3 hints. APK v61 / 1.1.39-memory-archive-pages, SHA-256 b28373f68dd4140cdbed5c97261d56bf2cfa572c00e5c4c7ff3765cd430c5f7e. Устройство не подключено; P3 остаётся открытым. ACT-0178 дополнительно заменил per-turn SQL чтение на пакетное чтение сообщений и защитил экран от смешения in-flight результатов старого поиска с новым. Повторная полная проверка: 60 suites / 310 tests, lint 0/72/3; APK v62 `1.1.40-memory-archive-robustness`, SHA-256 3533b9ddbe6f5cd7f9bfa7b45cdd51d63cd9fbf0f30638c95c57030727579903. Архив по умолчанию хранится бессрочно и поддерживает выборочное ограничение срока. ACT-0127 добавил явные scoped aliases с revisioned lifecycle и owner-confirmed finance bridge. Открыты редактируемый дневник памяти, cross-domain canonical linking/disambiguation, broader source-span/confidence semantics beyond profile excerpts, оценка полноты model top-k, idempotency recovery proof и broad field acceptance; MEM-12.13 остаётся открытым. ACT-0182 добавил владельцу ручное создание профильных фактов с provenance `user_entered`, без синтетического sourceTurnId, category identity и проверкой дубля. Полный Gradle 63/321, lint 0/73/3, APK v64 SHA-256 `b3c817d02e65211b34866d9e1563ab34a504156d6522e4bc966ef4d1784b8526`. Physical/TalkBack acceptance, весь P3 и owner acceptance остаются открыты.
  ACT-0091: added a visible Notes screen for the protected NOTE objects written by Gemini tools; local search and detail view share the People/Projects UI, localized AZ/RU/EN. APK v21 build/install succeeded over existing data. Field acceptance remains open; the complete P3 scope and MEM-12.13 are not closed.
  ACT-0089: added real People and Projects screens backed by the same encrypted Room/SQLCipher store used by Gemini, with local search and detail view in AZ/RU/EN. APK v20 build/install succeeded. Field acceptance remains open; P3 also needs aliases/disambiguation, memory diary UX, source/revision management, and dependent-safe restore/undo.
- [ ] **MEM-12.14** P4: пользовательские typed tables, строки/схемы, linked views, UI, экспорт/импорт, точные агрегаты и доступность. ACT-0084: typed schema validation and row/table CRUD with recoverable deletion exist. ACT-0170 adds PERSON_REF/PROJECT_REF columns with same-scope active target validation. ACT-0172 adds read-only active PERSON/PROJECT/NOTE views, additive Room 18→19 viewSpec, live canonical reads and fail-closed writes/export. ACT-0173 added filter/sort/page UI, then ACT-0174 removed the 500-source truncation: linked views now scan all active sources in SQL chunks within one read transaction, filter with Unicode case-insensitivity, locale-sort a bounded result window, and report full source/match counts. UI pages 25 rows; agent read_table_rows supports exact bounded offsets and limits. Offsets are capped at 100,000, with continuation exhaustion disclosed. Synthetic test covers 601 active sources, archived/deleted exclusions, AZ case-insensitive search, ascending/descending pages and agent full-source read. S55 records the contract. Full Gradle: 60 suites / 308 tests, 0 failures/errors/skips; debug/AndroidTest APKs and Kotlin compile pass; lint 0 errors / 72 warnings / 3 hints. APK v60 1.1.38-linked-view-search, SHA-256 704edf0d9a3317246e11411b05bcdc4d428d29fdc11f8bd334cd389e66023997. No device/owner acceptance. ACT-0180 добавил typed JSON SAF transfer и точные per-currency aggregates: 63/318 tests, lint 0/74/3, APK v63 SHA-256 `15ac1d3492684ba841d9fe71590f3e69f8034434828931b2c19952dbb76e0ddd`. Остались полный backup round-trip, SAF provider/device, TalkBack, production-scale performance и owner acceptance; MEM-12.14 stays open. ACT-0184 adds owner-facing optional-field preview, schema history and confirmed restore/redo as a new append-only revision; hidden row JSON values survive edits and redo. Full Gradle 63/324, lint 0/73/3, APK v65 SHA-256 `c63e99cda02c55f9c416de85a15b95284260a0c9001589c0b0391728f0da71b8`; S59/ADR-021. Physical device, TalkBack, full backup, SAF and owner acceptance remain open.
- [ ] **MEM-12.15** P5: общий структурный/текстовый и оценённый семантический поиск, evidence, ограниченный контекст, многоходовые уточнения и verified answers. ACT-0084 добавил lexical token/prefix retrieval по protected memory; ACT-0085 добавил tool search по всему сохранённому SQLCipher conversation archive через SQL-пейджи, с query/time filtering и ограниченными snippets; 240 сообщений ограничивает recent/context API, а не архив. Открыты оценка поиска на AZ/RU/mixed, полный source-span/provenance, pagination/completeness, durable clarification continuation, confidence/retrieval thresholds и verified claim gate.
- [ ] **MEM-12.16** P6: финансовые инструменты, задачи/напоминания и календарь. ACT-0095: SQLCipher device probe подтвердил annotation, одну receipt/repayment link, correction и точный остаток после reopen, same-name conflict без записи и миграции. ACT-0129 добавил относительное время задачи и название задачи в уведомление; ACT-0130 добавил точный AlarmManager и status-aware fallback. Фактическая доставка владельцу, finance event restore, aliases, calendar reliability и outbox/fault acceptance открыты.
- [ ] **MEM-12.17** P7: Gemini tools, общий voice/text runtime, consent, result cards/TTS без ложного успеха. ACT-0095: annotate_money_event, typed person conflict clarification, empty-model failure без вымышленного подтверждения, bounded finance evidence. Реальные Gemini AZ/RU mutate и restart search прошли; revoked consent остановил вызов до модели/инструментов. v26 установлен; полный unit suite 219/219, lintDebug и обе APK сборки прошли. Открыты field acceptance, confirmation/undo UX, recovery, text TTS/cards и полноценный verified-answer gate.
- [ ] **MEM-12.18** P8: проверить отказоустойчивость, отзыв прав, удаление/restore, миграции, безопасную диагностику и нагрузки всего агента. Зависимость: P1–P7; критические сценарии руководства без открытых блокеров.
- [ ] **MEM-12.19** P9: провести независимый holdout AZ/RU/mixed, измерения задержек, регрессию на Android и ежедневную приёмку владельцем по разделу 16 руководства. Зависимость: P0–P8; не приравнивать приёмку ядра к MEM-14.09. Подготовлен только privacy-minimal агрегатор `contracts/agent/v1/evaluation_metrics.py` с coverage, языковыми/аудио срезами, критическими ошибками и median/p95; 5 synthetic tests проходят. Реальный holdout и model run отсутствуют; P1–P8 и недельная приёмка владельца открыты. Поэтому P9 остаётся незакрытым.
- [ ] **MEM-12.20** P1/M20-02: полная переносимая копия Room/SQLCipher, Inbox/captures, task events, voice-memory до подтверждённого завершения миграции, stable scope/actor IDs и language/theme settings; typed versioned manifest, isolated validation, snapshot barrier и atomic multi-store cutover/recovery. ACT-0165 доказал пропуск Inbox note текущим Room-only backup. ACT-0166 завершил read-only inventory и зафиксировал device-bound exclusions. ACT-0167 обнаружил синтетический file→Room мигратор, не подключённый к UI и работающий только на пустой БД; user migration/cutover не проверены, поэтому scope остаётся прежним. См. [ADR-013](docs/adr/ADR-013-portable-backup-all-stores.md), [inventory](docs/PORTABLE_BACKUP_STORE_INVENTORY.md), [scope gap](docs/PORTABLE_BACKUP_SCOPE_GAP.md). Следом — выбор/реализация безопасной синхронизации источников, populated-user upgrade, archive round-trip и failure/device acceptance.

**Выход:** ответы воспроизводимы по разрешённым данным; «не найдено» не выдаётся за «не происходило».

## 13. Полноценный голосовой путь — E5

**Зависимости:** 02, 06–09; можно реализовывать параллельно 10–12 на уже принятых контрактах.

- [ ] **MEM-13.01** Реализовать запись с индикатором, паузой, возобновлением, прослушиванием, отменой и явной отправкой. Частично: [тестовый голосовой ввод](docs/VOICE_INPUT_PROTOTYPE.md) с явным запуском и отменой; аудиофайл, пауза и прослушивание не реализованы.
- [ ] **MEM-13.02** Реализовать безопасную загрузку аудио, проверку формата/длительности, обработку частичного файла и сохранность при прерывании.
- [ ] **MEM-13.03** Подключить STT с сегментами/версией, ручным выбором языка, смешанной речью и статусами обработки. Частично: системный on-device API для языка интерфейса; локальные модели и качество на устройстве не проверены.
- [ ] **MEM-13.04** Реализовать редактирование стенограммы, языка, говорящего и классификации с безопасным переразбором и последствиями. Частично: распознанный текст открывается как редактируемый черновик; версии и переразбор не реализованы.
- [ ] **MEM-13.05** Реализовать персональный словарь имён, произношений, проектов и терминов; пользователь может просмотреть, изменить и удалить записи.
- [ ] **MEM-13.06** Соединить голос с тем же разбором/подтверждением/памятью, что и текст; не создавать параллельные несовместимые правила.
- [ ] **MEM-13.07** Реализовать опциональную озвучку ответа: запуск/остановка, язык/голос, текстовый эквивалент, поведение при отсутствии нужного TTS. Частично: кнопки запуска/остановки системного TTS и предупреждение о возможной сети; языки и фактическое звучание не проверены.
- [ ] **MEM-13.08** [Совместно] Реализовать и проверить синхронный облачный live-диалог. После сообщения владельца `stt_failed` основной hold-to-talk использует Firebase AI Logic SDK `generateContent` с поддерживаемым audio model `gemini-3.8-flash`, вместо custom WebSocket модели `gemini-3.5-transcribe-live`, не входящей в Firebase AI Logic supported-model list. Ошибка теперь показывает безопасный код этапа/категории. APK v81 установлен на Redmi; после смены маршрута владелец ещё не подтверждал реальную AZ-транскрипцию, ответ агента и речь. Нужен физический синтетический тест с явным in-app consent; код/сборка не закрывают acceptance. См. ACT-0218.
- [ ] **MEM-13.09** Пройти независимый сквозной корпус RU/AZ/EN/code-switching до конечного состояния и реальное Android-аудио; подтвердить пороги раздела 02.

**Выход:** голос и текст дают согласованные доменные результаты; критические ошибки и ограничения измерены, а не скрыты гладкой стенограммой.

## 14. Мобильная устойчивость, приёмка и серверный переход — E5 / E6.A / E6.B

**Зависимости:** базовая очередь 06, версии/команды 07–08, голос 13 и проверяемые домены 10–11.

В E5 проверяется локальная сохранность и мобильная устойчивость; межустройственные MEM-14.01–MEM-14.02 и серверные части остальных проверок выполняются в E6.B. Контрольная точка MEM-14.09 зависит от всего нативного функционала, включая Android-части 15–18/21 и ежедневную приёмку 23, а не только от первого пути 09. Она не требует уже работающего backend. Требования, реализуемые только на серверной инфраструктуре, имеют отдельные незакрытые проверки в реестре MEM-01.12; это не разрешение вычеркнуть функцию или выдать её имитацию за реализацию.

- [ ] **MEM-14.01** Реализовать протокол дельта-синхронизации, версии объектов, удаления и видимость незавершённых изменений по ADR.
- [ ] **MEM-14.02** Реализовать доменное разрешение конфликтов двух устройств; деньги не используют безусловное «последний победил».
- [ ] **MEM-14.03** Проверить очередь при убийстве процесса, перезагрузке, потере места, обрыве загрузки и смене сети; исходники и операция не дублируются.
- [ ] **MEM-14.04** Проверить поздние результаты после исправления/удаления/отзыва сессии и безопасный повтор после сбоя обработчика.
- [ ] **MEM-14.05** Расширить проверку локальной защиты из MEM-06.09: восстановление системной копии, перенос на новое устройство, недоступный ключ и безопасное поведение старой сессии.
- [ ] **MEM-14.06** Проверить обновление приложения с непустой очередью и совместимость старой сборки с новым API/схемой.
- [ ] **MEM-14.07** На основе устройства владельца из MEM-00.09 подготовить матрицу дополнительного слабого устройства и поддерживаемых версий.
- [ ] **MEM-14.08** Измерить холодный запуск, память, батарею, длительную запись и фоновую работу на выбранной матрице; закрыть блокирующие ограничения.
- [ ] **MEM-14.09** [Совместно; до реализации собственного backend] Протестировать весь функционал нативного Android-приложения по полному реестру MEM-01.12, зафиксировать сборку, результаты на устройстве, ежедневное использование и ограничения; устранить блокирующие дефекты и получить явное подтверждение владельца о завершении нативного этапа.
- [ ] **MEM-14.10** После MEM-14.09 подготовить конкретный план серверной адаптации: границы переноса, API, схема миграции локальной базы/файлов, совместимость, контрольные сравнения и возврат к рабочей копии.
- [ ] **MEM-14.11** По MEM-14.10 реализовать серверную инфраструктуру, БД/миграции, файлы, фоновые задания, аккаунты и разрешения; создать серверную основу из 04–05/24, её сквозные проверки завершить после подключения Android.
- [ ] **MEM-14.12** Адаптировать нативный Android к API, серверным доменным командам, AI-обработке и синхронизации; сохранить локальную очередь и реализовать серверные части 06–13/18/21 и MEM-14.01–MEM-14.06. Перенос данных и итоговая приёмка выполняются отдельно в MEM-14.13/MEM-14.14.
- [ ] **MEM-14.13** Перенести существующие локальные данные и файлы с сохранением ID, источников, связей, версий, истории, денег и времени; проверить прерывание/повтор миграции, сверку и восстановление рабочей копии без повторных эффектов.
- [ ] **MEM-14.14** [Совместно] Пройти регрессию всего подтверждённого функционала после переноса, реальные серверные проверки доступа/синхронизации и сбои; зафиксировать доказательства и подтвердить завершение серверной адаптации отдельно от нативной приёмки.

**Выход:** прерывание работы не теряет принятые данные, а конфликт не маскируется успехом. Объём гарантий офлайна документирован.

## 15. Люди, проекты, объекты и хронология — E6

**Зависимости:** базовые сущности 07, связанные домены 10–12; можно расширять вместе с ними.

- [ ] **MEM-15.01** Реализовать карточки людей, псевдонимы, разрешённые контакты, контекст знакомства и связанную историю.
- [ ] **MEM-15.02** Реализовать подтверждённое объединение и разделение людей с распределением связей без потери/дублирования денег.
- [ ] **MEM-15.03** Реализовать личные/рабочие проекты, этапы, участников, задачи, финансы, документы и решения.
- [ ] **MEM-15.04** Реализовать темы, места и объекты: заказы, имущество, транспорт и настраиваемые категории.
- [ ] **MEM-15.05** Реализовать общую хронологию и переход к исходникам, периодам действия фактов и истории исправлений.
- [ ] **MEM-15.06** Реализовать визуальный граф связей с полноценной доступной списковой альтернативой.
- [ ] **MEM-15.07** Проверить архивацию/удаление проекта, смену связей, неоднозначного человека и пересчёт зависимых ответов/отчётов.

**Выход:** пользователь восстанавливает контекст человека или проекта через связанные записи и источники.

## 16. Документы, фото, OCR и встречи — E6

**Зависимости:** безопасный захват 04–06, источники/предложения 07–08; финансы 10, голос 13 и проекты 15 для связанных сценариев.

- [ ] **MEM-16.01** Реализовать выбор/Share нескольких фото, сканов, PDF и поддерживаемых документов с явными лимитами и ошибками.
- [ ] **MEM-16.02** Реализовать карантин/проверку вложений, безопасную выдачу файлов и защиту обработчиков от вредоносных/повреждённых материалов.
- [ ] **MEM-16.03** Реализовать OCR текста/таблиц и чеков с позициями, суммой, валютой, продавцом и датой; сохранять точный источник.
- [ ] **MEM-16.04** Реализовать предложения расходов и сверку чека с уже записанной покупкой, без молчаливого двойного учёта.
- [ ] **MEM-16.05** Реализовать извлечение сроков/обязательств из документов с подтверждением и без выдачи юридического заключения.
- [ ] **MEM-16.06** Реализовать разрешённую запись встреч, участников/говорящих, итоги, решения и follow-up; проверить согласия и исправления атрибуции.
- [ ] **MEM-16.07** Реализовать библиотеку исходников, поиск вложений и экспорт; проверить удаление файла и распространение изменений на память.

**Выход:** пользователь проверяет каждое предложенное значение по исходнику; импорт не получает полномочий выполнять инструкции внутри файла.

## 17. Проактивный секретарь — E6

**Основание:** [PROACTIVE_ASSISTANT](docs/PROACTIVE_ASSISTANT.md). **Зависимости:** актуальная память 07/12, деньги/задачи 10–11, настройки доступа 05.

- [ ] **MEM-17.01** Реализовать включение/паузу/выключение обзоров, выбор каналов, пространств, категорий и языка.
- [ ] **MEM-17.02** Реализовать утренние, вечерние и недельные сводки, приоритеты и обзоры по запросу с evidence и охватом данных.
- [ ] **MEM-17.03** Реализовать фиксированное/местное расписание, предварительный показ запусков, DST, тихие часы и ограничение повторов.
- [ ] **MEM-17.04** Реализовать нейтральное превью экрана блокировки и проверку прав перед доставкой/открытием.
- [ ] **MEM-17.05** Реализовать «полезно», «неверно», «уже сделал», «позже», «не показывать» с отдельными последствиями для подсказки и доменного объекта.
- [ ] **MEM-17.06** Реализовать инвалидирование сводок/ожидающих уведомлений после исправления, удаления и отзыва доступа.
- [ ] **MEM-17.07** [Совместно] Проверить пользу и ненавязчивость на ежедневных задачах; испытать пустой период, устаревшие данные и пропущенный запуск.
- [ ] **MEM-17.08** Реализовать подсказки по повторным упоминаниям без отметки результата: показывать источники, различать неизвестное исполнение и невыполненное, ограничивать повторы и учитывать явную обратную связь.

**Выход:** сводки помогают ориентироваться, сохраняя границу между записями, предположениями и фактами исполнения.

## 18. Внешние интеграции — E6

**Зависимости:** 01, 05, 07–08 и соответствующий домен; для каждого коннектора отдельно проверяется актуальная документация и доступность.

- [ ] **MEM-18.01** Определить конкретные поддерживаемые календарь, почту и хранилище; проверить API, разрешения и технические ограничения без ценовых исследований.
- [ ] **MEM-18.02** Реализовать общую основу коннекторов, безопасное хранение OAuth-токенов, раздельные scope чтения/записи и повторную авторизацию.
- [ ] **MEM-18.03** Реализовать календарный импорт/сверку и разрешённые изменения/приглашения с проверкой конфликтов и подтверждения.
- [ ] **MEM-18.04** Реализовать разрешённый импорт почты и файлов, происхождение и исключение повторного импорта.
- [ ] **MEM-18.05** Реализовать сверку после неизвестного результата внешнего действия; повтор не отправляет второе приглашение или изменение.
- [ ] **MEM-18.06** Реализовать отключение, отзыв доступа и выбранное удаление импортированного с очисткой зависимостей.
- [ ] **MEM-18.07** Проверить каждый коннектор на настоящем тестовом аккаунте: истечение токена, ограничения запросов, смена прав, отказ и восстановление. Моки отмечать отдельно.

**Выход:** каждое внешнее изменение объяснимо и разрешено. Скрытый доступ к чатам и самовольные переводы денег не становятся частью интеграций.

## 19. Семейные и рабочие пространства — E6

**Зависимости:** модель доступа 01/05, проекты 15 и домены, которые будут разделяться.

Реальная многопользовательская работа относится к E6.B после MEM-14.09 и появления серверной основы MEM-14.11. Её доменные правила проектируются заранее; локальная модель или имитация не закрывает задачи серверного доступа.

- [ ] **MEM-19.01** Реализовать создание пространств, явные приглашения, принятие/отклонение, истечение и отзыв приглашения.
- [ ] **MEM-19.02** Реализовать роли читать/писать/управлять, владение и определённые правила ухода/передачи управления.
- [ ] **MEM-19.03** Реализовать общие проекты, задачи и бюджеты с явной областью видимости каждого объекта.
- [ ] **MEM-19.04** Реализовать перенос/публикацию личной записи в пространство только с показом последствий и соответствующим разрешением.
- [ ] **MEM-19.05** Исключить раскрытие личной памяти через общие источники, связи, поиск, экспорт, подсказки и контекст AI.
- [ ] **MEM-19.06** Реализовать отзыв участия во время обработки/поиска/экспорта; показать ограничения очистки отключённого устройства.
- [ ] **MEM-19.07** Проверить конкурентные изменения и весь жизненный цикл членства несколькими аккаунтами, включая удаление и восстановление.

**Выход:** общая работа не делает частные сведения общими автоматически; доступ воспроизводимо проверен во всех слоях.

## 20. iOS, веб и общее качество клиентов — E6

**Зависимости:** завершённая серверная адаптация MEM-14.14 и стабильные серверные контракты; этап E6.C. Первый путь 09 сам по себе не разрешает начинать этот этап. Остальные клиенты не объявляются готовыми по общему backend.

- [ ] **MEM-20.01** Определить матрицу возможностей клиентов, поддерживаемые платформы и обоснованные различия; до начала MEM-20.02/MEM-20.03/MEM-20.05 разбить клиентские пути и их приёмку на отдельные дочерние задачи с постоянными ID.
- [ ] **MEM-20.02** Реализовать iOS-клиент: аккаунт, захват, инбокс, диалог, память и исправления по общим контрактам.
- [ ] **MEM-20.03** Реализовать iOS-экраны денег/дел/проектов/документов/сводок/настроек и доступ к согласованным интеграциям/пространствам.
- [ ] **MEM-20.04** Проверить iOS Share, микрофон, уведомления, хранение, офлайн/синхронизацию и обновление на реальном устройстве.
- [ ] **MEM-20.05** Реализовать адаптивную веб-панель с полными согласованными пользовательскими путями и отображением источников/истории.
- [ ] **MEM-20.06** Проверить веб-разрешения, несколько вкладок, устаревшие версии данных, клавиатуру, фокус и поведение без сети.
- [ ] **MEM-20.07** Проверить одинаковые балансы/сроки/источники, исправления и отзыв доступа между Android, iOS и вебом.
- [ ] **MEM-20.08** Завершить RU/AZ/EN во всех строках, ошибках и форматах; проверить длинные тексты и смену языка без изменения фактов.
- [ ] **MEM-20.09** Провести проверку TalkBack/VoiceOver/веб-доступности, крупного текста, контраста, reduced motion и доступных графиков/таблиц.
- [ ] **MEM-20.10** Пройти согласованный набор сценариев на каждой поддерживаемой платформе и исправить различия, нарушающие доменную семантику.

**Выход:** каждая платформа имеет собственные свидетельства приёмки; экран-каркас не считается завершённым клиентом.

## 21. Полный жизненный цикл данных и приватность — E6 / E7

**Основание:** [SECURITY](docs/SECURITY_AND_PRIVACY.md). **Зависимости:** базовые механизмы 05; расширяется при добавлении каждого нового типа данных, а не откладывается целиком до конца.

- [ ] **MEM-21.01** Реализовать настройки хранения аудио/текста/документов отдельно и понятное объяснение судьбы связанных фактов.
- [ ] **MEM-21.02** Реализовать просмотр и изменение того, что память хранит о пользователе; отдельный выбор «не сохранять».
- [ ] **MEM-21.03** Реализовать полный переносимый экспорт исходников, фактов, связей, истории и настроек в документированных форматах.
- [ ] **MEM-21.04** Открыть экспорт вне Memora и проверить, что история понятна и доступна без приложения; не ограничиваться фактом создания архива.
- [ ] **MEM-21.05** Реализовать удаление записи/источника/пространства/аккаунта с прогрессом, сроками и проверкой производных данных/очередей.
- [ ] **MEM-21.06** Проверить удаление из индексов, сводок, кэшей и вложений; отдельно проверить восстановление старой копии с сохранением удаления и отзыва прав.
- [ ] **MEM-21.07** Зафиксировать реальные потоки данных к провайдерам, разрешённые регионы/маршруты и пользовательское раскрытие; проверить соответствие конфигурации.
- [ ] **MEM-21.08** Подготовить правила доступа поддержки, обезличенную диагностику и обработку запросов на доступ/экспорт/удаление без стандартного доступа к личной памяти.

**Выход:** пользователь управляет всей цепочкой своих данных, включая производные; заявленные сроки и ограничения проверены.

## 22. Коммерческие функции — сохранены, сейчас отложены

**Статус всех пунктов:** **ОТЛОЖЕНО до возвращения владельца к коммерческим/ценовым решениям.** Не начинать исследования цен, бюджетов или тарифов по этому списку. Технические ограничения нагрузки и сохранность данных продолжают разрабатываться в других разделах. Отсрочка не является разрешением расходов.

- [ ] **MEM-22.01** [Владелец; отложено] Возобновить обсуждение модели доступа/подписки и зафиксировать необходимые коммерческие решения отдельным документом.
- [ ] **MEM-22.02** [Отложено] Реализовать серверные права подписки и подтверждение покупок по принятым решениям и актуальным требованиям площадок.
- [ ] **MEM-22.03** [Отложено] Реализовать восстановление покупок, отмену, льготные периоды, возвраты и идемпотентные события подписки.
- [ ] **MEM-22.04** [Отложено] Реализовать понятные лимиты/счётчики обработки и поведение при их достижении без удаления ранее сохранённых данных.
- [ ] **MEM-22.05** [Отложено] Проверить покупки и состояния подписки в тестовых средах всех выбранных каналов; согласовать готовность коммерческого выпуска.

**Выход:** только после возобновления этой темы владельцем. Технический production и завершённый коммерческий продукт учитываются раздельно; отложенные функции нельзя считать готовыми или молча вычеркнуть.

## 23. Личное использование и ограниченный пилот — E3 / E5 / E7

**Зависимости:** личное использование начинается после 05 и 09, голос — после 13–14. Расширение аудитории требует сохранности и изоляции используемого объёма. Полный E6 не блокирует раннюю проверку, но объём пилота обозначается явно.

До сервера эта зависимость охватывает локальную безопасность 05 и мобильные проверки MEM-14.03–MEM-14.08 в применимой части; ежедневная нативная проверка нужна для MEM-14.09 и не зависит от её завершения или от серверной миграции. После переноса безопасность и пилот серверной версии проверяются заново.

- [ ] **MEM-23.01** Зафиксировать уровни допуска: синтетические данные → личные данные владельца → похожие пользователи → production; определить функции и блокирующие дефекты каждого уровня.
- [ ] **MEM-23.02** [Совместно] Начать ежедневную проверку владельцем с конкретными сценариями и условиями; результаты хранить без личных исходников.
- [ ] **MEM-23.03** Собирать ошибки, число уточнений, усилия на исправление, полезность поиска/сводок и повторное использование; отсутствие жалобы не считать точностью.
- [ ] **MEM-23.04** Исправлять причины найденных проблем и добавлять обезличенные случаи в регрессионный набор без загрязнения закрытой оценки.
- [ ] **MEM-23.05** Проверить восстановление до использования Memora как единственного места хранения важных сведений; объяснить текущие ограничения.
- [ ] **MEM-23.06** [Совместно] Подготовить приглашение похожих пользователей, согласие на тестирование/записи, канал обратной связи и правила удаления тестовых данных.
- [ ] **MEM-23.07** Провести пилот на иных привычках, именах, голосах и устройствах; проверить переносимость результата и закрыть блокирующие дефекты.
- [ ] **MEM-23.08** Составить отчёт по пилоту: пройденные сценарии, известные ограничения, подтверждённые качества и решение о следующем уровне допуска.

**Выход:** есть реальные наблюдения, а не только демонстрация разработчика; расширение аудитории опирается на проверенный объём.

## 24. Эксплуатационная готовность и staging — E2 / E7

**Основание:** [OPERATIONS](docs/OPERATIONS_AND_RELIABILITY.md), [QUALITY](docs/QUALITY_AND_ACCEPTANCE.md). **Зависимости:** основы 04–05 создаются рано; проверки расширяются с продуктом и обязательны перед соответствующим production-выпуском.

В E2 выполняется применимая локальная часть: сборка, миграции, безопасная диагностика и восстановление данных Android. Развёртывание собственного сервера, серверные SLO, очереди и restore drill относятся к E6.B/E7 после MEM-14.09.

- [ ] **MEM-24.01** Настроить воспроизводимое развёртывание staging/production с разделением секретов, данных, файлов, уведомлений и AI-маршрутов; непроверенный CI-код не получает production-ключи.
- [ ] **MEM-24.02** Определить измеримые SLO, RPO/RTO, сроки удаления, поддерживаемую нагрузку и последствия отказов; заменить TBD проверяемыми условиями.
- [ ] **MEM-24.03** Настроить резервирование БД, файлов и необходимых ключей/конфигурации; восстановить их в изолированной среде и измерить результат.
- [ ] **MEM-24.04** Провести полный restore drill: применить удаления/отзывы до открытия доступа, пересобрать индексы без AI-переизвлечения и повторного исполнения действий.
- [ ] **MEM-24.05** Проверить миграции со старой схемы/клиента и на реалистичном объёме; определить безопасный откат или исправление вперёд для каждой миграции.
- [ ] **MEM-24.06** Проверить нагрузку, рост очереди, ограничение запросов, отказ БД/хранилища/провайдера и восстановление без потери принятых операций.
- [ ] **MEM-24.07** Настроить мониторинг ошибок/очередей/напоминаний/AI и реальные оповещения с назначенным получателем; проверить срабатывание и инструкции.
- [ ] **MEM-24.08** Реализовать feature flags и аварийное отключение маршрута AI/интеграции; проверить возврат версии кода/промпта/конфигурации без отката пользовательских фактов.
- [ ] **MEM-24.09** Подготовить runbook для потери записи, ошибки денег, недоставки уведомления, утечки ключа, отказа провайдера и восстановления сервиса.
- [ ] **MEM-24.10** Провести итоговую проверку безопасности, включая независимую оценку перед широким запуском; закрыть критические дефекты и зафиксировать область/ограничения проверки.
- [ ] **MEM-24.11** Организовать контакт поддержки/безопасности, сообщение о сбоях, журнал инцидентов и реальный порядок реагирования с учётом команды из двух участников.

**Выход:** есть проверенные восстановление, наблюдаемость и процедура реакции. Не обещать круглосуточную поддержку или гарантии, для которых нет работающего процесса.

## 25. Подготовка и управляемый выпуск в production — E7

**Зависимости:** пройденная приёмка включаемых функций, 21, 23–24. Поэтапный Android-выпуск допустим при явно указанном объёме; завершение полного продукта требует всех соответствующих модулей/клиентов и отдельного решения по 22.

- [ ] **MEM-25.01** [Совместно] Определить точный объём выпуска, платформы, аудиторию и страны; незавершённое явно отключить и не рекламировать как доступное.
- [ ] **MEM-25.02** Проверить рабочее название/права на материалы и лицензии зависимостей; подготовить применимые пользовательские условия, политику приватности и правовую проверку целевых рынков.
- [ ] **MEM-25.03** Подготовить принадлежащие владельцу аккаунты публикации, идентичность приложения/домена и доступы; эти задачи не выполняются автоматически при создании списка.
- [ ] **MEM-25.04** Настроить release signing, ограниченный доступ к ключам и их восстановление; сохранить воспроизводимые версионированные артефакты.
- [ ] **MEM-25.05** Проверить подписанный релиз: чистую установку, обновление, повторный вход, очередь, локальные миграции и отсутствие debug-конфигурации/тестовых секретов.
- [ ] **MEM-25.06** Сверить актуальные требования магазинов и декларации данных/разрешений с реальным поведением; подготовить описания, скриншоты, справку и материалы выпуска по платформам.
- [ ] **MEM-25.07** Собрать пакет доказательств: версия кода/модели, сценарии/устройства, языковые метрики, нагрузка, безопасность, экспорт/удаление и восстановление; закрыть блокирующие дефекты.
- [ ] **MEM-25.08** [Совместно] Принять конкретное решение о публикации подготовленной версии с условиями остановки/отката; создание TODO не означает разрешения публикации.
- [ ] **MEM-25.09** Выполнить поэтапное развёртывание и публикацию, smoke-проверки реальных пользовательских путей и наблюдение; при нарушении критериев остановить расширение.
- [ ] **MEM-25.10** Подтвердить стабильность выпуска за заранее определённый период, записать фактические ограничения и обновить документацию/дневник/чек-лист.

**Выход:** пользователям доступна проверенная версия с понятной областью возможностей, поддержкой и механизмом остановки. «Опубликовано» и «стабильно в эксплуатации» фиксируются отдельно.

## 26. После запуска — постоянный цикл

**Зависимости:** первый production-выпуск 25. Галочки ниже означают создание и проверку процессов; каждый следующий запуск процесса получает дату/результат в журнале, а не считается выполненным навсегда.

- [ ] **MEM-26.01** Настроить регулярный обзор ошибок, доставки, качества AI и обратной связи по версиям/языкам с обезличенной диагностикой.
- [ ] **MEM-26.02** Установить процесс инцидентов: стабилизировать, исправить причину, объяснить последствия и добавить регрессионный сценарий.
- [ ] **MEM-26.03** Установить и впервые выполнить цикл проверки восстановления, удаления, экспорта и отзыва доступа; сохранять результаты повторов.
- [ ] **MEM-26.04** Установить обновление зависимостей/сертификатов/ключей/API и безопасный вывод старых клиентов без внезапной потери данных.
- [ ] **MEM-26.05** Установить приёмку смены моделей/промптов/провайдеров: закрытый набор, проверка людей, ограниченный выпуск и возврат версии.
- [ ] **MEM-26.06** Регулярно сверять реальную пользу и ограничения с полной концепцией; добавлять задачи с устойчивыми ID без незаметного сокращения продукта.
- [ ] **MEM-26.07** После завершения всех включённых этапов провести отдельную приёмку полного продукта; явно перечислить оставшиеся/отложенные функции, включая раздел 22.

**Выход:** изменения и сбои обрабатываются воспроизводимо; дневник, требования и этот список отражают реальность.

## Карта покрытия полного видения

Это карта областей, не готовая таблица тестов. Подробная трассировка каждого требования/сценария — отдельная незавершённая задача MEM-01.12.

| Требования полной спецификации | Задачи/разделы |
| --- | --- |
| 1.1 Инбокс | 06, 08, 13, 16 |
| 1.2 Разговор с секретарём | 08–09, 12–13 |
| 1.3 Деньги и обязательства | 10, 11, 15–16 |
| 1.4 Задачи, обещания, события | 11, 17–18 |
| 1.5 Люди и проекты | 07, 15, 19 |
| 1.6 Долговременная память | 07, 12, 14, 21 |
| 1.7 Проактивность | 17 |
| 1.8 Мультимодальность | 13, 16 |
| 1.9 Интеграции | 18; системный Share — 16 и 20 |
| 1.10 Аккаунт, настройки, подписка | 05, 13–14, 21; подписка — отложенный 22 |
| 1.11 Совместная работа | 19 |
| Android / iOS / веб, RU/AZ/EN, доступность | 03, 06, 13–14, 20 |
| Источники, исправления, неизвестность, подтверждения | 01, 07–09, 12, 21 |
| Сохранность, безопасность и эксплуатация | 04–05, 14, 21, 24–26 |
| P01–P14 из инженерного плана | 03, 06–08, 10–14, 17, 21 |
| S01–S37 и регрессии | MEM-01.12; доменные разделы, 23–25 |

## Свидетельство завершения пункта

Для документальной задачи достаточно ссылки на проверенный документ и записи об актуальности. Для реализации запись должна содержать ID задачи, версию кода/модели, результат, условия проверки (автоматика / staging / реальное устройство / ежедневное использование), ограничения и ссылки на артефакты. Если проверка выявила ошибку, оставить галочку открытой либо снять прежнюю с объяснением в дневнике.

Отдельные документы доказательств создаются по мере работы; не добавлять фиктивные отчёты ради закрытия списка. Коммерческие решения, независимая внешняя проверка и публикация остаются открытыми до фактического выполнения, даже если код готов.

**Продолжение MEM-12.11 (ACT-0071):** SQLCipher Community copyright и полный BSD-style license доступны из «О приложении» → «Лицензии открытых компонентов»; синтетический UI test 1/1, debug APK и lint прошли. Это выполняет SQLCipher attribution, но не закрывает полный license audit или остальные P1 recovery gates.

### Current P3 work package

- [x] **ACT-0186:** locally verify optional profile-fact source quotes; persist nullable exact UTF-16 offsets through additive Room 19→20 migration; display exact excerpt in Memory UI; cover Unicode, non-matching quotes, revisions and provider contract. Full Gradle 63/325, lint 0/73/3; APK v66 SHA-256 `b918987cbf98b6389360f5c825db2936a6fe8bb234e1481dab842103df0682c5`. Host synthetic only; Android migration probe compiled but was not device-run. This is a source-location slice, not confidence scoring or P3 closure. See ADR-022 and S60.
- [x] **ACT-0188:** exact optional case-revision source spans; additive Room 20→21 migration; portable backup exports current schema21 and retains Room18-only legacy import/migrate-forward compatibility. Full Gradle 63 suites / 326 tests, 0 failures/errors/skips; debug and AndroidTest builds/Kotlin compile passed; lint 0/73/3. APK v67 SHA-256 `9954707dfbbf0b3d747718f1379cfab224d6b811849cd090cfc1ee1f82c3eabb`; generated schema21 confirms nullable offsets. Host synthetic only; opt-in Android migration and legacy backup import probe compiled but was not executed; device/TalkBack/owner acceptance not claimed. See ADR-023/S61 and diary. This does not close full P3/P4 or portable all-store backup.

### ACT-0190 — verified M20-04 retrieval slice

- [x] **ACT-0190:** `search_memory` can retrieve cases by wording in their three newest locally anchored revision excerpts and returns up to three verified excerpts with revision/source-turn IDs; invalid spans are omitted. No migration. Full Gradle passed: 63 suites / 326 tests, 0 failures/errors/skips; debug/AndroidTest builds and Kotlin compile passed; lint 0/73/3. APK v68 / `1.1.46-case-source-retrieval`, SHA-256 `a1b2b698bd8c94b1b69893de7762b8670b4341f8aa93cfe92d393bc0171c62f2`. Host synthetic only; ADB empty, no device/TalkBack/live Gemini/owner acceptance. See ADR-024/S62 and diary. M20-04/P3 remains open.

### ACT-0192 — M20-05 Cases screen search/filter slice

- [x] **ACT-0192:** Cases screen search covers all non-deleted same-scope records through 100-row pages in a consistent read transaction; returns up to 30 ranked hits and supports status-filtered queries. AZ/RU/EN copy, empty/clear states, and Compose plus >100-case regressions are included. Full Gradle passed: 63 suites / 328 tests, 0 failures/errors/skips; debug/AndroidTest builds and Kotlin compile passed; lint 0/73/3. APK v69 / `1.1.47-case-search-ui`, SHA-256 `c0cea8fe9234a633c76c2f95bae7cfc2f90236c1369559444463aefae1e763fc`. No schema change. Synthetic only; no device/screenshot/TalkBack/owner acceptance. See ADR-025/S63; M20-05 remains partial.

### ACT-0194 — M20-03 person-linked case history slice

- [x] **ACT-0194:** Person detail now queries same-scope active cases through explicit participant links, independently of unrelated recent cases. Shows eight by default and localized expand/collapse up to 100 linked cases. Synthetic repository regression proves an older linked case beyond 100 newer unrelated cases is found and foreign-scope records are excluded; Compose regression verifies expanding a nine-case list reveals the ninth older case. Full Gradle passed: 63 suites / 329 tests, 0 failures/errors/skips; debug/AndroidTest builds and Kotlin compile passed; lint 0/73/3. APK v70 / `1.1.48-person-case-story`, SHA-256 `4579ec698f10928135ed3dbe8051ad58670da99a57a60cd46254bb05ac376398`. No schema migration. ADB empty; no physical device, visual/TalkBack or owner acceptance. See ADR-026/S64; M20-03 remains partial.

### ACT-0196 — M20-05 complete loaded person timeline

- [x] **ACT-0196:** Person story shows the newest 40 events first and provides localized, repeatable batches of 40 older events until all loaded entries are visible; collapse returns to the newest 40. Synthetic Compose regression uses 50 linked cases and verifies an older case revision appears after expansion. Full Gradle: 63 suites / 329 tests, 0 failures/errors/skips; debug/AndroidTest APK builds and AndroidTest Kotlin compilation passed; lint 0/73/3. APK v71 / `1.1.49-person-history-pages`, SHA-256 `6c04e21787f7978dbe7e63d8fbaa68bd5c32dd57288972334b0bb671b78269d0`. No schema migration. Host synthetic only; ADB empty, no device/TalkBack/owner acceptance. M20-05 remains partial. See ADR-027/S65.

### ACT-0198 — M20-03 exact task-label suggestions

- [x] **ACT-0198:** Person task picker promotes tasks whose stored person label exactly matches the selected PERSON title or an active owner-managed alias. These remain visibly unconfirmed suggestions, require the existing explicit owner confirmation to persist `task_person_link`, and do not hide other tasks. Synthetic Compose test uses a normalized exact name plus an unrelated task, verifies both sections, confirms no link exists before user confirmation, then exercises the existing confirmation/link flow. Full Gradle: 63 suites / 329 tests, 0 failures/errors/skips; debug/AndroidTest APK builds and AndroidTest Kotlin compilation passed; lint 0/73/3. APK v72 / `1.1.50-person-task-link-suggestions`, SHA-256 `9a02e9fbde5da7b6815dd2c8bfd75cceede669353c1eeac57d40d260d059b251`. No schema change. ADB empty; device/TalkBack/owner acceptance open. See ADR-028/S66; M20-03 remains partial.

## ACT-0200 — Verified: exact person aliases inside natural memory queries

- [x] `search_memory` detects active owner-managed aliases as complete contiguous Unicode letter/number phrases inside a query, with case/whitespace normalization and punctuation boundaries. Returned identity evidence includes canonical person ID/name, alias revision and source turn.
- [x] Synthetic host test verifies an embedded alias phrase with punctuation and excludes retired, foreign-scope and canonical-name-colliding aliases.
- [x] Full Gradle: 63 suites / 330 tests, 0 failures/errors/skips; debug and AndroidTest builds, AndroidTest Kotlin compile; lint 0 errors / 73 warnings / 3 hints. APK v73 / `1.1.51-memory-alias-search`, SHA-256 `4a58bd16f39aff121c6816c32ede901a940c48fcea66a7ab80f81b84cb7f2bfb`.
- [x] No migration or inferred relationship. ADB empty; no physical-device/TalkBack/owner acceptance. Broad retrieval quality remains open. ADR-029/S67.

## ACT-0202 — Verified: exact person queries retrieve explicitly linked cases

- [x] A canonical person name or active exact alias inside a query supplies IDs for bounded lookup of same-scope active cases connected through existing `case_participant` rows; case wording need not repeat the person's name.
- [x] Output labels the evidence with `explicit_person_case_link` and matched canonical IDs, preserves the case snapshot/source evidence and deduplicates case IDs. At most three exact people are followed; at most 100 recent candidates/person and 20 linked case snapshots/query; `activity_truncated` reports possible partial coverage.
- [x] Synthetic regression verifies an unrelatedly titled case is retrieved through the exact alias participant link, while unrelated and foreign-scope cases remain excluded. No relationship is inferred or written.
- [x] Full Gradle: 63 suites / 331 tests, 0 failures/errors/skips; debug and AndroidTest builds, AndroidTest Kotlin compile; lint 0 errors / 73 warnings / 3 hints. APK v74 / `1.1.52-person-case-search`, SHA-256 `65ab7129553133cb0cc5ef7282a8c8b892cb3675e12e2a75c4471ab94460c8ae`.
- [x] No migration. ADB empty; no device/TalkBack/owner acceptance. Broad M20-03/04 retrieval, Gemini live and release acceptance remain open. ADR-030/S68.


## ACT-0204 — Verified: Cases search and lists paginate

- [x] Repository provides deterministic ranked-search pages with stable case ID tie-break and offset; no-query/status lists use same-scope SQL LIMIT/OFFSET. Query and status mutation reset to the first page. Search display is bounded to 1,050 records plus a sentinel; plain list has a 99,990 maximum starting offset; limit reached is disclosed.
- [x] UI renders only one page of 30 records, exposes older/newer controls, and applies pagination to text search, status filters and the default list. No rows are copied or changed by browsing.
- [x] Synthetic repository regression pages through 67 matching records with no duplicates and deterministic ordering; Compose regression reaches a match beyond the first 30 and returns to the newer page. Existing source-search, filters and cross-domain search regressions also pass.
- [x] Full Gradle: 63 suites / 333 tests, 0 failures/errors/skips; debug and AndroidTest builds, AndroidTest Kotlin compile; lint 0 errors / 73 warnings / 3 hints. APK v75 / `1.1.53-cases-pages`, SHA-256 `39251e14c8ca38a267d963f245a75dc8ebb506ebacb8ed622712baf6170ca72f`.
- [x] No migration. ADB empty; no device/TalkBack/owner acceptance or performance benchmark. M20-05 remains partial. ADR-031/S69.


## ACT-0206 — Verified: AZ/RU/EN weekday and ISO-week memory search

- [x] Local `search_memory` resolves explicit previous/current/next ISO week (Monday–Sunday) and explicitly qualified previous/current-week/next weekday phrases; profile timezone anchors the dates. Bare weekday stays ambiguous.
- [x] Date-only temporal searches retrieve local same-scope dated finance, task and calendar activity, exclude adjacent ranges and do not call Gemini or write records. Existing ISO-date and relative-day search remain.
- [x] Synthetic fixed-clock integration covers last/current week in AZ/RU/EN, previous Friday in AZ/RU/EN, ordinary question wording, next-week parser boundaries, neighboring-day exclusion and foreign-scope isolation. ADR-032/S70.
- [x] Full Gradle `:app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:compileDebugAndroidTestKotlin :app:lintDebug --no-daemon --console=plain --max-workers=1` with `JAVA_TOOL_OPTIONS=-XX:TieredStopAtLevel=1`: 63 suites / 334 tests, 0 failures/errors/skips; debug and AndroidTest APK builds, AndroidTest Kotlin compile; lint 0 errors / 73 warnings / 3 hints. APK v76 / `1.1.54-memory-week-search`, SHA-256 `b7f28653670e1608416b1e46243047e57f8cd6154a9317bc149491438da24416`.
- [x] No schema migration. Implementation commit `4e9617e638c374165d83896c442ee7c6d3b6054b` is pushed to `origin/main` (ACT-0207). ADB empty; no device/TalkBack/owner acceptance or latency/resource benchmark. This is a bounded phrase set, not general date understanding; natural-language date expressions beyond bounded ISO intervals and M20-04 remain open.


## ACT-0208 — Verified: exact project-linked case retrieval

- [x] Resolve only one unique exact active same-scope PROJECT title; follow existing active `domain_object` case evidence links. Suppress traversal on duplicate titles or a full bounded candidate window.
- [x] Preserve case/project provenance and report matched project IDs; bound lookup candidates and disclose possible truncation; exclude foreign/deleted/unlinked rows.
- [x] Synthetic AgentRuntime integration verifies retrieval beyond lexical case text, duplicate-title ambiguity suppression and foreign/unlinked exclusions; no mutation/model request/schema migration. ADR-033/S71.
- [x] Full Gradle passed: 63 suites / 336 tests, 0 failures/errors/skips; debug and AndroidTest APK builds, AndroidTest Kotlin compile; lint 0 errors / 73 warnings / 3 hints. APK v77 / `1.1.55-project-case-search`, SHA-256 `cd0d6c0d3228cb9edcb0237426cf21e112747a242ab3fb9a4dca2e2960ff8fcc`. No device/TalkBack/owner acceptance or performance benchmark; M20-03/04 and release readiness remain partial. See ADR-033/S71; implementation and handoff in ACT-0209.


## ACT-0210 — Verified: bounded explicit ISO date-range retrieval

- [x] Parse exact inclusive date ranges in AZ/RU/EN and ISO separator forms; cap at 366 days and reject invalid/reversed/oversized ranges without endpoint-only fallback.
- [x] Apply the local date filter consistently to canonical finance, tasks and calendar activity, retaining profile-timezone semantics and read-only/same-scope behavior.
- [x] Synthetic fixed-clock test proves both inclusive boundaries and an interior date, excludes adjacent days, covers all supported syntax, and rejects malformed, reversed and oversized ranges. ADR-034/S72.
- [x] Full Gradle: 63 suites / 337 tests, 0 failures/errors/skips; debug/AndroidTest APKs and AndroidTest Kotlin compile passed; lint 0 errors / 73 warnings / 3 hints. APK v78 / `1.1.56-memory-date-ranges`, SHA-256 `8a8906fff096068bd1354e9fca6ce045cab1dc65d46d4f026b1574de56f1e135`.
- [x] No schema migration or model/network call. ADB empty; device/TalkBack/owner acceptance and latency/resource benchmark remain open. M20-04 and release readiness remain partial; implementation/docs and Git handoff recorded in ACT-0211.

### ACT-0212 — Physical Redmi acceptance evidence (2026-11-05)

- [x] v78 installs and launches on Redmi 2201117SG / Android 13; empty first-run path and Today/Secretary/Tasks/Memory/Reports render. No crash observed; no personal record entered. Screenshots: `docs/device-acceptance/ACT-0212/`.
- [x] Landscape and 1.25× system-font observations recorded; system orientation/font settings restored to their pre-check values.
- [x] ACT-0214 fixed the large-font AZ navigation crowding; device capture shows the five labels fit at 1.25×. Landscape report is vertically scrollable; phone and Compose checks reach the report source and lower sections, so the earlier apparent clipping is a below-the-fold scroll state, not an inaccessible overlap.
- [ ] Owner/TalkBack/keyboard and real workflows remain required; mic/notification permission stayed denied. This slice does not close MEM-03.12, M20-02/03/04/05/08 or MEM-14.09.

### ACT-0214 — Responsive navigation and v79 device verification (2026-11-05)

- [x] Bottom navigation labels center/wrap; when system fontScale >1.15, base 9.5sp retains system scaling. AZ narrow-phone UI regression and screenshot show all five labels visible at 1.25×.
- [x] Reports scroll reachability verified in phone landscape on device and by `w800dp-h360dp-land` UI regression; report source and lower sections can be reached. No custom landscape redesign needed.
- [x] APK v79 installed over v78 via `adb install -r`; package version and unchanged `firstInstallTime` confirmed, app launched, settings restored. Since profile was empty, populated-data retention remains open.
- [ ] MEM-03.12 / M20-05 owner visual, keyboard, 44–48dp and TalkBack acceptance remains open; screenshots are evidence, not owner approval.
- [ ] M20-02/03/04/08 and MEM-14.09 remain open for populated DB migration/restart, full-domain and daily-use checks, live AZ/RU voice, reminders and native owner acceptance.
