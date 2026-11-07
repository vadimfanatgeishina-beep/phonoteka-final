# Финальная версия — этап 1: заметки и контракт локального канала

Основа — последняя безопасная передача `Phonoteka-drafts-source.zip`. Это продолжение финального приложения, **не завершение всех ЛР8–16**. Единственный APK с полной функциональностью пока не объявляется готовым. Разделение на лабораторные сборки здесь не выполняется.

Актуальные результаты именно этого этапа — `lab_reports/final_step1/RESULTS.md`. Предыдущие draft/current_validation результаты относятся к прежним снимкам, не наследуются как новый PASS.

## Реальные изменения

| Пункт | Конкретные файлы | Что изменено | Что ещё не доказано |
|---|---|---|---|
| ЛР11 updatedAt | data/notes/TrackNotesRepository.kt | Note и HMAC-index Entry содержат updatedAt; save/restore ставят время, список newest-first | Настоящая дата старых записей неизвестна и остаётся0; UI показывает «неизвестна», не дату миграции |
| Пустая заметка | тот же Repository | Blank title+text нельзя commit/import; пустой редактор/draft можно сохранить как промежуточное редактирование | Реальный process-kill/SAF на Android |
| Двойное повреждение | тот же Repository, viewmodel/TrackNotesViewModel.kt | Отдельная damaged row, здоровые доступны; текст из повреждённого ciphertext не раскрывается, VM не открывает эту запись | Android corruption UI и реальные KeyStore failures |
| Backup recovery | тот же Repository | При валидном previous создаётся repaired current revision, atomically публикуется index, пишется integrity.log без текста | Power-loss/OEM filesystem durability не объявляется доказанной |
| Integrity menu | ui/screens/TrackNotesScreen.kt | Кнопка проверки, checked/total progress, recovered/damaged summary | Progress rendering/device acceptance |
| Корзина | Repository/VM/Screen | Список deletedAt, restore, confirm purge-all; активные данные не удаляются purge-all | Камера/биометрия к этой функции не относятся; реальные Android UX tests нужны |
| Удаление | Screen/VM | Long press и кнопка с confirmation; damaged permanent removal отдельно; cancel не вызывает mutation | Новые component UI negatives только собраны, пока не выполнены |
| Step-up boundary | security/RiskStepUp.kt, data/notes/AuthorizedTrackNotes.kt | Clear trash и remove damaged требуют своих operation-specific approvals; одно approval нельзя использовать для другой операции | Не исправляет ещё незакрытый biometric-refresh flow ЛР13 |
| Import preview | Repository/VM/Screen | Read+validate+count до confirmation; apply использует private copy тех же bytes; cancel/invalid preview ничего не заменяют | Process death во время импорта и реальные document providers |
| Legacy import | Repository | Explicit v1/v2, versionless object/array, body/text bridge; strict types/IDs, duplicate reject, unsupported version без Int wrap | Это всё ещё per-track Notes adaptation, не независимый basic Notes app |
| SAF architecture | data/notes/AndroidJsonDocuments.kt | Notes SAF open/read/write в data-layer на IO; VM не знает Uri/ContentResolver | Базовый LibraryBackupScreen ещё имеет свой старый SAF path; его не объявлять исправленным этим этапом |
| AES policy | data/notes/AndroidNoteStorage.kt | Новые AES-ключи явно256bit; existing alias/key не удаляется; fresh key policy/IV test добавлен | Размер существующих ранее созданных ключей не переопределён; device KeyInfo test не запускался |
| HTTP status | tools/LocalLab15MockServer.java | Typed accepted/Signature/Clock/Replay/Capacity →200/401/401/409/503 | Android actual fixture response tests только обновлены/собраны |
| Nonce retention | RequestSigning/SignedFrames/Java | Предыдущее исправление future timestamp retention сохранено; invalid signature не consumes nonce | Persistent replay cache across server restart не реализован |
| WSS waits | security/LocalReconnectPolicy.kt, LocalLiveSocket.kt |1/2/4/8/16/30s cap30, overflow-safe policy | Max reconnects по умолчанию остаётся5; offline10s Android сценарий не выполнен |
| Rotation tests | test/security/OwnedBackupPinRotationTest.kt | Один pinned клиент принимает owned primary и backup, trusted-but-unpinned third отклонён | JVM ephemeral cert policy test не равен actual Android NSC/CER rotation |
| ЛР10 timeouts | src/debug и src/release network/Lab10HttpClient.kt | Explicit connect10/read30 seconds | Empty/detail VM/real API Android acceptance остаются |
| ЛР14 QR decoder | ui/components/OwnedQrScanner.kt, test/security/OwnedQrEncodingTest.kt | TRY_HARDER в реальном декодере,32 новых случайных owned invitations в одном regression case вместо одной | Camera frame orientation/lighting/device verification остаются |
| ЛР8 contrast | ui/components/StatusButton.kt | SUCCESS check mark использует отдельный foreground, а не цвет своего фона | Остальной profile/design/accessibility backlog остаётся |

