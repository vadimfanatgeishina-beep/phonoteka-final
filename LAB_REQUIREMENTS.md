# ЛР8–16: атомарная сверка полного комплекта-кандидата

> Актуальный этап: FINAL_STEP3.md / lab_reports/final_step3/RESULTS.md. Предыдущие отчёты исторические; Android acceptance не наследуется.

> Текущий этап: FINAL_STEP2.md / lab_reports/final_step2/RESULTS.md. Предыдущие evidence относятся к предыдущим снимкам; device acceptance не наследуется.

> Актуальный этап финального приложения: FINAL_STEP1.md / lab_reports/final_step1/RESULTS.md. Таблица ниже — историческая сверка прежних снимков; новые проверки не закрывают device/historical критерии автоматически.

> Последующее дополнение: FOLLOWUP_DRAFTS.md / lab_reports/draft_followup/RESULTS.md. Device/historical критерии остаются открытыми.

> Обновление: текущие фактические результаты и оставшиеся ограничения — ACTUAL_VALIDATION.md и lab_reports/current_validation/. Старые заявления «не запускалось» ниже описывают момент подготовки предыдущего кандидата, не текущий итог; device acceptance и исторический baseline не считаются пройденными.

Дата исходной подготовки: 2026-10-08. Обновлены фактические локальные результаты: ACTUAL_VALIDATION.md. JVM/build не заменяют Android acceptance.
Статусы: **готово** — документ/артефакт подготовлен; **частично** — код/тест подготовлен, критерий закрыт не полностью; подтверждённые JVM/build части отмечены отдельно, runtime не подразумевается; **не сделано** — нужных данных/артефактов нет; **нужно проверить на устройстве** — требует фактического device acceptance.
Прежние результаты 68 JVM и отдельных Android 13 сценариев относятся к v3; не наследуются изменённым кодом.
Пути ниже относительно корня. Полные команды и ожидаемые результаты — RUN_MATRIX.md.

