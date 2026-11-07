# Этап 14 — дополнительные пункты лабораторных

> Kotlin не компилировался (нет Gradle/SDK). Java-стенды запущены реально:
> `java tools/backend/IdentityServerTest.java` → **OK (34 checks)** (`lab_reports/backend_run.txt`),
> `java tools/lab16/Lab16VariantsTest.java .` → **OK (42 checks)**.

## ЛР10 доп. — E2E на настоящем публичном API
`app/src/androidTest/.../data/RealPublicApiE2ETest.kt`: HTTPS jsonplaceholder → Retrofit → `RoomPostCatalogRepository` → Room → Flow.
Проверки: первая страница = 20, вторая без дублей (40), детали+комментарии в кэше, локальный поиск, 404 → `CatalogApiError.Http(404)`.
Запуск (нужен интернет на устройстве):
```
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.realApi=true -Pandroid.testInstrumentationRunnerArguments.class=com.phonoteka.data.RealPublicApiE2ETest
```
Без флага тест пропускается (assume), чтобы обычный прогон не зависел от сети.

## ЛР13 доп. — отдельный identity-backend
`tools/backend/IdentityServer.java` (только JDK, слушает 127.0.0.1):
- `POST /auth/login` — PBKDF2-SHA256, роль/scopes назначает сервер (клиентский `role` игнорируется), 5 ошибок → 429 + Retry-After на 60 с;
- `POST /auth/refresh` — ротация; повтор старого refresh → отзыв всего семейства (access тоже);
- `POST /auth/logout` — идемпотентный отзыв;
- `GET /me`, `/pass` (pass:view), `/audit` (audit:read) — 401/403;
- непрозрачные токены, на сервере только SHA-256; access 5 мин, refresh 7 дней; `Cache-Control: no-store`; лимит тела 4 КБ, только `application/json`, дубли ключей → 400; в журнале нет секретов.

Запуск и подключение эмулятора:
```
java tools/backend/IdentityServer.java 8085
adb reverse tcp:8085 tcp:8085
```
Учётные записи: `student` / `guard` / `admin`, пароль — `PHONOTEKA_DEMO_PASSWORD` или `demo-pass-123`.
Автотест сервера: `java tools/backend/IdentityServerTest.java` (34 проверки).

Android-клиент: `security/RemoteIdentityClient.kt` — типизированные результаты (InvalidCredentials, Locked, SessionRevoked, Forbidden, Network), access только в памяти, scopes из ответа сервера, не-HTTPS адрес кроме 127.0.0.1 отклоняется. JVM-тест `RemoteIdentityClientTest` (MockWebServer); добавлена `testImplementation org.json:json`.

## ЛР16 доп. — CI
`.github/workflows/android-security.yml`: Semgrep (правило «токен в логе», `--error`), Gitleaks по истории, Java-стенды ЛР16 и backend, `testDebugUnitTest lintDebug assembleRelease assembleDebugAndroidTest`. GitLab-вариант `.gitlab-ci.yml` остался. Заработает после push в GitHub.

## ЛР14 — мелочь
Удалён `navDeepLink phonoteka://details/{trackId}` из `Navigation.kt`; детали трека — только внутренняя навигация.

## Остальные «доп./PRO» пункты
Код для них был раньше (Macrobenchmark ЛР9, risk step-up ЛР13, TLS-downgrade ЛР15, PRO ЛР10–14). Их статус «частично» — не из-за кода, а потому что нужен прогон на устройстве (см. `MISSING_VERIFICATION.md`). Macrobenchmark: `./gradlew -PwithBenchmarks=true :benchmark:connectedBenchmarkAndroidTest`.
