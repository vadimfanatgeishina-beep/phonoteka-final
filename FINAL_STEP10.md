# Этап 10 — устойчивость сессии ЛР13 и очистка тестов

## refreshStore.read() в MockSessionRepository
После ЛР13 хранилище refresh-токена пробрасывает LOCKED/TRANSIENT, не удаляя токен. Все четыре вызова (`restoreFromBiometric`, `login`, `accessToken`, `logout`) теперь идут через `readStoredRefresh()`:
- LOCKED и TRANSIENT превращаются в `KeyLostException` с понятным сообщением из `KeyLossPolicy.userMessage` (исходная ошибка сохраняется как cause);
- до обращения к endpoint: серверный refresh не расходуется, сохранённая сессия не стирается, можно повторить после разблокировки;
- `login` при заблокированном хранилище не отправляет пароль на endpoint;
- `logout` по-прежнему очищает локальные данные и сообщает об ошибке через `MockSessionPersistenceException`;
- невосстановимые ошибки (повреждённый блоб и т.п.) не маскируются под повторяемые.

Новый тест `RefreshStoreFailureTest.kt` — 5 JVM-тестов на эти сценарии.

## runBlocking → runTest
- `PostCatalogApiContractTest.kt` (JVM) и `AndroidNoteStorageTest.kt` (androidTest) переведены на `runTest`.
- В `app/build.gradle.kts` добавлен `androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")`.

## Не проверено
- Kotlin не компилировался и тесты не запускались: в песочнице нет Gradle и Android SDK.
- Новая зависимость androidTest не скачивалась (нет интернета); версия совпадает с уже используемой в test.
