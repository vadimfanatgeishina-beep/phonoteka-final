# Этап 16 — правки по BUILD_REPORT.md (реальная сборка JDK 17, ASUS ZS661KS, Android 13)

## Ошибки компиляции (перенесены в исходник, чтобы не править руками)
1. `Navigation.kt` — импорт `Lab13ResultsScreen`.
2. `LoadingButton.kt` — импорт `androidx.compose.runtime.getValue`.
3. `TaskTrackerScreen.kt` — `ExperimentalComposeUiApi` в `@OptIn` + импорт.
4. `app/build.gradle.kts` — core library desugaring (`desugar_jdk_libs:2.1.5`) для `java.util.Base64` на API 24–25.
5. `RefreshStoreFailureTest` — тип `Throwable`, а не `Exception`: проверка `IllegalStateException` сохранена.

## Падения JVM-тестов (5)
| Тест | Причина | Исправление |
|---|---|---|
| `PriceTickerTest.withoutUnsubscribeListenersAccumulate` | **Баг модели:** Set схлопывал одинаковую лямбду, и утечка не была видна | `CopyOnWriteArrayList`: каждая подписка считается |
| `DraftStorageMigrationTest.interruptedTmpFile…` | **Баг на Windows:** `File.renameTo` не заменяет существующий файл | `replaceAtomically()`: на Windows `Files.move(ATOMIC_MOVE)`, на Android прежний rename |
| `OwnedBackupPinRotationTest` (2) | Сертификат выдан на `localhost`, а URL указывал на `127.0.0.1` | URL принудительно на `localhost` |
| `SocketUserBindingTest.boundSocket…` | Корутина подключения не запускалась до блокирующего `takeRequest` | `runCurrent()` перед ожиданием, таймаут 5 с, понятная ошибка вместо NPE |

## androidTest
- 3 падения — условия запуска (разблокировка / HTTPS-сервер 8443), см. FINAL_STEP15.md.
- 19 зависших: в biometric-тестах нет BiometricPrompt, они не ждут ввода. Вероятная причина — экран погас или заблокировался во время UI-тестов (Compose ждёт окно). Перед прогоном:
  `adb shell svc power stayon true; adb shell settings put system screen_off_timeout 1800000; adb shell wm dismiss-keyguard`
  Затем гонять по классам, чтобы найти зависший:
  `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.phonoteka.ui.Lab9PhonotekaUiTest`
