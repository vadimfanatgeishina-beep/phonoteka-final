# ЛР16 — аудит и hardening Phonoteka

**Объект:** `com.phonoteka`, исходная версия `eadf4b0` и hardening-ветка `labs-integration`.  
**Методика:** статический просмотр Android manifest/build config, Compose Compiler, Gradle lint и unit tests; динамические проверки выполнялись только на собственном проекте. CVSS v3.1 используется для приоритизации; векторы ниже отражают учебную модель и требуют подтверждения на целевом устройстве.

## Резюме

В исходном снимке найдены разрешённые Android Backup, отсутствие локального auth gate, неограниченная обработка сетевого аудиопотока, destructive fallback Room при миграции и URL-параметры в диагностических сообщениях загрузчика. В текущем hardening-снимке отключены системные backups, добавлены PIN/биометрия и blockout, введены пределы сетевой загрузки и частные TLS/HMAC механизмы для лабораторного стенда, включены R8/resource shrinking, удалены URL из диагностических сообщений. Destructive fallback Room удалён; историческая схема Room v1 в Git отсутствует, поэтому миграцию из неизвестной старой схемы нельзя честно подтвердить.

Итоговый прогон 08.10.2026: `testDebugUnitTest` — 68 тестов, 0 failures/errors; `lintDebug` — успешно, 51 warning и 0 errors; `assembleRelease` и `assembleDebugAndroidTest` успешны. Release APK unsigned/minified. На USB-телефоне ZS661KS (Android 13) прошли AndroidKeyStore refresh-token и AES-GCM backup instrumentation tests, а также реальная TLS/HMAC проверка локального стенда: верный pin принят, дубликат nonce и неверный pin отклонены. UI Compose runner зависает при спящем экране; оставшиеся UI/TalkBack/SAF/биометрические сценарии требуют активного экрана. Внешний MITM не запускался.

## Находки и триаж

| ID | Находка в исходном снимке | MASVS | CVSS 3.1 | Приоритет / состояние |
|---|---|---|---|---|
| F-01 | `allowBackup=true`: данные базы/настроек могли попасть в системную резервную копию. | STORAGE | 5.5 `AV:L/AC:L/PR:L/UI:N/S:U/C:H/I:N/A:N` | P1 — исправлено: `allowBackup=false`, exclusions для cloud/device transfer; есть SAF backup библиотеки. |
| F-02 | Открытие каталога не требовало локальной аутентификации; любой, кто открыл приложение на разблокированном устройстве, видел/менял библиотеку. | AUTH | 7.2 `AV:L/AC:L/PR:N/UI:R/S:U/C:H/I:H/A:N` | P1 — исправлено кодом: 6-значный PIN, KeyStore HMAC, биометрия, lockout и блокировка сессии через 5 минут; требуется device test. |
| F-03 | HTTP/HLS ответ без лимита размера мог заполнить хранилище или остановить загрузку. | NETWORK/RESILIENCE | 6.4 `AV:N/AC:L/PR:N/UI:R/S:U/C:N/I:N/A:H` | P1 — исправлено: HTTPS only, max 200 MiB, playlist 2 MiB/2,000 сегментов, запись `.part` и rename. Unit/локальный серверный negative test нужно приложить. |
| F-04 | `fallbackToDestructiveMigration()` мог удалить каталог при несопоставимой версии Room. | STORAGE | 7.2 `AV:L/AC:L/PR:N/UI:R/S:U/C:N/I:H/A:H` | P1 — удалено silent destructive fallback. Исторической schema v1 в репозитории нет; upgrade с неизвестной БД не подтверждён. |
| F-05 | `StreamSearcher`/сетевые ошибки включали URL источника; URL может содержать временные query-параметры. | STORAGE/PRIVACY | 3.2 `AV:L/AC:L/PR:L/UI:N/S:U/C:L/I:N/A:N` | P2 — исправлено: в ошибках остаётся тип источника/host, полные URL не журналируются. |
| F-06 | Release-сборка исходно была без minification/shrinking, увеличивая доступную поверхность анализа и размер приложения. | RESILIENCE | Hardening gap, не самостоятельный exploit; CVSS не назначается | P2 — включены `minifyEnabled` и `shrinkResources`; release APK собран успешно, unsigned, 5,051,463 байт. |

## Повторяемая проверка

Из корня репозитория при JDK 17 и Android SDK 34:

```powershell
.\gradlew.bat :app:lintDebug
.\gradlew.bat :app:dependencies --configuration releaseRuntimeClasspath
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:assembleRelease
.\gradlew.bat :app:connectedDebugAndroidTest
git show eadf4b0:app/src/main/AndroidManifest.xml
git show eadf4b0:app/build.gradle.kts
```

Ожидаемые hardening-признаки: merged release manifest показывает `allowBackup=false`, `usesCleartextTraffic=false`; release build minified; debug-only local mock client не попадает в release; unit tests проходят. `connectedDebugAndroidTest` требует online emulator/device. Запуск `adb backup` на Android 12+ может быть системно недоступен, поэтому отсутствие данных проверяется manifest/extraction rules и тестовым устройством, не предположением по команде.

## Локальный TLS/HMAC стенд

`tools/LocalLab15MockServer.java` обслуживает только `127.0.0.1:8443`. Нужны локальный PKCS#12 keystore и `PHONOTEKA_MOCK_HMAC_KEY` из окружения. Приватный ключ не включён в Git. Debug client pinning ограничен `localhost`; это не закрепляет сертификаты YouTube/SoundCloud и не выдаётся за защиту сторонних источников. Клиентский общий HMAC-ключ сам по себе не защищает сервер, если встроен в APK: в лаборатории он используется только как учебный test key.

## Остаточный риск / честный статус

- Runtime-скриншоты, adb-выводы и MITM-доказательства на эмуляторе не приложены; AVD завершался offline. UI instrumentation только скомпилированы.
- `lintDebug` успешен с 51 warning и без errors; предупреждения сохранены в Gradle lint report. Release сборка успешна, APK не подписан. Снимки baseline-находок и статических проверок сохранены в `lab_reports/lab16/static_evidence.txt`.
- Для Room отсутствует история исходной schema v1; silent wipe удалён, но тест обновления старой установки должен предоставить владелец конкретной прежней БД.
- RASP отмечает debugger/emulator как сигнал и не блокирует владельца; проверки можно обойти, это не замена серверной защите.


