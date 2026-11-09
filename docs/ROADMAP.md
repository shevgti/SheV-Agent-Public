## Ограничения на границе исполнения (2026-11-05)
- [x] Ограничен размер входа шага плана: максимум 8192 символа.
- [x] Повторная проверка размера непосредственно в `AgentToolExecutor` (по умолчанию 8192; допустимая конфигурация до 65536 символов).
- [x] Исполнитель отклоняет чрезмерный ввод до вызова обработчика.
- [x] Исключения обработчика изолируются; наружу возвращается безопасный `HANDLER_FAILED` без внутренних деталей.
- [x] Добавлен общий механизм `ToolInputSchema` для JSON-объектов: типы полей, required/optional, длины, диапазоны чисел и allow-list строк.
- [x] Исполнитель отказывает, если схема не зарегистрирована, payload не JSON-объект или содержит неизвестные/неверно типизированные поля.
- [ ] Описать отдельные реальные схемы для каждого подключаемого инструмента и добавить доменную валидацию внутри адаптеров.

## Самонастраиваемый интерфейс агента
- [x] Добавить декларативную модель компонентов рабочего пространства.
- [x] Добавить policy для добавления, перемещения, скрытия, переименования и удаления обычных компонентов.
- [x] Запретить агенту скрывать/удалять системные элементы разрешений и глобальной остановки.
- [x] Добавить unit-тесты на допустимые изменения и обходы ограничений.
- [ ] Подключить renderer Compose к декларативной раскладке и добавить UI предпросмотра.
- [ ] Сохранять раскладку в Room и добавить откат к предыдущей версии.
- [ ] Подключить команды модели к layout tools через approval/policy pipeline.

## Голосовой интерфейс и управление разрешениями
- [x] Зафиксировано требование: голосовое общение на русском языке.
- [x] Добавлен fail-closed контракт проверки голосового подтверждения: профиль READY, одноразовый challenge, request ID, срок действия, speaker match, liveness и confidence threshold.
- [x] Добавлена проверка привязки голосовой команды к одному ожидающему запросу разрешения; поддерживаются отдельные намерения ALLOW_ONCE / ALLOW_PERSISTENT / DENY / REVOKE.
- [ ] Подключить реальный ASR для русского языка и голосовой ответ (TTS), с видимым текстом распознанной команды.
- [ ] Реализовать enrollment и локальную speaker verification; оценить anti-spoof/liveness, хранение шаблона с Android Keystore и удаление профиля.
- [ ] Реализовать постоянные разрешения с узкой областью (tool/capability/project/path), сроком действия и немедленным отзывом.
- [ ] Связать голосовую выдачу/отзыв разрешения с точным pending request; фраза без активного запроса не меняет grants.
- [ ] Критические действия (удаление, платежи, публикация/релиз, изменение прав/безопасности) всегда требуют отдельного подтверждения на конкретную операцию и дополнительной защиты от replay/spoof.
- [ ] Добавить UI управления голосовым профилем, постоянными разрешениями, журналом и глобальной остановкой.
- [ ] Добавить тесты и device acceptance tests для русского ASR/TTS, ложного совпадения, воспроизведения записи, истечения challenge и отзыва grants.

- [x] Добавлена модель постоянного grant с точной capability и scope PROJECT / PATH / RESOURCE.
- [x] Добавлена проверка срока действия, отзыва, запрет глобального scope и запрет постоянных grants для CRITICAL capabilities.
- [x] Добавлено сопоставление PATH по границе каталога и блокировка traversal-путей.
- [x] Добавить Room entity/DAO для хранения, наблюдения, чтения активных и отзыва grants; миграция БД 3→4.
- [ ] Добавить журнал событий выдачи/отзыва разрешений.
- [x] Добавить экран просмотра и отзыва действующих постоянных разрешений.
- [x] Встроить проверку grant в AgentToolExecutor перед обработчиком; требуется передать точный resource target.
- [ ] Гарантировать, что target вычисляется доверенным адаптером из проверенных аргументов, а не задаётся моделью напрямую.
- [ ] Добавить экран управления разрешениями и голосовые команды выдачи/отзыва.

## Самонастраиваемый интерфейс агента
- [x] Декларативная модель компонентов рабочего пространства и policy редактирования.
- [x] Защита системных элементов разрешений и глобальной остановки от скрытия/удаления.
- [x] Unit-тесты разрешённых изменений и попыток обхода.
- [ ] Подключить Compose renderer и предпросмотр изменений.
- [ ] Сохранять раскладку в Room и добавить откат.
- [ ] Подключить инструменты управления интерфейсом к ИИ-модели через policy.

- [x] Сохранять декларативную раскладку в Room (БД v5, миграция 4→5).
- [x] Добавить экран редактирования: перемещение, скрытие/показ, добавление модулей и сброс.
- [x] Применять сохранённую раскладку к порядку и видимости встроенных блоков главного экрана Compose.
- [ ] Добавить предпросмотр с подтверждением и откат к предыдущей версии.
- [ ] Подключить инструменты управления интерфейсом к ИИ-модели через policy.