| ЛР/уровень | Атомарный пункт | Статус | Артефакт / незакрытая граница |
|---|---|---|---|
| 8 база | M3 light/dark/dynamic/typography/shapes | частично | ui/theme/PhonotekaTheme.kt; debug Kotlin/ресурсы скомпилированы; device visual acceptance открыт |
| 8 база | AppButton enabled/styles/sizes + ProfileCard slots | частично | ui/components; disabled-click regression в Lab8AccessibilityTest |
| 8 база | loading/error semantics/live region/48dp | нужно проверить на устройстве | StatusButton/FormField/TrackCard; TalkBack и hit targets |
| 8 расширение | SYSTEM/LIGHT/DARK, forceDarkContent, persistence | нужно проверить на устройстве | SettingsViewModel / theme UI; смена темы test-компонента не доказывает persistence приложения |
| 8 расширение | compact vs expanded | частично | HomeScreen + оба semantics tags в UI-тесте; нужны реальные ширины |
| 8 база | light/dark preview/design document | готово | ui/Previews.kt, DESIGN_SYSTEM.md; runtime PNG генерируются только успешным тестом |
| 8 база | AppButton + три UI-теста | частично | androidTest/ui/Lab8AccessibilityTest.kt; device-прогон не завершён для новой версии |
| 9 база | три семейства анимаций и label | частично | TrackCard/SplashScreen; runtime не проверен |
| 9 база | debounce/cancel/autosave | частично | TrackListViewModel + controlled gate в TrackListScreen/тестах |
| 9 база | subscribe/dispose/lifecycle STARTED timer | частично | lifecycle collection, repeatOnLifecycle; background acceptance открыт |
| 9 база | четыре поведенческих UI-теста без sleep | частично | Lab9PhonotekaUiTest; новый прогон нужен |
| 9 база | реальные Layout Inspector/RECOMP до и после | не сделано | нет runtime counts/screenshots; LAB9_PERFORMANCE.md — процедура |
| 9 база | current compiler reports | готово | lab_reports/current_validation/compose/ и unstable-functions.md — реальная текущая сборка; НЕ Layout Inspector/runtime counts |
| 9 PRO/доп. | Macrobenchmark | частично | benchmark/; compileBenchmarkKotlin реально PASS; целевая benchmark APK/runtime measurements не получены |
| 10 база | Retrofit/Gson/Repository→VM→UI/3 состояния/debug logging | частично | PostCatalogApi/RoomPostCatalogRepository/PostCatalogViewModel |
| 10 база | retry после network error/rotation | частично | retry 1/2/4s; actual rotation открыт |
| 10 база | детали обновляются после refresh | частично | combine Room flows, generation guard; JVM regression и local Room contract |
| 10 PRO | пагинация без concurrent offset race | частично | pageMutex, VM guard; нужны реальные быстрые прокрутки |
| 10 PRO | Room offline-first/typed errors/DI/local search cancellation | частично | repository интерфейс, CatalogApiError, query flatMapLatest |
| 10 PRO | SavedStateHandle query/detail id + VM tests | частично | PostCatalogDetailFlowTest/CatalogRetryTest; настоящая process death не проверена |
| 10 доп. | repository E2E на настоящем публичном API | не сделано | вместо внешнего API подготовлен owned HTTPS+Room contract; не выдаётся за real-API E2E |
| 11 база | filesDir JSON backup/IO/atomic/recovery | частично | LibraryBackupCodec/FileStore; AtomicFile + interrupted-write tests |
| 11 база | SAF import/export/cancel/corruption/overwrite | нужно проверить на устройстве | Lab11BackupScreen; IO перенесён с main thread |
| 11 база | CRUD/autosave/per-launch data | частично | адаптация фонотеки + per-track notes; не буквальное отдельное приложение |
| 11 PRO | one encrypted revision per note + HMAC manifest | частично | data/notes/FileTrackNotesRepository, KeystoreNoteCrypto |
| 11 PRO | corrupt revision → previous backup | частично | backup/ revisions, NoteRead.recovered; fallback тест подготовлен |
| 11 PRO | trash/restore/30-day cleanup | частично | manifest trash + purgeTrash; JVM tests |
| 11 PRO | schemaVersion2/v1 migration/import validation | частично | notes export/import, 5MiB/10000 limits; base library backup остаётся v1 |
| 11 PRO | encrypted draft/cache + SavedStateHandle/search300ms | частично | TrackNotesViewModel/Repository; нет write debounce; только ID в SavedStateHandle; SAVED после IO, kill ДО acknowledgement всё ещё может потерять ввод |
| 11 PRO | KeyUnavailable typed/reset with confirmation | частично | NoteError + confirmed UI reset; hardware key loss не проверен |
| 11 PRO | ≥4 repository JVM tests/temporary dir/crypto DI | готово | app/src/test/java/com/phonoteka/data/notes/TrackNotesRepositoryTest.kt: 6/6; это только JVM temporary-dir/DI, не Android KeyStore |
| 12 база | assets/boundaries/STRIDE/DREAD/tree/countermeasures | готово | THREAT_MODEL_LAB12.md; структура подготовлена, не доказательство защиты |
| 12 PRO | v2-diff/3 own attack plans/detection/ALE/MASVS | готово | LAB12_MODEL_V2.md, ATTACK_PLANS_LAB12.md; ALE — гипотезы |
| 12 расширение | модель v3 новых механизмов и рисков | готово | LAB12_MODEL_V3.md |
| 12 PRO | фактическое выполнение attack plans | нужно проверить на устройстве | планы не результаты; только свой APK/fixture |
| 13 база | первый mock password → PIN setup | частично | AuthGateScreen; system auth отдельна от PIN |
| 13 база | 4 ошибки mock/5m/countdown | частично | LocalMockAuthEndpoint + Gate/Lab13MockAuthScreen; JVM тест default TTL |
| 13 база | PIN HMAC/lockout/recovery8chars/одноразовость/newPIN | частично | LocalAuthManager; recovery требует также успешный mock login в gate |
| 13 база | strong biometric CryptoObject и callbacks | нужно проверить на устройстве | MainActivity + LocalAuthManager; enrollment/cancel/error/rotation |
| 13 база | refresh auth-bound KeyStore/access memory-only | частично | production AES alias auth-required300s; no-auth test fixture отделён |
| 13 база | rotate/revoke/failed persistence/process death/uninstall | частично | MockSessionRepository + digest ledger + tests; настоящий device death открыт |
| 13 база | step-up ≤5m/monotonic time | частично | in-memory elapsedRealtime freshness; global gate + detail step-up |
| 13 база | auth audit/FLAG_SECURE/overlay/backup/log hygiene | частично | signed auth snapshot; overlay31+, secure windows; tests/audit acceptance открыт |
| 13 доп. | риск-ориентированный step-up | частично | RiskStepUp.kt + repository gates + PIN dialog; version/operation-bound60s; RiskStepUpTest: 4/4 JVM; реальные step-up UI/system confirmation открыты |
| 13 доп. | отдельно назначенный real identity/Ktor backend | не сделано | исходная локальная вертикаль не требует реальных аккаунтов; настоящий backend не выдаётся за mock |
| 14 база | server-assigned opaque mock grant/scopes | частично | MockRoleAuthority; роли только debug fixture, не production JWT |
| 14 база | UI/VM/repository checks | частично | LocalDemoPermissions, Track*ViewModel, PhonotekaRepository; direct-call tests |
| 14 база | first rationale/refusal/settings permission flow | нужно проверить на устройстве | Lab14AccessScreen / POST_NOTIFICATIONS |
| 14 база | ADMIN-only original audit/tamper detection | частично | AccessAuditRepository signed snapshot; rollback валидного snapshot не обнаруживается |
| 14 база | exported/deep link/direct adb call | нужно проверить на устройстве | MainActivity validation + auth gate; direct call evidence отсутствует |
| 14 адаптация | InviteActivity/camera/QR invitation | частично | закрытая InviteActivity, on-demand CameraX/ZXing, signed one-use track invitation; реальный camera/permission/adb открыт |
| 14 PRO | SAF HMAC audit export | частично | export signer; SAF UI runtime не подтверждён |
| 15 база | only owned local TLS fixture/debug pins/release system CA | частично | debug NSC/client, release stub; unused main cert удалён |
| 15 база | request HMAC/query/body/time/nonce | частично | localhost-only/64KiB signing; canonical в коде; tests prepared |
| 15 база | future retention/invalid/stale/replay | частично | Kotlin HTTP/WSS + Java standalone regressions |
| 15 база | correct/wrong/substitute certificate | частично | LocalHttps/LocalLab15TlsClient/OwnedCertificateSubstitution tests; изменённый код не прогонялся |
| 15 база | WSS/reconnect/backoff/frame signatures/metrics | частично | LocalLiveSocket, debug screen, LocalLab15WssServer; new channel + direction binding |
| 15 доп. | TLS1.0/1.1 server downgrade regression | частично | LocalTlsPolicy + raw ClientHello test; реальный собственный Java loopback: TLS1.2/1.3 handshake PASS; TLS1.0/1.1 fatal protocol_version PASS; log в current_validation |
| 16 база | ≥5 находок/triage/baseline reproduction | частично | исторический v3 audit сохранён; настоящий eadf4b0 отсутствует в ZIP |
| 16 база | lint/SCA/manifest merger/secret scans | частично | scripts/rules/inventory; current_validation содержит реальный lint, manifest summaries, узкий SAST/secret scan и OSV version matching; это не исчерпывающий vulnerability/device audit |
| 16 база | R8/backup-off/cleartext-off/diagnostic hygiene | частично | code/build/manifest; minified runtime не подтверждён |
| 16 база | downloader negative tests | частично | size/chunked/playlist/HTTPS→HTTP tests; AudioDownloaderLimitsTest: 2/2 JVM; device downloader acceptance открыт |
| 16 база | Room upgrade без потери данных | частично | modeled2→3 fixture test подготовлен; подлинная old schema/DB НЕ предоставлена, actual historic upgrade открыт |
| 16 база | dynamic adb/backup/logcat/own MITM evidence | нужно проверить на устройстве | source-only preparation, no fabricated logs |
| 16 база | RASP advisory + protected source/APK delivery | частично | runtime signals и safe source ZIP; новые debug/minified unsigned release/androidTest APK реально собраны; protected runtime не подтверждён |
| 16 доп. | SAST token log rule/CI | частично | semgrep-security.yml/.gitlab-ci.yml; Semgrep: 2 правила/78 main файлов/0 находок; Gitleaks allowlisted source: 0 находок; настоящий CI runner/история не проверены |
| 16 база | authentic vulnerable/hardened tags | частично | изолированные реконструкции F-01…F-06 в tools/lab16 (26 проверок, реально запущено Java 25); подлинного commit eadf4b0 нет |

