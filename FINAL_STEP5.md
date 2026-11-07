# Этап 5 — ЛР11: независимые заметки и автосохранение

Статус: код написан, **не компилировался** (в среде подготовки нет Gradle/Android SDK/интернета).

## Файлы
| Файл | Содержание |
|---|---|
| data/freenotes/FreeNotesRepository.kt | FreeNote, строгий FreeNotesCodec (version=1, типы, лимиты, уникальные id), FreeNotesRepository (filesDir/notes.json, Dispatchers.IO, tmp + fsync + atomic rename, карантин битого файла), SAF-порт FreeNotesDocuments + Android-реализация |
| viewmodel/FreeNotesViewModel.kt | CRUD, автосохранение (debounce 800 мс, Mutex, changeToken/savedToken), flush(), импорт с предпросмотром и подтверждением, экспорт, «Начать заново» |
| ui/screens/FreeNotesScreen.kt | Список + редактор, CreateDocument/OpenDocument, flush на ON_STOP / «Назад» / уход с экрана, статус сохранения (liveRegion), testTag |
| ui/Navigation.kt, ui/screens/SettingsScreen.kt | Маршрут `settings/lab11/free-notes`, пункт «Заметки · ЛР11» |
| test/.../freenotes/FreeNotesTest.kt | 14 JVM-тестов: кодек (6), репозиторий (3), ViewModel (5) |

## Соответствие базовому заданию ЛР11
- Файловый Repository на filesDir, JSON, IOException → типизированная ошибка, IO-диспетчер, temp + rename — да.
- CRUD, автосохранение, сохранение между запусками — да (заметки не привязаны к треку).
- Импорт OPEN_DOCUMENT, экспорт CREATE_DOCUMENT, отмена безопасна, storage-разрешений нет — да.
- Повреждённый JSON: ошибка показывается, файл не перезаписывается, запись блокируется до импорта или «Начать заново» (битый файл откладывается как notes.json.corrupt-<time>).
- Старые заметки к трекам (PRO: AES/GCM, ревизии, корзина) не тронуты и остаются на `settings/lab11/notes`.

## Проверка
```
./gradlew :app:testDebugUnitTest --tests 'com.phonoteka.freenotes.*'
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```
Ручной сценарий на устройстве: создать → печатать → «Сохранено» через ~1 с → Home → убить процесс (`adb shell am kill`) → заметка на месте; экспорт → отмена диалога; импорт битого файла; `adb shell run-as <pkg> sh -c 'echo x > files/notes.json'` → экран повреждения.

## Не закрыто
- Сборка и тесты не запускались; Android UI-тестов для этого экрана нет.
- Убийство процесса в первые ~800 мс без ON_STOP теряет последние нажатия (обычный сценарий Home → kill защищён flush).
- Импорт только заменой (без слияния); kotlinx.serialization не используется — Gson уже в зависимостях.
- Далее: ЛР13 (потеря ключа KeyStore), ЛР14 (biometric step-up), ЛР15 (WSS с привязкой к пользователю), ЛР16 (vulnerable/hardened), прогоны на устройстве.
