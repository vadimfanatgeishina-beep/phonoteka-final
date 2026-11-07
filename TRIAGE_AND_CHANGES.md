# Короткий triage и карта патчей

> Текущий фактический итог нового кандидата: ACTUAL_VALIDATION.md / lab_reports/current_validation/. Ниже сохранён исторический план/контекст; сообщения о неподтверждённой сборке относятся к предыдущему source-only этапу. Device/historical критерии остаются открытыми, если не подтверждены отдельно.

Это исходный кандидат, а не подтверждённое закрытие ЛР8–16. Команды сборки/тестирования здесь не выполнялись.

| Приоритет | Правка / точные пути | Проверяемый критерий |
|---|---|---|
| P0 | security/RequestSigning.kt, security/SignedWebSocketFrames.kt, tools/LocalLab15MockServer.java, tools/LocalLab15WssServer.java | nonce хранится до timestamp + window включительно; future timestamp не допускает повтор после прежнего now + window; overflow/boundary/лимит не ослабляют replay guard |
| P0 | security/MockAuthSession.kt, ui/screens/AuthGateScreen.kt, security/LocalAuthManager.kt | 4 ошибки → 300000ms; ledger не хранит raw bearer в ключах; refresh replacement при сбое записи revoked, старый тоже не оживает; auth-bound AES key, prepareWrite ДО rotation; system cancel fail-closed |
| P0 | PhonotekaApp.kt, security/MockRoleAuthority.kt, security/DemoRoles.kt, data/PhonotekaRepository.kt | forged role/scopes не проходят registry; истёкшая локальная auth/denied role запрещают прямой repository вызов, не только кнопку; signed audit проверяется до чтения/экспорта |
| P1 | viewmodel/PostCatalogViewModel.kt, data/PostCatalogRepository.kt (RoomPostCatalogRepository) | old detail task не перезаписывает новый; Room changes обновляют экран; cancellation не превращается в error; retry 1/2/4s bounded |
| P1 | data/LibraryBackupFileStore.kt, data/notes/AndroidNoteStorage.kt | rollback до finishWrite; recovery после unfinished write; после finishWrite только верификация, НЕ failWrite; новый пустой manifest не ломает последующее создание AES ключа |
| P1 | androidTest/.../ui/Lab8AccessibilityTest.kt, androidTest/.../ui/Lab9PhonotekaUiTest.kt, androidTest/.../security/LocalLab15TlsClientTest.kt | disabled click не действует, search ждёт удаления старого результата, autosave gate контролируем; pin/query/signature/time/replay negative сценарии изолированы |
| P1 | data/notes/*, viewmodel/TrackNotesViewModel.kt, ui/screens/TrackNotesScreen.kt | encrypted revisions/backup/trash, atomic HMAC index, импорт валидируется ДО commit; SAF вне main; key-loss reset только с подтверждением |
| P1 | security/LocalLiveSocket.kt, debug/.../Lab15LiveScreen.kt, tools/LocalLab15WssServer.java | localhost-only, отдельные request/response channels, новое channel на reconnect, bounded backoff; release не доверяет fixture |
| P2 | network/AudioDownloader.kt, benchmark/*, tools/verify_local.sh, tools/package_submission.py, .gitlab-ci.yml | negative download tests; воспроизводимый benchmark без auth bypass; проверяемая CI; упаковка без work/credentials |

Все app пути таблицы относительны app/src/main/java/com/phonoteka/, если не указан source set. Полные filenames/тесты можно искать в unified diff; команды и устройства — RUN_MATRIX.md. Полный diff применяется к чистому v3; incremental — только к v3 с пятью предыдущими priority patches. Не смешивать варианты.

Дополнительные ограничения: already-started долгую операцию надо проверять на logout/background/role switch; граница при входе в API не является гарантией немедленной отмены любой внешней операции. Actual Room upgrade, performance numbers, SCA, runtime R8 и полный connected без поднятых fixtures не могут быть заменены добавлением исходников.