Все Java/Kotlin пути без префикса ui/viewmodel/security выше находятся в app/src/main/java/com/phonoteka/; test/androidTest — в соответствующих source sets. Конкретные полные пути новых файлов видны в unified diff.

Фактическое дополнение: ACTUAL_VALIDATION.md / lab_reports/current_validation. Частичный статус не означает пройденную лабораторную; не подтверждённые device/historical критерии остаются открытыми.

## Этап 13
- ЛР11: notes.json → kotlinx.serialization — сделано
- ЛР13: уведомление о recovery, экраны заказы/результаты за step-up — сделано
- ЛР14: exported InviteActivity, scopes pass:view/scan:execute/audit:read, пропуск, regex token, PERMISSIONS_LAB14.md — сделано
- ЛР16: F-07 WebView, F-08 clipboard — сделано (см. FINAL_STEP13.md)

## Этап 14 — дополнительные пункты
- ЛР10 доп.: E2E репозитория на настоящем публичном API — RealPublicApiE2ETest (androidTest, флаг realApi=true); нужен прогон на устройстве с интернетом
- ЛР13 доп.: отдельный identity-backend — tools/backend/IdentityServer.java (login/refresh-ротация/обнаружение повтора/logout/scopes/блокировка перебора), IdentityServerTest: 34/34 реально PASS; Android-клиент RemoteIdentityClient + RemoteIdentityClientTest
- ЛР16 доп.: CI для GitHub — .github/workflows/android-security.yml (Semgrep, Gitleaks, стенды, unit/lint); запуск после push
- ЛР14: внутренний navDeepLink phonoteka://details удалён
