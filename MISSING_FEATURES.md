# Дополнение: реализуемые пробелы, а не выдуманные результаты

> Обновление: текущие фактические результаты и оставшиеся ограничения — ACTUAL_VALIDATION.md и lab_reports/current_validation/. Старые заявления «не запускалось» ниже описывают момент подготовки предыдущего кандидата, не текущий итог; device acceptance и исторический baseline не считаются пройденными.

Это следующий source-only кандидат поверх Phonoteka-full-source-candidate. Компиляция/тесты/сканеры/устройство НЕ запускались. Синтетический Git commit в среде подготовки используется только для diff; это не исторический baseline проекта.

## Сделанные изменения

### ЛР13: риск-ориентированный step-up
- `app/src/main/java/com/phonoteka/security/RiskStepUp.kt`: monotonic session <300000ms; отдельное подтверждение sensitive action; challenge 120000ms; approval 60000ms, связанный с operation и новой authentication version.
- `security/LocalAuthManager.kt`: версия auth увеличивается только после успешной PIN/biometric/recovery и записи аудита; freshness не выдаётся при ошибке аудита.
- `PhonotekaApp.kt`: policy проверяется после role authority в repository gate; allowed role и denied step-up записываются отдельно. Debugger/background≥60s усиливают export/scan, но emulator не запрещён.
- `ui/components/RiskStepUpPrompt.kt`: PIN не сохраняется, Cancel ничего не выполняет. После подтверждения пользователь ПОВТОРЯЕТ действие — pending импорт/удаление не исполняется автоматически. Неверный PIN использует существующий lockout.
- Ресурсы: library import/clear, notes import/reset, audit export, invite create/export. UI/VM не могут закрыть gate скрытием диалога. Биометрия остаётся альтернативой базового входа; отдельный риск-диалог в этом варианте использует PIN.
- JVM: `RiskStepUpTest`, `AuthorizedNotesStepUpTest` — cancel/version/scope/TTL/recreation/direct repository denial. Это подготовленные тесты, не результаты.

### ЛР14: интегрированное приглашение и камера
- `InviteActivity.kt` — exported=false, FLAG_SECURE/overlay31+, проверки при входе и на каждом действии, закрытие при истечении локальной auth.
- `security/MockInviteAuthority.kt`, `AndroidInviteMac.kt`, `data/TrackInvites.kt`: mock ADMIN создаёт QR только для существующего track; SECURITY/ADMIN сканируют. HMAC-SHA256 KeyStore; digest issuance ledger; TTL≤5m; authoritative one-time consumption; подмена/повтор/отзыв/restart отклоняются.
- `ui/screens/TrackInviteScreen.kt`: создать, явно экспортировать QR через SAF на собственный второй экран, прочитать камерой; нет plaintext key/token логов. Экспортированное приглашение не ключ, но его всё равно не следует публиковать; оно действует лишь в этом процессе до истечения/первого использования.
- `ui/components/OwnedQrScanner.kt`: CameraX back camera, локальный ZXing, закрытие каждого ImageProxy, KEEP_ONLY_LATEST, bounded image, dispose закрывает только свои use cases; кадры не сохраняются/не уходят в сеть.
- `ui/permissions/*`: reusable on-demand rationale/deny/settings/grant flow. CAMERA добавлена потому, что функция теперь есть; camera hardware необязателен, широких storage permissions нет.
- `viewmodel/TrackInviteViewModel.kt`: отказ не оставляет QR/track receipt; generation/cancel защищает от позднего ответа после role change.
- JVM: `MockInviteAuthorityTest`, `OwnedQrEncodingTest`, `PermissionFlowPolicyTest`, `TrackInviteViewModelTest`. Instrumentation: `InviteManifestContractTest`. Реальный permission/camera/adb ещё проверить.
- Это музыкальное приглашение, а не реальный пропуск организации. Authority локальна, не защищает от модификации собственного процесса/root. Два разных устройства не разделяют ledger; второе устройство только ПОКАЗЫВАЕТ экспортированный QR, сканирует и проверяет исходное приложение.

### ЛР15: server-side downgrade regression
- `tools/LocalTlsPolicy.java` общая policy TLS1.2/1.3 для HTTPS и WSS.
- `tools/LocalLab15TlsDowngradeTest.java`: свои ephemeral loopback listeners, позитивные TLS1.2/1.3; raw TLS1.0/1.1 ClientHello и обязательный ответ сервера fatal protocol_version. Client-only refusal, EOF, другой alert — НЕ pass. Не меняет global disabledAlgorithms/proxy/CA.
- Использует только PKCS12 своего fixture через локальный env; значения/приватные материалы не нужны в чате. Test executable подготовлен, реальные wire evidence отсутствуют до запуска.

### ЛР8/9/16: проверяемость оставшихся пунктов
- `SettingsViewModel.kt` DataStore injection; `SettingsPersistenceTest.kt` открывает только temporary store и проверяет новое VM/store после записи theme/forceDark.
- Debug-only RecompositionProbe и экран окна измерения: фактические aggregate committed compositions (включая initial composition), без state feedback и без пользовательских названий/ID/ключей. Release/benchmark — no-op. CSV не является CPU/FrameTiming или Layout Inspector.
- `tools/summarize_compose_reports.py` извлекает до трёх действительно unstable функций из НОВОГО composables.txt; если их меньше, ничего не выдумывает.
- `ModeledVersion2MigrationTest.kt` проверяет существующую 2→3 migration на явно КОНТРОЛИРУЕМОЙ v2 SQLite fixture с текущими track/candidate определениями. Это НЕ восстановление неизвестной исторической schema, НЕ доказательство всех старых установок и НЕ migration1→2.
- `tools/run_device_acceptance.sh`: bounded runner, раздельные группы, own-device wake, без PIN bypass; reverse cleanup, не перезаписывает чужую reverse mapping.
- `tools/run_security_scans.sh`: проверяет наличие инструментов, пишет версии и redacted reports, missing/nonzero — не clean. Не заменяет CVE-анализ.

## Интеграция

1. Если уже применён прошлый **FULL** комплект, используйте только `Phonoteka-missing-after-full.patch`.
2. Если база — исходный приложенный v3, используйте только `Phonoteka-expanded-from-v3.patch`.
3. Если база — v3 + пять priority fixes (но НЕ full), используйте только `Phonoteka-expanded-after-priority.patch`.
4. Либо откройте `Phonoteka-expanded-source-candidate.zip`, там все изменения уже есть.

Не смешивать diff. `git apply --check <chosen.patch>`, затем `git apply <chosen.patch>` в отдельной ветке. Commit ID подготовки не подменяет пользовательские vulnerable/hardened tags.

## Всё ещё нельзя закрыть подготовкой исходников

- Реальные TalkBack/SAF/CameraX/rotation/biometric/process death и minified runtime.
- Новые JVM/lint/APK/connected результаты, сканы, before/after performance и Layout Inspector.
- Подлинные старые Room schema/DB, baseline commit, исторические tags.
- Реальный identity backend не создаётся: исходные материалы требуют учебную локальную вертикаль, пользователь запрещает настоящие аккаунты/секреты. Если преподаватель отдельно назначил Ktor/иной PRO backend, это отдельное требование, не скрывается локальным mock.

См. MISSING_VERIFICATION.md. Никакой новый APK, лог device/scan, CSV/скриншот или успешный тест не сфабрикован.

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
