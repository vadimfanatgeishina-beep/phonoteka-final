# Команды и критерии проверки полного комплекта

> Обновление: текущие фактические результаты и оставшиеся ограничения — ACTUAL_VALIDATION.md и lab_reports/current_validation/. Старые заявления «не запускалось» ниже описывают момент подготовки предыдущего кандидата, не текущий итог; device acceptance и исторический baseline не считаются пройденными.

**Фактически выполненные команды/результаты — ACTUAL_VALIDATION.md. Остальные команды здесь являются планом, не свидетельством прогона.**
Результаты старого ZIP: JVM 68/68, lint 51 warning/0 error, APK собраны; отдельные device KeyStore/AES-GCM/TLS/HMAC прошли по сообщению владельца. Общий connected был незавершён. Не переносить эти числа на новый diff.

## 1. Интеграция и локальные проверки

Из корня проекта, JDK17 и SDK34. Bash; PowerShell: `./gradlew` → `.\gradlew.bat`, одна строка вместо `\`.

```bash
git apply --check Phonoteka-expanded-from-v3.patch  # либо expanded-after-priority / missing-after-full по своей базе, НЕ вместе
git apply Phonoteka-expanded-from-v3.patch
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest --rerun-tasks
./gradlew :app:dependencies --configuration releaseRuntimeClasspath
```

Или `bash tools/verify_local.sh`: сохраняет результаты в `.local/evidence`, останавливается при ошибке.
Если базой служит исходный source-candidate ZIP, diff уже не нужен.
Не запускать общий connected без fixtures. Release APK по умолчанию unsigned; успешная сборка не проверяет runtime R8/Reflection.

## 2. Java fixtures без Android

```bash
mkdir -p .local/java-fixtures
javac --add-modules jdk.httpserver -d .local/java-fixtures tools/LocalTlsPolicy.java tools/LocalLab15MockServer.java tools/LocalLab15MockServerTest.java tools/LocalLab15WssServer.java tools/LocalLab15WssServerTest.java tools/LocalLab15TlsDowngradeTest.java
java --add-modules jdk.httpserver -cp .local/java-fixtures LocalLab15MockServerTest
java --add-modules jdk.httpserver -cp .local/java-fixtures LocalLab15WssServerTest
```

Регрессии не открывают listener, не читают PKCS12, используют собственные сгенерированные ключи только в памяти.
PowerShell mkdir: `New-Item -ItemType Directory -Force .local/java-fixtures | Out-Null`.

## 3. Раздельные device-проверки

Локально выберите ANDROID_SERIAL своего устройства. Не передавайте значения PIN/ключей/серийного номера в чат.

```bash
adb -s "$ANDROID_SERIAL" get-state
adb -s "$ANDROID_SERIAL" shell input keyevent KEYCODE_WAKEUP
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.phonoteka.security.RefreshTokenStoreTest,com.phonoteka.data.LibraryBackupFileStoreTest,com.phonoteka.data.notes.AndroidNoteStorageTest,com.phonoteka.security.AccessBoundaryTest,com.phonoteka.data.LocalRoomCatalogContractTest --rerun-tasks
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.phonoteka.ui.Lab8AccessibilityTest,com.phonoteka.ui.Lab9PhonotekaUiTest --rerun-tasks
```

Unlock screen manually. Для `AuthBoundRefreshPolicyTest` сначала пройдите Android system-confirmation в приложении, затем немедленно:

```bash
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.phonoteka.security.AuthBoundRefreshPolicyTest --rerun-tasks
```

`RefreshTokenStoreTest` проверяет no-auth fixture в ОТДЕЛЬНОМ alias/prefs. Не считать его доказательством production key policy.
Сам факт `KeyInfo.isUserAuthenticationRequired` — статическая runtime-инспекция policy, не отрицательная проверка после истечения auth window.
Дополнительно вручную: повтор после 300 секунд без system authentication должен запросить её, отмена не уничтожает ciphertext/не открывает библиотеку.

После ЛР8 isolated theme test можно забрать только его PNG (не app data):

```bash
adb -s "$ANDROID_SERIAL" exec-out run-as com.phonoteka.labcheck cat files/lab8-theme-evidence/lab8-light.png > lab8-light.png
adb -s "$ANDROID_SERIAL" exec-out run-as com.phonoteka.labcheck cat files/lab8-theme-evidence/lab8-dark.png > lab8-dark.png
```

Bash binary redirection. В старом PowerShell используйте `cmd /c` для binary output, чтобы shell не перекодировал PNG.

## 4. Только собственный HTTPS/WSS стенд

У владельца должны быть локальные PHONOTEKA_MOCK_KEYSTORE, PHONOTEKA_MOCK_KEYSTORE_PASSWORD, PHONOTEKA_MOCK_HMAC_KEY, соответствующие debug pins. Значения не нужны в документах. Никаких настоящих сервисных секретов.
Для debug WSS ключ тестовой фикстуры берётся из локального env при сборке; пустой env оставляет экран отключённым.
Только публичный mock key допускается в debug APK. Release поле пусто.

Терминал A: `java --add-modules jdk.httpserver -cp .local/java-fixtures LocalLab15MockServer`.
Терминал B: `java --add-modules jdk.httpserver -cp .local/java-fixtures LocalLab15WssServer`.
После сообщений listeners:

```bash
adb -s "$ANDROID_SERIAL" reverse tcp:8443 tcp:8443
adb -s "$ANDROID_SERIAL" reverse tcp:8444 tcp:8444
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.phonoteka.security.LocalLab15TlsClientTest --rerun-tasks
adb -s "$ANDROID_SERIAL" reverse --remove tcp:8443
adb -s "$ANDROID_SERIAL" reverse --remove tcp:8444
```

В приложении: Настройки → Локальный WSS. Подключить, остановить fixture, проверить backoff/metrics, снова запустить fixture; после лимита reconnect нажать «Подключиться» заново.
Не менять глобальный Android proxy, не собирать чужой трафик. Certificate substitution тест использует только локальный MockWebServer и два генерируемых сертификата.
TLS downgrade: WSS listener разрешает TLS1.2/1.3; собственный Java loopback downgrade тест реально получил fatal protocol_version для TLS1.0/1.1; Android/WSS device evidence отдельно открыт. Отказ старого протокола только на клиенте НЕ доказывает server-side отказ.
В любом исходе удалить reverse и остановить оба процесса Ctrl+C.

## 5. Performance и Macrobenchmark

```bash
./gradlew -PwithBenchmarks=true :app:assembleBenchmark :benchmark:assembleBenchmark
./gradlew -PwithBenchmarks=true :benchmark:connectedBenchmarkAndroidTest
```

Benchmark signed DEBUG key, minified, not debuggable target; это НЕ production release подпись. Не устанавливать поверх важной библиотеки без собственного backup.
После compilation reset benchmark ждёт до 120 секунд: владелец вручную выполняет mock/system/PIN вход и открывает список. Auth bypass отсутствует; на неподготовленном устройстве тест обязан остановиться.
Измеряется scroll, не вход. Для «до/после» тот же device, одинаковая библиотека, повтор исходного проверенного commit. На пустом списке нет валидного scroll benchmark.
Layout Inspector: отдельно снять RECOMP tick, поиск, прокрутку, добавление/удаление. Заполнять только полученные counts. Compiler reports после реальной новой сборки взять из app/build/compose_reports и compose_metrics.

## 6. SAST/SCA/секреты

```bash
semgrep scan --validate --config tools/semgrep-security.yml
semgrep scan --config tools/semgrep-security.yml --json --output .local/evidence/semgrep.json app/src/main/java
gitleaks dir . --redact --report-format json --report-path .local/evidence/gitleaks-directory.json
# Только в настоящем репозитории владельца, если есть история:
gitleaks git . --redact --report-format json --report-path .local/evidence/gitleaks-history.json
python tools/package_submission.py .local/Phonoteka-safe-submission.zip
```

Отсутствующий scanner/невалидный config/ненулевой exit — не clean scan. Выводы redacted; не прикладывать приватные материалы.
Dependency inventory не является CVE-анализом. Для текущих публичных resolved Maven versions выполнен OSV lookup и triage jsoup; отчёты в current_validation. Shaded/непубличные artifacts и exploitability этим не закрыты. Не обещается «нет уязвимостей».

## 7. Ручные acceptance cases

| ЛР | Критерий | Ожидаемо | Фактический результат новой версии |
|---|---|---|---|
| 8 | TalkBack, theme persistence, compact/expanded | доступно/сохраняется | НЕ ПРОВЕРЕНО |
| 9 | STARTED timer/background, debounce, unsubscribe | таймер/подписки не продолжаются в фоне | НЕ ПРОВЕРЕНО |
| 10 | rotation/process death/detail id/pagination | нет stale/дубликатов, детали восстановлены | НЕ ПРОВЕРЕНО |
| 11 | SAF cancel/malformed/overwrite | отмена безопасна, ошибочный import не меняет catalog | НЕ ПРОВЕРЕНО |
| 11 PRO | note save/backup/trash/30d/v1 migration/draft/key loss | корректное восстановление/типизированная ошибка/подтверждение reset | НЕ ПРОВЕРЕНО |
| 13 | 4 ошибки mock/5m/system auth/PIN/recovery/biometric | lockout, код одноразовый, ключ защищён системой | НЕ ПРОВЕРЕНО |
| 13 | logout/process death/uninstall-like | revoked refresh не восстанавливает сессию | НЕ ПРОВЕРЕНО |
| 14 | denied VM/repository, forged scopes, tampered audit | fail-closed, ADMIN-only audit | НЕ ПРОВЕРЕНО |
| 14 | permission refusal/settings/deep-link direct invocation | объяснение/нет auth bypass | НЕ ПРОВЕРЕНО |
| 15 | correct/substitute pin, old/future/replay signature, WSS reflection/reconnect | reject/TTL/backoff соответственно | НЕ ПРОВЕРЕНО |
| 16 | minified runtime, manifest/backup/logs/upgrade | hardening не ломает app/нет утечек | НЕ ПРОВЕРЕНО |

Подлинный Room v1/v2 upgrade требует старой БД/schema владельца; новый generated schema не считается её заменой.
Исторические vulnerable/hardened tags создавать только после проверки реального исходного commit и текущего build; не подменять baseline новым ZIP.

Auth-bound instrumentation также использует отдельный UUID namespace (та же policy-конфигурация, НЕ рабочий refresh token/alias); тест не должен заменять действующую сессию. AccessBoundaryTest сохраняет ContextWrapper.applicationContext, чтобы tamper проверял только отдельные test prefs.

## Воспроизводимый SCA по публичным координатам

```bash
./gradlew :app:writeOwnedDependencyComponents --init-script tools/dependency-components.gradle
python3 -m unittest discover -s tools -p 'test_query_public_dependency_advisories.py'
python3 tools/query_public_dependency_advisories.py .local/evidence/release-components.json .local/evidence/osv-release.json
bash tools/run_security_scans.sh
```

OSV запрос отправляет только публичные Maven group/name/version. Нет отправки source/APK/ключей. Exit1 означает advisory version match, сетевой/форматный сбой также не считается clean scan. Нулевой результат не доказывает отсутствия уязвимостей. Scanner-secret проверяет только allowlisted submission; `history` запускается исключительно в настоящем репозитории владельца.
