# Этап 13 — доделан код по ЛР11, ЛР13, ЛР14, ЛР16

> Kotlin в песочнице **не компилировался** (нет Gradle/Android SDK/интернета). Запущен только Java-стенд ЛР16:
> `java tools/lab16/Lab16VariantsTest.java .` → **OK (42 checks)**, F-01…F-08 REPRODUCED/BLOCKED.

## ЛР14
| Требование | Сделано |
|---|---|
| exported только InviteActivity | Новая `InviteActivity` (exported=true, фильтр `phonoteka://invite`, FLAG_SECURE, singleTop, excludeFromRecents). Прежний внутренний экран QR-приглашений треков → `TrackInviteActivity` (exported=false). У MainActivity удалён deep link `phonoteka://details`; она exported только как LAUNCHER (без этого приложение не запустить). |
| token `[A-Za-z0-9_-]{8,64}` | `security/EmployeeInviteLink.kt`: строгий разбор (нет userinfo/порта/пути/фрагмента/лишних и повторных параметров, длина ≤256), токен не попадает в `toString`/аудит. `MockInviteRegistration` — одноразовая регистрация, хранит SHA-256 токена. Экран `InviteRegistrationScreen`. |
| scopes pass:view / scan:execute / audit:read | `DemoScope(wire)`: `library:read`, `library:write`, `pass:view`, `scan:execute`, `audit:read`, `invite:create`; `DemoSession.scopeClaim` (как claim `scope` в JWT), `DemoScope.parseClaim` (неизвестные игнорируются). pass:view — у всех ролей, scan:execute — SECURITY/ADMIN, audit:read — ADMIN. |
| экран пропуска | `ui/components/EmployeePassCard.kt`: фото (заглушка), QR через ZXing `QRCodeWriter` (в QR нет токена сессии), статус «действует/истёк». В ЛР14 кнопка «Показать пропуск» — только после ответа мок-сервера по pass:view; смена роли скрывает пропуск. |
| обоснование разрешений | `PERMISSIONS_LAB14.md`. |

## ЛР11
notes.json переведён с Gson на **kotlinx.serialization** (`@Serializable` DTO, `Json{prettyPrint; ignoreUnknownKeys}`), плагин `org.jetbrains.kotlin.plugin.serialization` 1.9.25 + `kotlinx-serialization-json:1.6.3`. Формат v1 и ошибки (CORRUPT/UNSUPPORTED_VERSION/TOO_LARGE) прежние; старые файлы читаются. Строгие типы проверяются по JsonElement (числа в кавычках → CORRUPT). Gson остался только для Retrofit/резервной копии библиотеки. +5 тестов в `FreeNotesTest.kt`.

## ЛР13
- `security/RecoveryNotifier.kt`: `MockRecoveryOutbox` — при успешном `recover()` «отправляется» e-mail и SMS на маскированные адреса (без кода/PIN), аудит `recovery_notification_sent:email,sms`; экран входа показывает, куда ушло уведомление.
- `Lab13ResultsScreen` (Настройки → «Заказы и результаты · ЛР13»): «Обычные заказы» сразу; «Результаты» — если вход старше 5 мин: объяснение → PIN → только потом загрузка. Логика в `SensitiveResultsGate` (загрузчик не вызывается без step-up — тест).

## ЛР16
- В уязвимую версию (`tools/lab16/Lab16Variants.java`) добавлены **F-07** WebView (HTTP + JS-интерфейс отдаёт токен) и **F-08** clipboard без очистки. Hardened: WebView только https-allowlist, без JS/моста/file; clipboard — sensitive-флаг и очистка через 30 с, чужой текст не трогается.
- В самом приложении (hardened): WebView отсутствует (проверяется стендом по исходникам); кнопка «Копировать» в редакторе заметки → `SensitiveClipboard` (EXTRA_IS_SENSITIVE, `clearPrimaryClip` API28+, иначе пустой clip). Политика `ClipboardClearPolicy` — JVM-тест.
- Результат стенда: `lab_reports/lab16/variants_run.txt`.

## Новые / изменённые файлы
Новые: `InviteActivity.kt` (новая), `TrackInviteActivity.kt` (переименована), `security/{EmployeeInviteLink,RecoveryNotifier,SensitiveResultsGate,SensitiveClipboard}.kt`, `ui/screens/{InviteRegistrationScreen,Lab13ResultsScreen}.kt`, `ui/components/EmployeePassCard.kt`, `test/.../security/Step13SecurityTest.kt`, `PERMISSIONS_LAB14.md`.
Изменены: `AndroidManifest.xml`, `MockRoleAuthority.kt`, `LocalAuthManager.kt`, `AuthGateScreen.kt`, `Lab14AccessScreen.kt`, `FreeNotesRepository.kt`, `FreeNotesScreen.kt`, `Navigation.kt`, `SettingsScreen.kt`, `build.gradle.kts` (корень и app), `FreeNotesTest.kt`, `InviteManifestContractTest.kt`, `Lab16Variants*.java`, `MISSING_VERIFICATION.md`.

## Что проверить у себя
1. `./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease` — первая сборка скачает плагин serialization.
2. `./gradlew :app:connectedDebugAndroidTest` — в т.ч. 3 теста `InviteManifestContractTest` (список exported, ссылка invite, отсутствие details).
3. adb-команды из `MISSING_VERIFICATION.md` (invite valid/bad, details, TrackInviteActivity → SecurityException).
4. В `Navigation.kt` остался `navDeepLink phonoteka://details` — без intent-filter в манифесте он снаружи недоступен; можно удалить, если преподаватель придерётся.
