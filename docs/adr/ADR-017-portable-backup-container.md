# ADR-017: формат криптографического контейнера переносимой копии

**Исторический статус до ACT-0157:** инженерское решение; потоковые exporter и importer соединены со snapshot/manifest/staged validator и проверены только на synthetic Android DB.

**Статус обновлён ACT-0157:** encrypted snapshot exporter/importer/validator and internal recoverable active-DB cutover pass a synthetic Android 14 path; startup recovery is called before Room open. SAF/user-facing flow and physical process/device acceptance remain open, therefore M20-02 remains partial.

**Совместимость схем обновлена ACT-0188:** новые экспорты используют текущую Room schema 21. Только Room 18 является поддерживаемым legacy input (это последний schema version фактически зафиксированного export format); staged Room open автоматически проводит 18→21 миграции и readback требует текущую версию. Room 19/20 пока не признаются форматами переносимой копии. Это не доказывает поддержку всех состояний app-owned stores: общий scope backup остаётся ограничен по ADR-013/inventory.


## Контекст и границы

Ключ основной SQLCipher базы обёрнут Android Keystore и хранится в исключённой из системной копии области. Он намеренно не переносится между устройствами. Пользовательская копия должна быть независима от Keystore; локальные временные аудио/диалоги не включаются. Пользователь распоряжается созданным файлом, приложение не хранит его копию.

Решение владельца уже фиксирует отключённые Android backups, пользовательский контроль export-файлов и удаление миграционных копий после подтверждённого переноса. Данное ADR не меняет эти решения и не разрешает автоматически включать копирование.

## Контейнер v1

- AES-256-GCM с 96-bit случайным nonce и 128-bit tag.
- Ключ выводится из введённой пользователем парольной фразы через PBKDF2-HMAC-SHA-256; соль — 128 бит от `SecureRandom`, итерации записываются в заголовке и принимаются только в диапазоне 600,000–2,000,000. В текущей реализации используется 600,000; это инженерский baseline, требующий измерения на поддерживаемом Android-железе перед UI-релизом.
- AAD включает весь заголовок: magic, версию формата, параметры KDF, соль и nonce. Неверный пароль и любая порча файла дают отказ без открытых байтов.
- Пароль 12–1,024 символа. Не сохранять его, не журналировать, не передавать модели. Временные массивы ключа очищать после операции.
- Разбор ограничен размером контейнера и параметрами KDF до вычисления ключа; неизвестная версия, неправильная длина или параметры вне диапазона отклоняются.
- Зашифрованный payload — бинарный manifest + отдельно keyed SQLCipher snapshot. Текущий `PortableBackupPayload` manifest содержит формат, Room schema, app versionCode, timestamp создания, ciphertext length и SHA-256 digest; parser ограничивает размеры и версии и проверяет точный размер/digest. Весь manifest и ключевой материал находятся внутри внешнего AES-GCM, поэтому metadata аутентифицированы контейнером. Новые экспорты требуют именно текущую `AGENT_DB_SCHEMA_VERSION` (сейчас 21), создают отдельный SQLCipher snapshot и задают Room `user_version` (SQLCipher export его не копирует); до принятия результата сравнивают integrity check, список таблиц и число строк каждой таблицы. Parser/validator принимают текущую схему и legacy schema 18; legacy snapshot открывается через зарегистрированные Room migrations до текущей схемы, после чего readback обязан показать текущую версию. Room 19/20 не поддерживались форматом exporter и отклоняются. `PortableBackupExporter` координирует snapshot → streaming manifest digest → AES-GCM в приватный `AtomicFile` → copy в предоставленный output stream; используются 64 KiB buffers. После ошибки destination может содержать неполный файл, поэтому caller должен удалить/заменить целевой document; приватные временные файлы экспортера очищаются. Координатор проверен только synthetic probe, не является доказательством полноты backup.

NIST SP 800-132 описывает PBKDF для защиты хранимых данных и ключей; NIST объявил намерение добавить memory-hard вариант при пересмотре документа. Здесь PBKDF2 выбран как доступная стандартная JCA реализация Android без новой криптографической зависимости, а не как окончательная оптимальная UX/security оценка. SQLCipher `sqlcipher_export()` копирует схему и данные в attached DB; интеграция должна использовать документированный механизм, избегать сырого копирования активной SQLite/SQLCipher базы/WAL и проверять фактическую семантику операции на Android.

## Безопасный импорт

1. `PortableBackupImporter` ограничивает размер до KDF и потоково расшифровывает в app-private `AtomicFile`; этот временный candidate не читается до проверки GCM tag. После этого manifest/digest проверяются потоково, а SQLCipher snapshot выделяется отдельным файлом. Временный payload содержит snapshot key и удаляется после извлечения; файлы не покидают закрытое хранилище приложения. Физическое защищённое стирание flash не обещается.
2. Проверить формат/manifest и поддерживаемую схему, затем SQLCipher integrity/migration на отдельной БД.
3. Открыть candidate только под отдельным UUID staging DB name через Room/SQLCipher, сверить версию/integrity и table/row readback по manifest без логирования содержимого.
4. Только после успешного close/reopen переключать активную БД атомарным recoverable-маркером. Не удалять прежнюю базу до завершённой проверки нового хранилища.
5. Неверный пароль, повреждение, неизвестная схема, нехватка места, остановка процесса или ошибка ключа оставляют исходную активную БД нетронутой; очистка только собственных временных артефактов.

В будущей UX-реализации экспорт/импорт требует отдельного объяснения, подтверждения, ввода и повторного ввода парольной фразы, picker URI и локализованных состояний ошибки/успеха (AZ/RU/EN). До полной integration/device проверки не включать на персональных данных и не считать recovery закрытым.

## Источники

- [NIST SP 800-132](https://csrc.nist.gov/pubs/sp/800/132/final) и [решение NIST о пересмотре](https://csrc.nist.gov/news/2023/decision-to-revise-sp-800-132).
- [SQLCipher API: `sqlcipher_export`](https://www.zetetic.net/sqlcipher/sqlcipher-api/).

## Приёмка

Host tests покрывают внешнюю криптографию (включая streaming round-trip, declared-length mismatch и неверный пароль), потоковое извлечение snapshot с digest verification/cleanup и inner-manifest rejection cases. Исторические synthetic Android 14 проверки проходили через exporter → importer → Room18 staged validator и подтверждали plaintext absence, 44 user tables, close/reopen, wrong password/key rejection и cleanup. Текущий Android probe должен проверять export текущей schema 21 и staged readback 21; отдельная Room v1→21 migration probe проверяет связность зарегистрированных миграций. Успешная компиляция этих opt-in probes не означает их запуск. Открыты: device execution, старого Room18 portable fixture import round-trip, KDF timing на поддерживаемых устройствах, SAF provider, corrupt/unsupported import UX, URI permissions, storage failure/disk-full, полная multi-store scope, UX и owner acceptance. M20-02 не закрывать по одному synthetic probe.

## User workflow checkpoint ACT-0158

The UI is implemented in `PortableBackupScreen.kt` and reached from More. It uses SAF rather than broad storage permission, validates a known bounded document size before KDF, never changes the active DB during candidate preview, requires a separate final replacement confirmation, and restarts the process after arm so startup recovery precedes normal Room access. The host UI and synthetic storage probe pass, but the real document provider/device path is not yet accepted.
