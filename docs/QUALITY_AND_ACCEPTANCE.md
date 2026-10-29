# Production Quality Contract

All targets below are **proposed acceptance thresholds** for validation, not measured results. Define verified SLOs from beta/load tests.

## Core correctness (release blockers)
- Unauthorized cross-tenant reads/writes = 0 in automated and adversarial tests.
- Duplicate capture requests do not cause duplicate ledger, obligations or reminders.
- Financial arithmetic/invariants: 100% deterministic test correctness on supported typed scenarios.
- Every canonical change traceable to source/user action with version history.
- High-risk external actions cannot execute without explicit authorized confirmation.
- Conflicting/ambiguous person, date, money, obligation direction => no confident automatic commitment.
- Deleted content must not leak through search indexes or summaries after deletion completes.
- Language tests cover RU/AZ/EN/code-switching including amounts, names, negation, time and noisy audio.

## Proposed SLOs
- Core API availability target >=99.9% monthly excluding announced maintenance (confirm budget/design).
- 95th percentile text response latency goal <=3 s for simple queries under baseline load, excluding queue/third-party outages.
- 95th percentile capture acknowledgement <=1 s with healthy network; async processing displays progress rather than promising exact duration.
- Reminder scheduling correctness >=99.99% in deterministic tests; measure delivered vs submitted, acknowledging mobile push networks cannot guarantee receipt.
- Zero data loss in documented restore tests within defined RPO/RTO; RPO/RTO set before production and tested.
- WCAG 2.2 AA-inspired accessibility assessment for web and platform accessibility testing for mobile, with known deviations documented.

## Evaluation suites
Unit tests, property-based financial ledger tests, time zone/DST tests, voice multilingual benchmarks, structured extraction golden cases, prompt-injection red teaming, RAG hallucination tests, approval-gate tests, tenancy fuzzing, offline-sync race tests, backup restore drills, mobile battery/performance tests, privacy/delete/export tests, subscription reconciliation.

## Provider quality gates
Compare WER plus critical-slot accuracy, confusion of «я должен/мне должны», exact entity matching, false positive actions, correct abstentions, cost per successfully handled message, latency and fallback. Provider changes require regression proof and staged rollout.

## Acceptance scenarios
Refer to docs/SCENARIOS.md, every scenario maps to automated E2E fixture with expected stored domain events and verified UI states. No «PASS» based solely on mocked API responses for integrations or real-device constraints; distinguish simulated / staging / real validation.

## Release gating
Security review, privacy/legal checklist, data migration tests, performance/load reports, SLO dashboard, incident runbook, app-store compliance, functioning export and deletion, disaster recovery validation. Failing invariant blocks production release regardless of feature count.

## Текущий порядок проверки и доказательства готовности

Следующие правила — **спецификация будущей проверки, не результаты тестирования**. Разработка начинается с Android и повседневных сценариев владельца; после проверки на его задачах — с похожих пользователей. Полный целевой охват продукта сохраняется. Последовательность работ описана в [ENGINEERING_PLAN.md](ENGINEERING_PLAN.md).

Для каждого требования и сценария вести связь: идентификатор → ожидаемые события и итоговое состояние → реализация → проверки → свидетельства. Статусы отражают разную степень подтверждения:

1. **Предложено:** определены вход, предпосылки, ожидаемое поведение и нерешённые вопросы.
2. **Реализовано:** есть код; само наличие кода не доказывает корректность.
3. **Проверено автоматически:** приложены версия кода, окружение, тест и результат; имитация внешнего сервиса отмечена явно.
4. **Проверено на реальном устройстве:** зафиксированы Android-устройство/версия ОС, разрешения, состояние сети и наблюдаемый результат, включая перезапуск и фоновую работу, где применимо.
5. **Проверено в ежедневном использовании:** указан период наблюдения, выполненные сценарии, исправления и найденные ограничения. Опыт одного владельца не подтверждает пригодность для всех пользователей.

Неприменимый уровень проверки отмечается с причиной. Изменение кода, модели, промпта или контракта требует повторной проверки затронутых сценариев; старое свидетельство не переносится автоматически. Для S01–S37 такие свидетельства пока не заявлены.

## Сквозная оценка AI и голоса

Проверять цепочку «исходная запись → транскрипт → сущности → предложения действий → подтверждение → сохранённое состояние → ответ с источником». Разделить набор настройки и закрытый проверочный набор; не подбирать промпты по ответам закрытого набора. Использовать синтетические примеры и явно разрешённые реальные записи, сохраняя происхождение, доступ и версию эталона.

Отдельно оценивать RU, AZ, EN и смешанную речь, шум, отрицания, имена, суммы, валюты, направление долга, даты, исправления и неоднозначность. Для каждого среза публиковать объём выборки и покрытие, а при статистических выводах — неопределённость оценки. Небольшой набор без ошибок не доказывает отсутствие ошибок в эксплуатации.

Метрики: точность критических полей и конечного состояния; доля ошибочно автоматически сохранённых изменений; корректные уточнения и отказы; доля входов, завершённых без ручного вмешательства; полнота и соответствие источников ответу; усилия пользователя на проверку и исправление. Точность автоматических действий оценивать вместе с их покрытием: отказ от всех действий не считается успешной системой.

Число `confidence`, выданное моделью, не считать вероятностью правильности. Политику автоматического сохранения калибровать по размеченным результатам отдельно для языка и типа действия; численные границы утверждать до допуска соответствующей функции. Смена модели, промпта или резервного провайдера требует сквозной регрессии, а не только проверки JSON Schema.

## Незакрытые критерии допуска

- Численные пороги качества извлечения, допустимой доли ошибочных автоматических записей и минимального покрытия ещё **TBD**. Сначала получить базовые измерения и определить цену ошибки для каждого типа действия; затем зафиксировать критерии до оценочного прогона.
- Ранее перечисленные SLO остаются целями. Нагрузка, метод измерения, RPO/RTO, сроки удаления и поведение при просроченном напоминании требуют отдельного согласованного контракта.
- Денежные операции и коррекции проверять на параллельных командах, повторах, устаревших версиях и сбоях между зависимыми изменениями. Сохранение локально, фиксация сервером, отправка уведомления, доставка и прочтение — отдельные наблюдаемые состояния.
- Перед использованием реальных личных данных проверить доступ, подтверждения, экспорт и удаление в соответствующем объёме. Перед расширением аудитории нужны проверки восстановления, отзыва доступа и изоляции пользователей. Наличие автоматических проверок не заменяет реальную проверку Android-сценариев и ежедневное наблюдение.
- Открытая критическая ошибка доступа, денежных инвариантов, потери данных или неподтверждённых действий блокирует допуск затронутой функции. Для остальных дефектов фиксировать последствия и условия повторной проверки.

Вопросы цен, тарифов, бюджетов и расчёта экономики сейчас полностью отложены по решению владельца. Ранее записанные экономические пункты сохраняются как часть будущего видения и не являются задачами текущего этапа.
