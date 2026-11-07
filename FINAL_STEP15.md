# Этап 15 — исправления по отчётам DEVICE_REPORT.md / TEST_REPORT.md

> Kotlin в песочнице не компилировался — пересобрать: `./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease` и `:app:connectedDebugAndroidTest`.

| # | Проблема из отчёта | Исправление |
|---|---|---|
| 1 | `InviteManifestContractTest` падал на debug: Compose tooling добавляет exported `PreviewActivity`/`ComponentActivity` | Тест игнорирует только эти две библиотечные activity; для `com.phonoteka.*` по-прежнему строго InviteActivity + MainActivity(LAUNCHER); в release tooling-activity запрещены |
| 2 | Мёртвый `MainActivity.validateDeepLink` (details) | Метод и оба вызова удалены |
| 3 | `last_auth` пишется в prefs, но не читается | Запись удалена; свежесть входа только в памяти (fail-closed после process death) |
| 4 | `addTrack` искал id по artist+title | `TrackDao.insertSingle` возвращает `Long`, `addTrack` отдаёт настоящий rowId |
| 5 | «Проверка» на главной всегда 0 (статуса `review` нет) | Счётчик = треки в статусе `ready` (кандидат найден, ждёт проверки/скачивания) |

Остальные падения на устройстве — окружение, не код:
- `AuthBoundRefreshPolicyTest`, `BiometricRefreshVaultPolicyTest`: заблокировать → разблокировать телефон (PIN/отпечаток) и запустить в течение ~30 с.
- `LocalLab15TlsClientTest`: сначала поднять HTTPS-стенд из `RUN_MATRIX.md`, затем `bash tools/run_device_acceptance.sh tls`.

NewPipeExtractor (поиск/скачивание с YouTube/SoundCloud) — внешний риск, в коде не чинится.
