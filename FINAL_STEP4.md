# Этап 4 — отдельные экраны ЛР8 «Мой профиль» и ЛР9 «Трекер задач»

Статус: **код написан, но не скомпилирован.** В песочнице нет Gradle-дистрибутива, Android SDK и интернета.
Все пункты ниже нужно подтвердить командами из раздела «Проверка».

## Что добавлено

| Файл | Назначение |
|---|---|
| `tasks/TaskModel.kt` | Модель `@Immutable TaskItem`, `SaveState`, `FilterMode`, чистые функции `TaskOps` (60 задач, toggle без remap, add/remove/moveUp/filter) |
| `tasks/TaskStore.kt` | `TaskStore` и локальный `TaskFileStore`: IO-диспетчер, запись через tmp+rename, строгий парсер `TaskCodec` |
| `tasks/PriceTicker.kt` | Подписка, счётчик слушателей, `FilterAnalytics` |
| `viewmodel/TaskTrackerViewModel.kt` | StateFlow, `changeToken`/`savedToken`, склейка параллельных сохранений, ошибка сохранения |
| `ui/screens/TaskTrackerScreen.kt` | Экран ЛР9 со всеми анимациями, эффектами и testTag |
| `viewmodel/ProfileViewModel.kt` | Профиль в DataStore `phonoteka_profile`, валидация, «Отмена»/«Сбросить» |
| `ui/components/LoadingButton.kt` | `LoadingButton`, `SnackbarLikeMessage` (Assertive, автоскрытие) |
| `ui/screens/ProfileScreen.kt` | «Мой профиль»: Compact/Expanded, градиентная шапка, анимация карточки, статистика, достижения, форма, тема, 3 @Preview |
| Navigation / SettingsScreen | Маршруты `settings/profile`, `settings/tasks` и пункты в настройках |
| debug RecompositionProbe | В allowlist добавлены `TaskRow`, `TaskTrackerScreen`, `ProfileScreen` |
| JVM-тесты | `tasks/TaskTrackerLogicTest.kt` (16 тестов), `viewmodel/ProfileViewModelTest.kt` (4 теста) |
| androidTest | `ui/TaskTrackerUiTest.kt` (4 теста, mainClock/waitUntil), `ui/ProfileScreenTest.kt` (3 теста задания 3) |

## Соответствие ЛР9

### Функции

- 60 задач.
- Done переключается кликом по строке (`selectable`, Role.Checkbox).
- Checkbox с `onCheckedChange = null`.
- Детали раскрываются через `rememberSaveable(task.id)`.
- Поиск с debounce 300 мс.
- «Новая задача» + «Добавить», с валидацией 1–80 символов.
- «Сохранить» и автосохранение через 1 с после последнего изменения.
- «Тик» (`mutableIntStateOf`).
- FAB «Наверх» при `firstVisibleItemIndex > 5` (`derivedStateOf`).
- Удаление и «Выше» (перестановка).

### Анимации

Все ≤300 мс, у каждой есть label:

- animateColorAsState ×2, animateDpAsState, animateFloatAsState ×2;
- AnimatedVisibility (детали, FAB);
- animateContentSize;
- updateTransition для SaveButton: ширина, скругление, цвет;
- rememberInfiniteTransition: три точки, RepeatMode.Reverse, StartOffset.

Цикл бесконечной анимации — 450 мс. Это повтор, а не переход.

Выполненная задача отличается не только цветом: зачёркнутый текст, «✓ Выполнена», отступ, прозрачность.

### Эффекты

- `LaunchedEffect(Unit) { load() }`.
- `LaunchedEffect(query)` + лог `DEBOUNCE` с start/apply/cancel.
- `rememberUpdatedState` для apply и autosave.
- `LaunchedEffect(changeToken)` для автосохранения.
- `DisposableEffect` для PriceTicker + переключатель «Утечка» без отписки (счётчик слушателей виден в debug-панели).
- Аналитика: эффект против наивного вызова в теле.
- `collectAsStateWithLifecycle`.
- `repeatOnLifecycle(STARTED)` для таймера.
- `snapshotFlow + distinctUntilChanged + debounce` для лога `SCROLL`.
- В main нет Thread.sleep, runBlocking и GlobalScope.

### Производительность

- Лог `RECOMP` в TaskRow; RecordRecomposition.
- `key = { it.id }`, `contentType`.
- `@Immutable`.
- Лямбды обёрнуты в remember.
- Debug-переключатель NAIVE / STALE_REMEMBER / DERIVED со счётчиком фильтраций.

## Соответствие ЛР8

- Тема, AppButton, ProfileCard, StatusButton, FormField, ExtendedColors и ThemeMode уже были. Теперь они собраны на буквальном экране «Мой профиль».
- Кнопки:
  - «Сохранить изменения» — LoadingButton, LARGE PRIMARY;
  - «Отмена» — MEDIUM SECONDARY;
  - «Сбросить» — SMALL OUTLINED.
- Состояния полей:
  - FormField ERROR — пустое или длинное имя;
  - WARNING — длинное «О себе»;
  - DISABLED — роль и поля во время сохранения.
- Доступность:
  - `collectionInfo` / `collectionItemInfo` в достижениях;
  - heading у заголовков;
  - `minimumInteractiveComponentSize`;
  - роли RadioButton и Switch;
  - декоративная иконка с contentDescription=null;
  - «Изменения сохранены» в Assertive live region.
- Ширина экрана:
  - < 600dp → Compact;
  - 600–839dp → двухколоночный макет (Medium);
  - ≥ 840dp → Expanded.
- Тема и forceDark сохраняются через существующий SettingsViewModel/DataStore.

## Проверка

```
./gradlew :app:testDebugUnitTest :app:lintDebug :app:compileDebugAndroidTestKotlin :app:assembleDebug --no-daemon
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.phonoteka.ui.TaskTrackerUiTest,com.phonoteka.ui.ProfileScreenTest
adb logcat -s DEBOUNCE RECOMP LOAD TICKER SCROLL
```

## Не закрыто (честно)

1. Компиляция и все тесты этапа 4 не запускались.
2. Таблица «до/после» для пяти сценариев в `LAB9_PERFORMANCE.md` пока без чисел: измерения нужны на устройстве (Layout Inspector + лог RECOMP). Цифры не выдумывались.
3. GIF и скриншоты анимаций, Preview-рендеры.
4. TalkBack-прогон экрана профиля.
5. `runBlocking` остаётся в двух тестах (`AndroidNoteStorageTest`, `PostCatalogApiContractTest`). В main его нет. Замена на runTest — отдельной задачей.
6. Остальной backlog:
   - ЛР11 — независимые заметки и автосохранение;
   - ЛР13 — восстановление после потери ключа KeyStore;
   - ЛР14 — обязательный biometric step-up для журнала;
   - ЛР15 — WSS с привязкой к пользователю;
   - ЛР16 — изолированные vulnerable/hardened варианты;
   - реальные Android-прогоны.
