# Реально выполненные проверки текущего кандидата

> Verified baseline ДО draft-дополнения. Новый FOLLOWUP_DRAFTS.md / lab_reports/draft_followup/RESULTS.md имеют отдельные результаты; старый release/scanner/device итог не наследуется новым кодом.

Это дополнение к предыдущему expanded source candidate, не полный зачёт ЛР8–16. Старые68/68/Android13 факты относятся только к v3. Следующие результаты получены здесь на изменённой версии, не взяты из старых отчётов.

## Короткий triage и применённые правки

1. **WSS terminal shutdown** — `app/src/main/java/com/phonoteka/security/LocalLiveSocket.kt`: untrusted frame немедленно отменяет transport, а `onClosing` больше не завершает coroutine до завершения handshake. Регрессии в `app/src/test/java/com/phonoteka/security/LocalLiveSocketTest.kt` отвечают на close handshake, unwinding выполняется до blocking shutdown; ошибки cleanup НЕ проглатываются. Реальный первый прогон нашёл1 failure; неверная попытка server-side cancel дала2 fixture failures, финальная правка прошла2/2 и общий112/112.
2. **Две SCA version-match записи для jsoup1.22.2** — `app/build.gradle.kts` строго обновляет транзитивный parser до1.23.2. Повторная реальная compilation/R8 packaging прошла. Version lookup до/после и applicability limits — `lab_reports/current_validation/SCA_TRIAGE.md`.
3. **Воспроизводимость** — реальный Room schema3 экспортирован компилятором в `app/schemas/com.phonoteka.data.PhonotekaDatabase/3.json`; это НЕ выдуманная historical v1/v2 schema. Gradle wrapper получил executable mode. Добавлены `tools/dependency-components.gradle`, `tools/query_public_dependency_advisories.py` и6 offline Python tests; scanner script проверяет безопасный allowlisted archive вместо work/credentials/cache и отключает Semgrep telemetry.

## Подтверждённые результаты

| Проверка | Фактический итог | Граница |
|---|---|---|
| JVM112 тестов/27 классов | 112 passed,0 failed/error/skipped | JDK17, не Android instrumentation |
| Python SCA helper | 6/6 offline tests | mocks API, не сетевой security proof |
| lintDebug | 58 Warning,4 Information,0 Error | предупреждения не спрятаны; triage приложен |
| Debug APK | assembleDebug PASS; apksigner verify PASS | debug-only labcheck build; fixture env пустой |
| Release APK | assembleRelease PASS, minifyReleaseWithR8 + shrink resources выполнены | unsigned, R8 runtime НЕ проверен |
| AndroidTest APK | assembleDebugAndroidTest PASS; apksigner verify PASS | скомпилирован, не равнозначно device test PASS |
| Java HTTP nonce / Java WSS frames | standalone regressions PASS | только owned fixtures/local keys in memory |
| Java TLS protocol negatives | TLS1.2/1.3 handshake accepted; TLS1.0/1.1 server fatal protocol_version | фактический loopback server refusal, не только client policy отказ |
| Optional Macrobenchmark Kotlin | compileBenchmarkKotlin PASS,9 tasks/44s | не target benchmark APK/runtime measurements |
| Compose Compiler | реальные debug reports/metrics приложены | не runtime RECOMP/CPU/историческое before-after |
| Manifest merger | фактические debug/release component/security summaries | direct adb/runtime acceptance отдельно |
| Semgrep | 2 rules,78 main source files,0 findings/errors | узкий custom SAST, не comprehensive scan |
| Gitleaks | allowlisted source archive,0 findings | не original work/secret ZIP и не real Git history |
| OSV debug package graph | 136 coordinates,2 matches before →0 after | публичные version matches, не exploitability |
| OSV release graph | 167 resolved public coordinates,0 matches | не shaded libraries/полная гарантия отсутствия CVE |

