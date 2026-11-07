> **Следующее дополнение подготовлено:** риск-ориентированный step-up, private QR Activity/camera, server-side downgrade regression, измерительные инструменты. См. MISSING_FEATURES.md / MISSING_VERIFICATION.md. Новые тесты/сборки не запускались; прежние заявления об отсутствии этих исходников ниже исторические.

> Актуальный этап: FINAL_STEP3.md / lab_reports/final_step3/RESULTS.md. Предыдущие отчёты исторические; Android acceptance не наследуется.

> Текущий этап: FINAL_STEP2.md / lab_reports/final_step2/RESULTS.md. Предыдущие evidence относятся к предыдущим снимкам; device acceptance не наследуется.

> Последнее дополнение: FOLLOWUP_DRAFTS.md; отдельные фактические результаты — lab_reports/draft_followup/RESULTS.md. Старые отчёты относятся к verified baseline.

> Текущий фактический итог нового кандидата: ACTUAL_VALIDATION.md / lab_reports/current_validation/. Ниже сохранён исторический план/контекст; сообщения о неподтверждённой сборке относятся к предыдущему source-only этапу. Device/historical критерии остаются открытыми, если не подтверждены отдельно.

> **Full-source candidate (2026-10-08): новые изменения НЕ собраны/не тестировались. Начните с FULL_DELIVERY.md и RUN_MATRIX.md. Старые результаты ниже относятся к прежним версиям.**

# Phonoteka — Android

Android-клиент музыкальной библиотеки на Kotlin и Jetpack Compose. Проект хранит каталог в Room, настройки в DataStore, воспроизводит локальное аудио через Media3 и использует системный выбор документов для локальных файлов.

## Структура

- `data/` — Room, сущности, репозитории, библиотека и JSON-резервная копия.
- `network/` — поиск/загрузка аудио, Retrofit-каталог для ЛР10.
- `player/` — foreground service и Media3.
- `security/` — локальная аутентификация, демонстрационные роли, журнал доступа, HMAC-подпись запросов и сигналы RASP.
- `ui/` — навигация, Compose-экраны и компоненты.
- `src/test` — unit-тесты; `src/androidTest` — Compose UI-тесты.

## Лабораторные

- ЛР8–9: темы, общие Compose-компоненты, доступность, адаптивный главный экран, стабильные ключи списка, анимации и тесты.
- ЛР10: Retrofit/Gson, ручная загрузка страниц по 20, Room offline-cache, поиск с отменой после debounce, детали/комментарии и typed network errors.
- ЛР11: SAF backup/import библиотеки с проверкой JSON, подтверждением destructive replace и атомарной внутренней AES-GCM копией на AndroidKeyStore.
- ЛР12: [модель угроз v1](THREAT_MODEL_LAB12.md), [обновление v2](LAB12_MODEL_V2.md), [планы red/blue team](ATTACK_PLANS_LAB12.md).
- ЛР13: PIN HMAC, blockout, одноразовый recovery-код, biometric prompt с Keystore CryptoObject, audit, step-up перед деталями трека и FLAG_SECURE.
- ЛР14: демонстрационные роли EMPLOYEE/SECURITY/ADMIN, scopes, mock-server authorization, журнал allow/deny и runtime-запрос POST_NOTIFICATIONS.
- ЛР15: debug-only pinning-клиент локального HTTPS стенда, HMAC/timestamp/nonce и mock-server.
- ЛР16: release R8/resource shrinking, отключённые системные backups, cleartext policy, RASP-сигналы и [аудит](AUDIT_REPORT.md).

Полная по-пунктная сверка исходных DOCX, включая невыполненные runtime/PRO пункты, находится в [LAB_REQUIREMENTS.md](LAB_REQUIREMENTS.md). Код адаптирует лабораторные к музыкальной библиотеке; экраны заметок, пропуска/QR и геотрекера не представлены как буквальные функции Phonoteka.

## Сборка и проверки

Нужны JDK 17 и Android SDK 34. Команды из корня проекта:

```powershell
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:assembleRelease
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:connectedDebugAndroidTest
.\gradlew.bat :app:lintDebug
```

Debug APK: `app/build/outputs/apk/debug/app-debug.apk`. Release build включает R8/resource shrinking; в проекте намеренно не задан release signing key, поэтому перед распространением нужна отдельная release-подпись.

Локальная HTTPS фикстура ЛР15 использует тестовый сертификат для `localhost`. Приватный ключ стенда и HMAC test key должны задаваться локально переменными окружения; они не хранятся в репозитории. Debug-клиент не используется приложением в release.

Ограничение учебной модели: mock-роль в ЛР14 не является серверной авторизацией, а клиентский RASP можно обойти на контролируемом устройстве.

## Full-source candidate (2026-10-08)

Старые результаты сборок выше относятся к историческим снимкам. Полное расширение ЛР8–16 в этом архиве подготовлено, но не собрано и не проверено.
Начните с `FULL_DELIVERY.md`, `LAB_REQUIREMENTS.md` и `RUN_MATRIX.md`. Не переносите 68/68, lint51 и прежние device results на новый код.
Приватные deploy/PKCS12/env файлы намеренно отсутствуют. Debug local WSS без публичного owned-fixture env отключён; release не содержит fixture key.

## Продолжение финальной версии

Текущий блок доработок заметок/TLS и критерии проверки: [FINAL_STEP1.md](FINAL_STEP1.md). Точный контракт локального протокола: [LAB15_PROTOCOL.md](LAB15_PROTOCOL.md). Это промежуточный кандидат, не заявление о закрытии всех лабораторных.