Пути в таблице относительно `app/src/main/java/com/phonoteka/`, если не указан tools/src/debug/src/release/test.

## Архитектурные ограничения / не выдавать за закрытие

- Заметки всё ещё привязаны к существующему track ID, Gson/revisions вместо буквального basic notes.json/kotlinx.serialization. Создание независимой заметки и автосохранение в active catalog при уходе ещё не реализованы: уход подтверждает долговечный **draft**, а «Сохранить» делает commit.
- Integrity log — простой локальный append журнал безопасных ID/типов, **не authenticated security audit** ЛР13/14. Полный signed snapshot rollback остаётся за моделью защиты root-владельца.
- Настоящий ЛР13 biometric→decrypt refresh→restore session не исправлен этим блоком. Константный CryptoObject proof из предыдущего снимка ещё нужно заменить.
- ЛР14 append-only actor/subject JSONL security audit и mock employee pass ещё предстоит завершить.
- ЛР8 profile, ЛР9 literal TaskTracker60+effects+measurements и ЛР16 isolated vulnerable/hardened module ещё не готовы.
- Native system auth/camera/SAF/mitmproxy/Inspector/logcat/reinstall не выполнялись здесь. Нового Android эмулятора/телефона нет.
- Debug app id `.labcheck`, signing key этой рабочей среды может отличаться от прежнего. Не удалять установленное приложение с данными; собственный пустой AVD/локальная сборка со своим прежним signing process предпочтительнее.
- Release artifact unsigned. Assembly/R8 PASS не заменяет запуск minified приложения.

## Новые тесты

- `NotesRequirementsRegressionTest` —16 Repository JVM cases: empty/stamp/index/date/legacy/types/version-overflow/duplicate/preview/double corruption/repair/log/progress/selective delete/trash/failed commit/export/size.
- `NotesImportPreviewTest` —4 VM cases: cancel, validated snapshot, invalid preview, direct damaged open.
- `NotesDestructiveAuthorizationTest` —1 direct repository bypass case на обе destructive операции.
- `LocalReconnectPolicyTest` —4 policy/virtual-time/overflow/negative-config cases.
- `Lab10HttpClientPolicyTest` —1 timeout case.
- `OwnedBackupPinRotationTest` —2 real owned JVM TLS certificate policy cases.
- `OwnedQrEncodingTest` — исправлена нестабильная проверка детектора:32 независимых signed fixtures, те же TRY_HARDER hints, что в сканере; не PURE_BARCODE обход детектора.
- `NotesConfirmationUiTest` —4 Android component negatives: delete cancel/confirm, trash cancel, damaged open unavailable. Требуют выполнения на Android.
- `AndroidNoteStorageTest.freshNoteKeyIs256BitsNonAuthBoundAndUsesDifferentIvs` —1 isolated Android crypto case, требует device.
- `LocalLab15HttpsContractTest` + `run_owned_tls_contract.py` —real own-loopback HTTPS request/response, TLS1.2/1.3 positive, raw old-protocol server refusal. Не Android MITM/NSC evidence.

## Команды

JDK17, SDK34. Только свой отдельный тестовый Android. Команды ниже воспроизводимы; фактически выполненные результаты фиксируются отдельно в RESULTS.md, не выводятся из наличия команды.

```bash
./gradlew :app:testDebugUnitTest :app:lintDebug \
  :app:compileDebugAndroidTestKotlin :app:assembleDebug \
  :app:assembleDebugAndroidTest :app:assembleRelease \
  --no-daemon --max-workers=1 -Pkotlin.compiler.execution.strategy=in-process

python tools/run_owned_tls_contract.py

# На готовом собственном Android, не в этой среде:
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.phonoteka.ui.NotesConfirmationUiTest,com.phonoteka.ui.TrackNotesDurabilityUiTest,com.phonoteka.data.notes.AndroidNoteStorageTest \
  --rerun-tasks

# Сначала OWNED fixture + adb reverse по RUN_MATRIX.md:
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.phonoteka.security.LocalLab15TlsClientTest \
  --rerun-tasks
```

Применение unified diff: из корня **последнего drafts source**, сначала `git apply --check <patch>`, затем `git apply <patch>`. Если локальная версия отличается — check должен остановить применение; не форсировать и не перезаписывать пользовательские изменения. `.git` baseline этой подготовки synthetic import, не подлинная история проекта/учебный vulnerable baseline.