Полная групповая Gradle-команда завершилась `BUILD SUCCESSFUL in 14m55s`; реальные отчёты/классы/cases/counts — `lab_reports/current_validation/`. Source package не включает `.local`, original work/, ключевые контейнеры, SDK/Gradle caches, .git или чужую историю. APK hashes — `apk-summary.json`. Время наблюдения в machine reports — часы build environment; это не намеренно придуманное время device evidence.

## Что эти проверки действительно закрывают

- ЛР9: новый Compose Compiler artifact готов; runtime до/после пока нет.
- ЛР10: detail stale/load/Room-flow VM contract, retry и pagination guards получили JVM regression evidence. Настоящие Android rotation/process-death/offline UI открыты.
- ЛР11 PRO: все6 DI/temp-dir repository regressions прошли (revision/backup/trash/v1 import/draft/tamper/failed index write). Android AtomicFile/KeyStore/SAF device-сценарии открыты.
- ЛР13: 5m mock lockout, digest ledger, failed-rotation cleanup/preflight/process recreation подтверждены7 JVM tests;4 risk step-up tests тоже прошли. PIN/system biometric/auth-bound300s и hardware persistence не подменяются этими JVM результатами.
- ЛР14: mock role/invite/QR/permission policy и late response VM regressions прошли. Camera/rationale/settings, closed-Activity ADB, SAF и реальный one-use scan на своём экране ещё требуют устройства.
- ЛР15: HTTP/WSS future timestamp inclusive retention и bounds/overflow/replay, pin/substitution и directional reflection/reconnect имеют JVM/Java результаты. Это не новый Android TLS fixture acceptance.
- ЛР16: новый lint/manifest/minified APK/source scanner/OSV evidence получен. Подлинные baseline tags, настоящая old DB migration и minified runtime остались открытыми.

## Команды повторной проверки

Из корня, JDK17/SDK34, Gradle wrapper. Выберите только один соответствующий patch по своей базе.

```bash
chmod +x gradlew
unset PHONOTEKA_MOCK_HMAC_KEY
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest :app:writeOwnedDependencyComponents --init-script tools/dependency-components.gradle --no-daemon --max-workers=1 -Pkotlin.compiler.execution.strategy=in-process
python3 -m unittest discover -s tools -p 'test_query_public_dependency_advisories.py'
python3 tools/query_public_dependency_advisories.py .local/evidence/release-components.json .local/evidence/osv-release.json
bash tools/run_security_scans.sh
```

`--rerun-tasks` можно добавить для принудительного повторения всех задач; фактический выше итог содержит83 executed/5 cache/52 up-to-date tasks — это не скрывается. JVM test task реально executed.

Java exact compile/run и bounded device modes — RUN_MATRIX.md/MISSING_VERIFICATION.md. Для server-side downgrade нужны только свой локальный ephemeral PKCS12 и локальный password env; значения не помещать в argv/чат/отчёт. В этом прогоне такой контейнер был создан только для своего loopback стенда и удалён; не входит в submission.

## Что нельзя честно объявить закрытым

- Все device UI/TalkBack/theme persistence/biometric/SAF/camera/permission/deep-link/process-death/risk-stepup/end-to-end сценарии, а также minified release runtime.
- Исторический Layout Inspector/RECOMP/производительность before-after и Macrobenchmark measurements; debug counters/source не превращены в результаты.
- Подлинный Room historical upgrade: current generated schema3 и controlled modeled2 fixture НЕ заменяют отсутствующую настоящую old schema/DB.
- Authentic vulnerable/hardened Git tags: исходный repository commit отсутствует в ZIP; синтетическая preparation history не выдаётся за него.
- Настоящие identity/backend/public API E2E, если требуется буквальное отдельное задание: учебная mock/local адаптация им не подменяется. Настоящие аккаунты/секреты/чужой трафик не использовались.

Окончательный результат попытки собственного AVD записан отдельно в lab_reports/current_validation/DEVICE_ATTEMPT.md; отсутствие boot/test result — не pass. Общий connected не запускается без соответствующих fixtures/preconditions.
