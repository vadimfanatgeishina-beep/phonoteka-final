# Фактическая проверка финального приложения — этап 1

Источник: safe drafts-source snapshot. Это НЕ завершение всех ЛР8–16 и НЕ подлинная vulnerable история.

## Реально выполнено в этой рабочей среде

- Полный итоговый Gradle прогон: **BUILD SUCCESSFUL**. Команда ниже; исходный log `final-build.txt`.
- JVM: **162/162**, failures0/errors0/skipped0, классов35. Все имена/количества: `jvm-results.json`.
- Lint debug: **58 Warning / 4 Information / 0 Error/Fatal**. Полный XML и summary приложены; warnings не скрыты и не объявлены исправленными.
- Kotlin androidTest скомпилирован; debug APK, debug androidTest APK, minified unsigned release APK собраны. SHA256/размеры: `artifact-hashes.json`.
- Собственный Java localhost HTTPS/WSS regression: accepted,409Replay,401Signature/Clock, invalid signature не consumes nonce, future-timestamp retention, WSS checks; TLS1.2/1.3 handshakeaccepted и TLS1.0/1.1 serverfatal protocol_version. Log `owned-tls-contract.log.txt`; никакого чужого трафика.
- Owned JVM primary+backup pins положительные, third trusted-but-unpinned отрицательный: два теста включены в162. Не Android NSC/actualCER rotation evidence.
- QR regression:32 independent own random signed fixtures за один тест. До исправления TRY_HARDER полный прогон имел161PASS/1FAIL; сохранён `initial-final-run-FAILED.txt`. Затем focusedQR PASS, затем именно ПОЛНЫЙ162 PASS. Не скрывается первый сбой; не выключены тесты, нет PURE_BARCODE обхода детектора.

```bash
./gradlew :app:testDebugUnitTest :app:lintDebug \
  :app:compileDebugAndroidTestKotlin :app:assembleDebug \
  :app:assembleDebugAndroidTest :app:assembleRelease \
  --no-daemon --max-workers=1 -Pkotlin.compiler.execution.strategy=in-process
python tools/run_owned_tls_contract.py
```

JDK17 / SDK34 / Gradle8.10.2. Для воспроизведения нужны свои разрешённые toolchain/downloads.

## Не запускалось / не закрыто

Ни emulator, ни connected Android в этой среде нет. Новые4 UI negatives и fresh256bit KeyStore case только скомпилированы, не runtimePASS. Camera/SAF/TalkBack/process-kill/biometric/actual AndroidNSC/real rotation/minified runtime/mitmproxy не выполнялись. Пользовательские прежние68/68 и Android13 относятся к старомуZIP, не перенесены на новыйкод.

Release unsigned, debug signing текущей среды может отличаться. Не делать uninstall рабочего приложения с данными. Остаток реализации и точные файлы: `FINAL_STEP1.md`; точные device команды: `ANDROID_ACCEPTANCE.md`.

Изменения разработаны как адаптация Phonoteka; независимые notes/autocommit при уходе, ЛР13 real biometric decrypt refresh, ЛР14 append-only JSONL и прочие перечисленные там literal критерии ещё не выполнены.

В передаваемых текстовых логах нормализованы локальный путь `<project>` и пробелы на концах строк; исходные результаты/диагностика сохранены. Debug и androidTest APK также реально проверены `apksigner verify`; unsigned release не объявляется подписанным.
