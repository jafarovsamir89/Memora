# Instructions for future agents working on Memora AI

## Обязательный вход в работу: прочитай дневник

**Перед началом каждой новой сессии, после восстановления/сжатия контекста и перед сменой этапа прочитай этот файл и [дневник проекта](docs/AGENT_DIARY.md).** Затем проверь фактическое состояние файлов и Git. Дневник — опора для восстановления контекста, но записи о выполнении нужно сверять с репозиторием и результатами проверок.

Минимальная последовательность:

1. Прочитать `AGENTS.md` и `docs/AGENT_DIARY.md`: идея, решения пользователя, текущий этап, завершённое, открытые вопросы, следующий шаг.
2. Проверить `git status`, текущую ветку и последние изменения; не перезаписывать чужую незавершённую работу.
3. Прочитать `README.md` и актуальный план `docs/ENGINEERING_PLAN.md`. Для конкретной задачи открыть соответствующие продуктовые, доменные и проверочные документы.
4. Сформулировать для себя цель задачи, её связь с этапом и проверяемый результат. Если задача не относится к Memora или меняет границы продукта — прояснить намерение пользователя.
5. Перед отчётом обновить дневник: фактически сделанное, проверки и их ограничения, решения, незавершённое и точный следующий шаг.

## Краткая память проекта

- Memora — независимый персональный AI-секретарь с проверяемой долговременной памятью: голос/текст, люди, проекты, финансы/долги, задачи, календарь и проактивные обзоры; языки RU/AZ/EN.
- Полное видение сохраняется. Этапы определяют порядок реализации, а не удаляют модули из продукта.
- Пользователь решил: сначала проверять на его повседневных задачах, затем на похожих пользователях; первый рабочий клиент **Android**; основная команда — пользователь и AI-ассистент как ведущий инженер.
- **Ценовые вопросы полностью отложены пользователем.** Не инициировать обсуждение бюджетов, тарифов, цен провайдеров и экономики до его возвращения к теме. Существующие коммерческие документы сохраняются как контекст полного видения. Это не разрешение тратить средства или подключать платные услуги.
- Достоверный текущий этап находится в `docs/AGENT_DIARY.md`, порядок работ — в `docs/ENGINEERING_PLAN.md`. Не выводить готовность из наличия спецификации.
- Android-first не означает отказ от iOS, веба, документов, интеграций или совместных пространств.

## Внимательность, границы и вопросы

- Помнить основную идею и проверять, что работа помогает её реализовать. Не добавлять посторонние продукты, функции и техническую сложность без связи с текущей задачей.
- Не бояться задавать вопросы: при неоднозначной предметной логике, изменении границ, риске для данных или отсутствии необходимых сведений задать короткий конкретный вопрос, желательно с рекомендацией. Независимую работу продолжать; отсутствие ответа не считать согласием.
- Обычные обратимые инженерные решения в согласованных границах принимать самостоятельно и фиксировать основания. Не перекладывать на пользователя выбор каждой библиотеки и не переспрашивать уже принятые решения.
- Различать **решение пользователя**, **рекомендацию инженера**, **открытый вопрос**, **реализацию** и **проверенный результат**. Не превращать сохранённую идею в якобы утверждённое или выполненное решение.
- Освежать документацию при переходе этапа, перед изменением модели данных/разрешений/синхронизации и при сомнениях. Перед существенным изменением перечитывать затронутые спецификации и сценарии; не полагаться только на прежнюю переписку.
- Новое прямое указание пользователя имеет приоритет над старой записью. Существенные противоречия между документами явно обозначать и согласованно исправлять; не придумывать недостающие решения.
- Документация и дневник — долгосрочная память инженерной работы. Дневник не должен содержать секреты, токены, реальные личные аудио, финансовые сведения или лишние персональные данные. Примеры обезличивать.
- При передаче подзадач передавать текущие ограничения и разделять владение файлами. Основную проверку целостности результата выполняет ведущий агент.

## Исходные обязательные принципы

1. Treat README.md and docs/* as **target full-production product vision**. Do not present requirements as implemented software, and do not quietly strip modules to a small MVP.
2. Core identity: independent AI secretary, user-owned long-term accountable memory, voice/text input, finances/debts, tasks, people/projects, calendar, proactive briefings, multi-language RU/AZ/EN.
3. Never invent transaction data or answer personal-history queries without supporting stored evidence. Preserve provenance, temporal semantics, entity disambiguation, exact decimal money, idempotency, and user correction.
4. Privacy and permissions are non-negotiable: no hidden mic/WhatsApp snooping, no unauthorized external actions, protect all tenant boundaries, never expose third-party or user personal data to unrelated users.
5. Before writing production code, provide ADRs on architecture, synchronization, auth, ledger, retention, vendor routing and AI tool permissions, then tests and acceptance mapping. Avoid vendor lock-in.
6. Optimize for quality and reliability first, then cost; provider price/performance assumptions must be verified with real tests and current official documentation.
7. All generated features need error states, offline behavior, accessibility, i18n, source links, revision tracking, threat model and measurable acceptance criteria.
8. Do not assert production readiness from unit tests alone. Stage real-device, backend and reliability validations separately.
9. Keep documentation coherent: changes to domain behavior update PRODUCT SPEC, DATA, MEMORY, UX, TESTS and SCENARIOS as needed.
10. Do not delete user or repository content without explicit direction. Document unresolved assumptions rather than inventing answers.