## Объединение актуального интерфейса и Gemma runtime — 2026-11-05
- [x] Найдена причина расхождения APK: ветка `agent/github-actions-readonly-client` содержит обновлённый интерфейс с нижней навигацией («Главная», «Проекты», «Инструменты», «Память»), а ветка `agent/google-adk-litertlm-spike` с Gemma была создана от более старой базы и сохранила старую длинную главную страницу.
- [x] Сверены два реальных артефакта: run #198 / artifact 13420711534, commit `63064f76fae2e16693e24acaaf7cf195f7b5aa63`, version 0.1.3 — новый нижний tab bar, но ещё без Gemma; run #319 / artifact 13439323296, commit в build-info `07d99dd4d97f41362bad334612a7daff612399bd`, version 0.1.3 — содержит локальную Gemma, но старый экран. Именно второй APK ранее был выдан пользователю, что объясняет увиденный старый интерфейс.
- [x] Создана интеграционная ветка `agent/google-adk-litertlm-ui-merge` от актуальной UI-ветки `agent/github-actions-readonly-client`.
- [x] Перенесены pinned Gemma 4 E2B IT artifact spec, SHA-256 проверяемый downloader, LiteRT-LM + ADK runtime, lifecycle owner ViewModel и русскоязычная панель модели.
- [x] Сохранена нижняя навигация и текущий workspace dashboard; панель Gemma встроена в домашний экран. Runtime больше не принадлежит recyclable LazyColumn item.
- [x] ADK tool adapter подключён к текущему AgentToolExecutor с обязательной JSON input schema; вход адаптера валидируется существующей policy.
- [x] Версия интегрированной сборки: 0.1.5 / versionCode 6. Предыдущая Gemma-сборка была 0.1.4 / code 5; новая UI+Gemma версия различима по версии.
- [x] Android build toolchain приведён к JDK 21 / Gradle 9.8 / AGP 9.3.1 / compileSdk 37; CI настроен на unit tests, debug APK и Android 16 instrumentation smoke test.
- [x] Установлена причина KSP `unexpected jvm signature V`: Room 2.6.1 обрабатывал DAO suspend-методы с неявным Unit return; в AgentDao есть несколько таких методов. Все Room артефакты выровнены на 2.8.5 (исправление доступно в Room 2.7.0+).
- [x] После Room 2.8.5 KSP, Kotlin compilation и unit tests прошли; APK packaging выявил дублирующийся `META-INF/INDEX.LIST` из Google Auth/API dependencies.
- [x] Добавлено безопасное исключение `META-INF/INDEX.LIST` из packaged resources; ожидается повторная сборка APK.
- [ ] Дождаться CI на актуальном head интеграционной ветки; подтвердить unit tests, APK assembly и emulator smoke test.
- [ ] Создать/проверить Draft PR интеграции к `agent/github-actions-readonly-client`; не мержить без отдельного решения.
- [ ] После успешного CI проверить установку на Poco F8 Ultra и фактический запуск Gemma, русский prompt и повторный ход.
- [x] Для будущих debug APK добавлено кеширование `~/.android/debug.keystore` через GitHub Actions cache key `shev-agent-debug-keystore-v1`, чтобы сохранять один сертификат между сборками этой ветки.
- [x] Добавлен импорт Gemma из SAF-документа с лимитом 4 ГБ и обязательной SHA-256 проверкой — это резервный путь переноса модели из старой debug-установки.
- [x] Проверены сертификаты двух прежних APK: run #319 SHA-256 `5e84617d81e96717a61465c4e02773c7d2f3875a70228ea600ca22bfbfa3394e`; run #198 SHA-256 `0ef4e032186cd8a2b56675f5e2af33c1b0a2020ed7f663010c5232b2d6c4fba9`. Они различаются; private key старого run #319 отсутствует в artifact и не может быть восстановлен из сертификата.
- [ ] До выдачи новой сборки сравнить сертификат APK с установленным run #319. Новая cache-подпись будет стабильна только для последующих сборок; она не совпадёт со старым APK. Не просить пользователя удалять старое приложение без решения о повторной загрузке 2,58 ГБ модели.


## LM Studio optional provider — 2026-11-07
- [x] Review official OpenAI-compatible API, native REST v1, server settings and token authentication.
- [x] Record a security-first integration specification in `docs/LM_STUDIO_INTEGRATION.md`.
- [ ] Add provider abstraction while preserving the existing on-device LiteRT-LM backend.
- [ ] Add Room-backed non-secret provider settings and Keystore-backed token storage.
- [ ] Add explicit pairing and HTTPS-first endpoint validation; do not globally enable Android cleartext traffic for convenience.
- [ ] Add bounded OpenAI-compatible model-list and chat client, cancellation, redacted diagnostics and disconnect.
- [ ] Add tests for auth, redirects, timeouts, response limits, server errors, model mismatch and cancellation.
- [ ] Keep model tool calls disabled initially; later treat them only as untrusted proposals through AgentToolExecutor, capability grants and one-shot approval.
- [ ] Run CI and real Android-to-desktop LAN acceptance test before marking the provider ready.
- [ ] Keep Fine-tuning Lab separate from inference: trainer (e.g. Unsloth) -> evaluation -> export/convert -> LM Studio inference.


### LM Studio client foundation (2026-11-07)
- [x] Add an HTTPS-only OpenAI-compatible endpoint configuration validator; reject user-info, query, fragment, unexpected paths and missing model/token.
- [x] Add bounded `GET /v1/models` and non-streaming `POST /v1/chat/completions` client with bearer authentication, redirect rejection, timeouts, response-size limit, cancellation checks and redacted status errors.
- [x] Add unit tests for endpoint normalization and unsafe endpoint rejection.
- [ ] Wire the client into a user-controlled provider selection/settings UI; the client is not yet reachable from the app UI.
- [ ] Add Keystore-backed token storage and Room persistence for non-secret endpoint/model settings.
- [ ] Add fake-server integration tests and validate cancellation/redirect/response limits.
- [ ] Run Gradle/CI and resolve any compile/test issues before treating this client as validated.
