# Фактические результаты draft-дополнения

База — Phonoteka-verified-source.zip; все цифры ниже для новой версии, не перенос старых112 результатов.

- Полный JVM suite: **134 tests /29 classes;0 failures,0 errors,0 skipped**.
- Новые JVM cases: **22/22** (TrackNotesDurabilityTest15 + DraftStorageMigrationTest7).
- До fix LocalWssMockServerTest: реальный полный прогон134 с1 IOException cleanup timeout. Этот результат сохранён как previous-attempt-failure.json, не скрыт.
- После fix: полный134/134 и **два отдельных WSS repeat по1/1**. Repeat scope не суммируется как136 уникальных tests. Оба peers подтверждают close и оба onClosed ожидаются до shutdown; ошибка cleanup не игнорируется.
- lintDebug: **58 Warning,4 Information,0 Error**; все предупреждения в lint-summary.json.
- Debug APK, AndroidTest Kotlin и AndroidTest APK: **сборка PASS**. apksigner verify обоих current debug APK: **PASS**.
- Новые3 UI и1 KeyStore instrumentation cases **скомпилированы, но НЕ запускались на Android**. Это не подтверждает UI/биометрию/KeyStore/forced kill/power-loss на устройстве.
- Current Compose Compiler reports/metrics приложены в compose/; это не runtime performance before/after.
- Новый release/R8/resource shrinking/APK packaging: **PASS**, BUILD SUCCESSFUL in7m3s. Это новая доработанная версия, APK unsigned; minified runtime на Android НЕ проверен. Exact command/tail — release-result.txt.
- Новые SAST/Gitleaks/SCA/CI/Java fixtures здесь не запускались. Старые отчёты current_validation относятся к verified baseline. No scan claims inherited.

Полная команда/debug build tail — gradle-final-result.txt. Исходники/tests/criteria/точные команды — FOLLOWUP_DRAFTS.md. Full-suite summary сохранён ДО focused repeats, иначе junit directory содержит только1 selected test; это учитывается.

## Android/signing ограничения

Своя рабочая среда была восстановлена из safe source ZIP; private signing keys в архив не переносились. Совместимость нового debug certificate с ранее установленным .labcheck APK не подтверждена. Не удалять установленное приложение/данные ради sideload. Безопаснее собрать локально со своим прежним signing process либо тестировать на отдельном собственном пустом emulator. Production signing keys не запрашиваются и не прикладываются. Release (если собран) unsigned и не install-ready production artifact.

До IO acknowledgement последний ввод всё ещё может потеряться. Теперь plaintext saved-state не используется как альтернативное хранилище/автоматическая команда записи. Already committed ciphertext сохраняется при confirmed discard; explicit reset по-прежнему отдельное подтверждённое destructive действие.
