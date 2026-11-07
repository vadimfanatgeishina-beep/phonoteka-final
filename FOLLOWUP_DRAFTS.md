# ЛР11 PRO: подтверждаемая запись черновиков — дополнение к verified source

База: ранее выданный `Phonoteka-verified-source.zip`. Наша локальная preparation Git history НЕ подлинная история проекта. Новый итог — `lab_reports/draft_followup/RESULTS.md`; предыдущие112/112/release/scanner reports не наследуются изменённым исходникам.

## Что изменено

- `app/src/main/java/com/phonoteka/viewmodel/TrackNotesViewModel.kt`: нет искусственного write debounce400ms. Один writer, immutable snapshots, coalescing по ID и лимит32 pending/failed IDs. Старое завершение не подтверждает новую правку. `SAVED` только после возврата `saveDraft`; ошибка остаётся FAILED. Retry сохраняет исходный snapshot. Save/delete/edit/import/back ждут IO barrier; reset ждёт захваченный IO, не сбрасывает ключи под продолжающейся записью.
- Там же: только ID в SavedStateHandle. Title/text/pinned не сериализуются в plaintext Android bundle. Старые поля удаляются и не становятся командой записи/не перекрывают ciphertext. Восстановление только из repository. Confirmed discard очищает volatile edits, не удаляет сохранённые файлы/ключи и не обходит права.
- `app/src/main/java/com/phonoteka/data/notes/TrackNotesRepository.kt`: fsync+tmp/rename, игнорирование interrupted tmp, проверка existing track ID. Проверяются failures удаления. Legacy drafts переносятся при list/read: decode/validate → durable write → retirement; новый файл не перекрывается старым, ошибка переноса сохраняет legacy ciphertext. Legacy retirement перед destructive commit предотвращает resurrect после save/clear. Draft-first bootstrap обоих ключей до первого ciphertext предотвращает ложную HMAC-key-loss при первом manifest.
- `app/src/main/java/com/phonoteka/PhonotekaApp.kt`: durable drafts в `filesDir/track-notes/drafts`, legacy source — `cacheDir/track-note-drafts`; key-loss check учитывает оба места с ciphertext.
- `app/src/main/java/com/phonoteka/ui/screens/TrackNotesScreen.kt`: IDLE/SAVING/SAVED/FAILED, retry, явное подтверждение discard. Own Back/System Back ждут запись; ON_STOP запрашивает flush. Поля заблокированы во время barrier.
- `tools/run_device_acceptance.sh`: ui mode включает новый TrackNotesDurabilityUiTest; core включает дополненный AndroidNoteStorageTest.
- `app/src/test/java/com/phonoteka/security/LocalWssMockServerTest.kt`: фактический полный прогон обнаружил timeout cleanup. Оба peer отвечают на close, ожидаются client/server onClosed до shutdown. Cleanup failure НЕ проглатывается. Это fixture fix, не «отключён failing test».

## Тесты

- `app/src/test/java/com/phonoteka/viewmodel/TrackNotesDurabilityTest.kt`:15 новых JVM cases — immediate start, slow IO/coalescing, другие IDs, errors/retry, ordering save/delete/edit/reset, bounded queue, confirmed discard, plaintext saved-state protection/legacy distrust.
- `app/src/test/java/com/phonoteka/data/notes/DraftStorageMigrationTest.kt`:7 новых JVM cases — migration/precedence/failed promotion, interrupted temp, retirement/clear, missing ID, MAC bootstrap failure, reset.
- `app/src/androidTest/java/com/phonoteka/ui/TrackNotesDurabilityUiTest.kt`:3 новых device cases — back barrier, failed-write/system-back/retry, confirmed discard.
- `app/src/androidTest/java/com/phonoteka/data/notes/AndroidNoteStorageTest.kt`:1 новый isolated KeyStore case `firstDraftBeforeAnyManifestCreatesBothKeysAndCanBeCommittedLater`. Только UUID namespace/files; не production/session aliases.

## Применение и точные команды

Выберите patch поверх verified ИЛИ весь новый source archive, не оба. Сохраните свои правки отдельно.

```bash
git apply --check Phonoteka-drafts-after-verified.patch
git apply Phonoteka-drafts-after-verified.patch
chmod +x gradlew
unset PHONOTEKA_MOCK_HMAC_KEY
./gradlew :app:testDebugUnitTest :app:lintDebug :app:compileDebugAndroidTestKotlin :app:assembleDebug :app:assembleDebugAndroidTest --no-daemon --max-workers=1 -Pkotlin.compiler.execution.strategy=in-process
# Только22 новых JVM cases:
./gradlew :app:testDebugUnitTest --tests 'com.phonoteka.viewmodel.TrackNotesDurabilityTest' --tests 'com.phonoteka.data.notes.DraftStorageMigrationTest'
```

Device — только свой подготовленный/разблокированный Android, ANDROID_SERIAL задавать локально, не в чате:

```bash
bash tools/run_device_acceptance.sh core
bash tools/run_device_acceptance.sh ui
# Только новые UI cases и все AndroidNoteStorageTest cases:
timeout --foreground 300s ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.phonoteka.ui.TrackNotesDurabilityUiTest,com.phonoteka.data.notes.AndroidNoteStorageTest
# Только новый KeyStore case:
timeout --foreground 300s ./gradlew :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=com.phonoteka.data.notes.AndroidNoteStorageTest#firstDraftBeforeAnyManifestCreatesBothKeysAndCanBeCommittedLater'
```

Проверять XML/failed/skipped. Timeout/компиляция test APK — не device pass. Никаких auth bypass, настоящих аккаунтов/секретов/чужого трафика. Проверки TLS отдельно требуют своего fixture; не запускать общий connected вслепую.

## Неснятые границы

- Forced kill ДО IO acknowledgement может потерять последний неподтверждённый ввод. Удалён намеренный debounce, но async запись нельзя объявлять законченной заранее.
- Legacy plaintext bundle edits, не попавшие в ciphertext, намеренно не переносятся: arbitrary saved/default/Intent arguments не trusted write command. Подтверждённые encrypted drafts мигрируются; UI сообщает об этой границе.
- fd.sync+rename не является проведённым power-loss/file-system firmware испытанием. Whole NavHost transitions и OS lifecycle/process-death требуют устройства.
- Новые UI/KeyStore tests только подготовлены/скомпилированы, если это подтверждено RESULTS.md; на Android не запускались. Новый R8/release APK пересобран успешно, но unsigned; minified runtime не запускался. Старый release APK не содержит новую доработку, использовать новый только с корректным собственным signing process.
- Anti-rollback аудита, подлинная historical Room DB/теги Git, performance до/после и остальные ручные acceptance остаются открытыми. Фиктивный «защищённый local monotonic counter» не добавлен.
