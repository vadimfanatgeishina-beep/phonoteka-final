# Ещё не выполненные Android проверки этого этапа

Новые instrumentation tests только компилируются/собираются; ни одного connected запуска в этой среде нет. Старые пользовательские факты Android13 относятся к старому ZIP, не распространяются автоматически на этот кандидат.

## Собственная тестовая установка, без потери данных

Debug applicationId имеет суффикс `.labcheck`. Ключ подписи рабочего APK может отличаться от ключа предыдущего APK. Не делать uninstall приложения с нужными данными ради установки этого кандидата. Использовать отдельный собственный AVD либо собрать исходники своим прежним debug/signing процессом. Release APK unsigned.

## Заметки / UI / KeyStore

```bash
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.phonoteka.ui.NotesConfirmationUiTest,com.phonoteka.ui.TrackNotesDurabilityUiTest,com.phonoteka.data.notes.AndroidNoteStorageTest \
  --rerun-tasks
```

Критерии: cancel не изменяет notes/trash; confirm вызывает ровно одну mutation; damaged нельзя открыть/экспортировать как валидную; healthy доступна рядом с damaged; save draft ack только после IO; новая AES key256/non-auth/разные IV. Component fake тест не заменяет auth integration.

Ручной сценарий: существующий own track → заметка → изменение текста → сохранение → process kill → повторный запуск; затем trash/restore; создать две own notes и повредить current+previous одной только в own test sandbox; healthy row остаётся. SAF invalid/legacy/cancel/overwrite; восстановленная revision реально читается после второго запуска. Не подменять runtime проверку наличием кода.

## Локальный TLS/WSS

Сначала собственный fixture по RUN_MATRIX.md, затем adb reverse8443/8444. Не запускать весь connected набор без fixture. Не печатать значения signing key и не передавать реальные аккаунты.

```bash
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.phonoteka.security.LocalLab15TlsClientTest \
  --rerun-tasks
```

Критерии: correct pin200; повтор409 Replay; tampered body/signature401 Signature; stale401 Clock; invalid signature не сжигает nonce; trusted-but-wrong pin отклоняется конкретно как pin failure. Android actual primary→backup rotation, offline10s/reconnect и NSC/testCA ещё требуют отдельных устройствных сценариев; JVM fixture evidence их не заменяет.

## Остальная финальная версия

Следующие отдельные реализации: ЛР13 настоящий biometric CryptoObject decrypt refresh→session restore, ЛР14 append-only actor/subject security JSONL, literal profile/TaskTracker критерии8/9, независимые notes+active autosave11, user-bound ticket WSS15, isolated vulnerable/hardened16. Подлинные старые schema/commit/динамические evidence нельзя изобрести.
