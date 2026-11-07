# Этап 12 — Android-тесты для ЛР11 и ЛР13

## FreeNotesRepositoryDeviceTest (androidTest, 3 теста)
- без файла — пустой список;
- повторное сохранение заменяет существующий `notes.json` (проверка `renameTo` вместо `Files.move` на реальной ФС), новый экземпляр читает те же данные, `notes.json.tmp` не остаётся;
- битый файл даёт `CORRUPT`, не перезаписывается, после «Начать заново» откладывается в `notes.json.corrupt-<время>` с исходным содержимым.

## RefreshStoreKeyLossDeviceTest (androidTest, 3 теста)
Настоящие SharedPreferences и AndroidKeyStore, изолированное пространство fixture, журнал в памяти:
- запись/чтение без записи в журнал потерь;
- блоб не в Base64 — удаляется, в журнале `CORRUPT_BLOB`;
- изменённый шифртекст ловится проверкой GCM — удаляется, в журнале `CORRUPT_BLOB`.

## Не проверено
- Тесты не компилировались и не запускались: нет Gradle/Android SDK/эмулятора.
- Запуск: `./gradlew :app:connectedDebugAndroidTest --rerun-tasks`.
- Для ЛР14 (биометрия) и ЛР15 (WSS на устройстве) отдельных новых тестов на этом этапе нет: требуется эмулятор с отпечатком (`adb -e emu finger touch 1`) и запущенный локальный стенд.
