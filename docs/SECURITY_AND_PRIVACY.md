# Security, Privacy and Human Control

**Решение владельца 2026-10-31:** начать повседневную работу локальной памяти. Сохраняем разграничение разрешения владельца и проверенного результата: MEM-05.07–05.08/MEM-06.09 остаются открытыми в непроверенной части. При тестах только синтетические внешние данные, только Gemini, без платных подключений. Согласие на локальную запись не разрешает отправлять архив; каждой облачной сессии/записи предшествует объяснение Google и отдельное согласие. Промпты/транскрипты/ключи не журналируются, короткое аудио не пишется на диск. Замена подписи APK требует старый keystore либо отдельно разрешённую переустановку; Android Keystore не является резервной копией и теряется при удалении приложения.

## Этапы защиты — уточнение 2026-10-29

Сначала нативное Android-приложение с локальными данными/логикой; после тестирования и подтверждения всего функционала — серверная адаптация по разделу 1.1 [ENGINEERING_PLAN.md](ENGINEERING_PLAN.md). До личных записей обязательны локальная защита БД/файлов/ключей, политика системных резервных копий, экспорт/удаление и безопасная диагностика. Способ доступа к внешнему AI определяется ADR; общий секрет в распространяемом APK недопустим. Серверные сессии, tenant/space isolation, удалённый отзыв и серверные хранилища из следующих разделов реализуются и проверяются после перехода. Локальная проверка не является доказательством их безопасности.

## Threat model
Sensitive personal finances, debts, third-party names, private speech and documents; attack vectors include stolen devices/tokens, prompt injection, cross-tenant leakage, insecure media URLs, compromised integrations, malicious attachments, insider misuse, replay, data poisoning and billing abuse.

## Mandatory defenses
MFA/passkeys as supported; device session revocation; OAuth scopes and rotation; TLS in transit, encryption at rest with managed keys; short-lived signed attachment URLs; secrets only server-side; object malware checks; strong tenant/space authorization on all queries and storage; idempotency for writes; rate limits and fraud detection.

## Privacy by design
Microphone opt-in with visible recording state. No stealth monitoring. Avoid sending more user context than needed to model providers. Per-provider disclosures, retention policies, contractual review, residency and transfers subject to applicable laws. Original audio deletion configurable; user-accessible history, export and deletion. Shared workspaces require explicit boundaries and invitation.

## AI safety
Untrusted documents/recordings may contain prompt injection; no execution of instructions from imported content. Tools behind deterministic permission checks. No autonomous money transfers, deletion, third-party messaging or external appointment changes without scoped user authorization and confirmation. Ambiguous financial entries enter approval queue.

## Compliance and governance
Before launch, conduct jurisdiction-specific analysis (Azerbaijan, EU/EEA and any target markets) for privacy, consent, recording laws, consumer subscription rules, payment terms and cross-border transfers. Do not claim GDPR certification or regulatory approval without evidence. Data processing agreements, incident response, DSAR workflow, subprocessor register, retention schedule, security contact and vulnerability disclosure necessary.

## Operational policies
Audit sensitive access without storing secrets/transcripts in logs. Access to production personal records restricted, monitored and justified; operator-facing diagnostics redacted. Backups encrypted; restores and deletion cascade tested. Incident classification, escalation, breach-response timing set by legal obligations. Threat models updated for each integration.

## Уточнения контрактов доверия

Права и подтверждение проверяются для точного содержания и версии действия перед исполнением, включая отложенные задания. Отзыв доступа распространяется на поиск, экспорт, контекст модели и будущие уведомления. Нельзя гарантировать немедленное стирание ранее скачанной копии с отключённого устройства или отзыв уже сделанного экспорта; пределы офлайн-доступа и очистку нужно явно определить.

Исправление, удаление источника и отзыв прав инвалидируют зависимые сводки/кэши/индексы. До открытия восстановленной резервной копии повторно применяются актуальные удаления и ограничения доступа. Prompt injection проверяется также на попытки подменить сумму, адресата или ссылку-источник. Это требования к будущим контрактам, не реализованные гарантии. Связанные сценарии — S29, S35–S37 в [SCENARIOS.md](SCENARIOS.md); план — [ENGINEERING_PLAN.md](ENGINEERING_PLAN.md).
