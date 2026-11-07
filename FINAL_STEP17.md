# Этап 17 — ЛР1–7

Всё собрано в разделе **Настройки → «Лабораторные 1–7»** (маршрут `settings/labs1to7`, у раздела свой NavHost). Код лежит в пакете `com.phonoteka.labs`.

## Сверка требований

| ЛР | Требование | Где |
|---|---|---|
| 1 | Compose в Gradle, ComponentActivity/setContent, navigation-compose, APK | уже было (build.gradle.kts, MainActivity) |
| 1 | FirstScreen: приветствие + счётчик + кнопка, @Preview | `Labs1to4.kt: Lab1FirstScreen` |
| 1 | Курс Stepik | **вручную, к коду не относится** |
| 2 | Text/Column/Row/Box; заголовок, подзаголовок, кнопка; padding/fillMaxWidth/align/clickable | `Lab2BasicsScreen` |
| 3 | Профиль: аватар 64dp, имя жирным, статус серым, «Подписчики»/«Подписки» + Spacer(weight), «Подписаться» 16dp + «Сообщение» через 8dp, нижняя панель из 3 иконок SpaceEvenly, BoxWithConstraints >600dp → Row | `Lab3ProfileScreen` |
| 3 | Адаптивная карточка товара (картинка, название, цена, «Купить») | `ProductCard` |
| 3 | Две колонки 70/30 | `TwoColumnLayout` |
| 4 | Счётчик +1/−1/Сброс | `CounterSection` |
| 4 | Stateless `SearchBar(query, onQueryChange)`, у родителя — число символов и «Очистить» | `SearchBar` + `SearchSection` |
| 4 | 5 ExpandableCard, у каждой своё состояние | `ExpandableCard` |
| 4 | Форма с «Отправить»; тема на уровне экрана; чекбоксы со счётчиком | `FormSection`, `Lab4StateScreen`, `CheckboxSection` |
| 5 | LazyColumn: 100 элементов, заголовок, разделители, «Загрузить ещё» (+20) | `Lab5List.kt` |
| 5 | Индикатор вместо кнопки, key + animateItem, page/pageSize, «Больше нет данных», AnimatedVisibility | `Lab5ListScreen` |
| 5 | Фильтр категорий, позиция скролла сохраняется, debounce 300 мс, логика во ViewModel + unit-тесты | `Lab5ListViewModel`, `Lab5ListViewModelTest` |
| 6 | 4 экрана home/list/details/{itemId}/settings, 20 элементов, «Элемент не найден», popBackStack | `Lab6Navigation.kt: Lab6App` |
| 6 | ViewModel на каждый экран, StateFlow + collectAsStateWithLifecycle, загрузка один раз (поворот не повторяет запрос) | `LabListViewModel`, `LabDetailsViewModel`, `LabSettingsViewModel` |
| 6 | Настройки видны во всех экранах через `LocalLabSettings` (staticCompositionLocalOf + ProvideLabSettings) | тема и подсказки |
| 6 | Deep link `app://phonoteka/details/{id}`, стек Главная → Список → Детали | `LabDeepLinks`, `src/debug/AndroidManifest.xml` |
| 6 | Анимации fade+slide, строка/JSON в аргументе (`ref`, Gson), query-параметры `list?category=&favorites=` | `Lab6Routes` |
| 6 | Вложенная навигация в настройках (Общие / Уведомления / Тема), BackHandler с подтверждением при несохранённых изменениях | `navigation("settings")`, `Lab6GeneralSettings` |
| 6 | Unit-тесты VM, UI-тест «Главная → Список → Детали → Назад → Главная» | `Lab6ViewModelsTest`, `Labs1to7UiTest` |
| 7 | sealed Loading/Refreshing/Success/Empty/Error, delay(800), 20% ошибок, retry, FAB «Обновить» только в Success, поворот | `Lab7Loading.kt` |
| 7 | Ошибки трёх типов (Нет сети / Таймаут / Серверная) — у каждой своя иконка и текст; backoff 0/1/2/4 с; одновременно идёт только один запрос (isRequestActive) | `Lab7ListViewModel` |
| 7 | Кэш с cachedAt, фоновое обновление, полупрозрачный оверлей «Обновление…», Snackbar при ошибке над кэшем, pull-to-refresh, DataStore, лог в Logcat (`Lab7List`), «Ничего не найдено» | то же |
| 7 | Тесты: успех, ошибка+retry, множественные нажатия, backoff, кэш, поворот; UI «ошибка → ждём → повтор → успех» | `Lab7ListViewModelTest`, `Labs1to7UiTest` |

Deep link объявлен **только в debug-манифесте**. Так release-поверхность ЛР14 остаётся прежней: `InviteManifestContractTest` по-прежнему проходит, `phonoteka://details` всё так же не резолвится.

## Новые и изменённые файлы
- Новые: `labs/Labs1to4.kt`, `Lab5List.kt`, `Lab6Navigation.kt`, `Lab7Loading.kt`, `LabsHub.kt`, `src/debug/AndroidManifest.xml`.
- Новые тесты: `test/.../labs/Labs1to7ViewModelTest.kt` (3 класса, 19 тестов), `androidTest/.../ui/Labs1to7UiTest.kt` (5 тестов).
- Изменены: `Navigation.kt` (маршрут и переход по deep link), `SettingsScreen.kt` (пункт меню), `MainActivity.kt` (`LabDeepLinks.offer`).

## Важно
В песочнице нет Gradle/SDK/kotlinc, поэтому код **не компилировался**. Проверены только скобки.

## Что прогнать ворк-бади
```
./gradlew :app:assembleDebug :app:lintDebug
./gradlew :app:testDebugUnitTest --tests 'com.phonoteka.labs.*'
./gradlew :app:testDebugUnitTest
adb shell svc power stayon true
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.phonoteka.ui.Labs1to7UiTest
# deep link (после разблокировки приложения):
adb shell am start -a android.intent.action.VIEW -d "app://phonoteka/details/7" com.phonoteka.labcheck
adb shell am start -a android.intent.action.VIEW -d "app://phonoteka/details/999" com.phonoteka.labcheck   # «Элемент не найден»
```
Ручная проверка:
- поворот экрана на ЛР4, ЛР5 и ЛР7: состояние и скролл должны сохраниться, повторного запроса быть не должно;
- ширина больше 600dp на ЛР3 (планшет или ландшафт);
- Logcat с тегом `Lab7List`.

Если что-то не скомпилируется, пришлите лог ошибок целиком, я исправлю.
