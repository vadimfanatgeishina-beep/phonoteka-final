# Phonoteka — план проверки на реальном телефоне (для агента-исполнителя)

Ты — агент с доступом к терминалу, репозиторию Phonoteka и Android-телефону, подключённому по USB.
Задача: собрать проект, прогнать ВСЕ тесты и ручные сценарии ниже, чинить ошибки компиляции/тестов и собрать доказательства для отчётов по ЛР8–16.

## 0. Правила
- Работай из корня проекта. Все доказательства клади в `.local/evidence/<дата>/` (логи, XML отчёты, скриншоты).
- Каждую команду логируй: `cmd 2>&1 | tee .local/evidence/<дата>/<шаг>.txt`.
- «Успех» = BUILD SUCCESSFUL **и** в XML отчётах 0 failures, 0 errors. Skipped-тесты перечисли отдельно с причиной — это НЕ pass.
- Чинить можно: ошибки компиляции Kotlin, импорты, несовместимые API, очевидные баги в тестах. **Нельзя**: ослаблять проверки безопасности (делать exported=true, убирать step-up/PIN, разрешать cleartext, удалять/отключать падающие assert'ы), чтобы тест «позеленел». Если тест падает из-за настоящего бага безопасности — чини код приложения, а не тест, и опиши в отчёте.
- Каждую правку — отдельным коммитом `fix(device): …` (если есть git) или списком файлов в отчёте.
- Не стирай личные данные телефона. Удалять/чистить можно только `com.phonoteka.labcheck` (debug) и `com.phonoteka` (release), если стоят.
- PIN, recovery-коды, токены в отчёт и логи не пиши (тестовый PIN можно упомянуть как «тестовый PIN»).
- Экран телефона должен быть РАЗБЛОКИРОВАН (иначе UI-тесты виснут). Если нужен отпечаток/системное подтверждение — попроси человека и жди.

## 1. Подготовка
```bash
java -version            # нужен JDK 17 для Gradle; для tools/*.java — JDK 22+ (можно второй JDK)
echo $ANDROID_HOME       # Android SDK 34 (platform-tools, build-tools)
adb devices -l           # ровно одно устройство в состоянии device (если unauthorized — попроси нажать «Разрешить отладку»)
export ANDROID_SERIAL=<serial из adb devices>
D=.local/evidence/$(date +%F); mkdir -p $D
adb shell getprop ro.build.version.sdk | tee $D/device-sdk.txt
adb shell getprop ro.product.model | tee -a $D/device-sdk.txt
# для стабильности UI-тестов:
adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
adb shell settings put global animator_duration_scale 0
adb shell svc power stayon usb
```
В отчёт запиши модель и API-уровень: часть проверок зависит от версии (POST_NOTIFICATIONS ≥ 33, HIDE_OVERLAY ≥ 31, clearPrimaryClip ≥ 28, EXTRA_IS_SENSITIVE ≥ 33).

## 2. Сборка и JVM (без телефона)
```bash
chmod +x gradlew
./gradlew :app:testDebugUnitTest :app:lintDebug :app:compileDebugAndroidTestKotlin :app:assembleDebug :app:assembleDebugAndroidTest :app:assembleRelease \
  --no-daemon --max-workers=2 2>&1 | tee $D/02-build.txt
```
Ожидаемо:
- Код этапов 13–14 НИ РАЗУ не компилировался — ошибки вероятны. Список подозрительных мест (проверь первыми):
  - `ui/screens/FreeNotesScreen.kt` NoteEditor — `LocalContext.current` внутри Row, кнопка «Копировать» (testTag `noteCopy`);
  - `security/SensitiveClipboard.kt` — `ClipDescription.extras` (API 24+?), `ClipDescription.EXTRA_IS_SENSITIVE` (API 33, нужен `Build.VERSION.SDK_INT` guard), `clearPrimaryClip()` (API 28);
  - `data/freenotes/FreeNotesRepository.kt` — kotlinx.serialization: `prettyPrintIndent` (требует `@OptIn(ExperimentalSerializationApi::class)` — или просто убери), плагин `org.jetbrains.kotlin.plugin.serialization` 1.9.25 в обоих build.gradle.kts;
  - `security/EmployeeInviteLink.kt`, `security/RecoveryNotifier.kt`, `security/SensitiveResultsGate.kt` — `data object` (Kotlin 1.9 — ок, но проверь `languageVersion`);
  - `ui/components/EmployeePassCard.kt` — ZXing `QRCodeWriter` → Bitmap → `asImageBitmap()`, `Modifier.semantics`;
  - `ui/screens/Lab14AccessScreen.kt` — импорт `com.phonoteka.security.scopeClaim`, `DemoScope.PASS_VIEW`;
  - `ui/screens/Lab13ResultsScreen.kt`, `ui/screens/InviteRegistrationScreen.kt`, `InviteActivity.kt`, `TrackInviteActivity.kt`;
  - `security/RemoteIdentityClient.kt` (org.json + OkHttp), тест `RemoteIdentityClientTest` (нужен `testImplementation("org.json:json:20240303")`);
  - `ui/Navigation.kt` — удалён `navDeepLink`, проверь, что не осталось висячих скобок/импортов; маршрут `settings/lab13/results`.
- Итог: BUILD SUCCESSFUL. Отчёты: `app/build/test-results/testDebugUnitTest/*.xml`, `app/build/reports/lint-results-debug.html`.
- Запиши: число JVM-тестов (было 216 до этапа 13, сейчас должно быть больше), lint errors (должно быть 0) и warnings.
- Особо убедись, что прошли новые классы: `security.Step13SecurityTest` (ссылка-приглашение, scopes, уведомление о recovery, step-up результатов, буфер обмена), `security.RemoteIdentityClientTest` и новые тесты в `FreeNotesTest` (строгий разбор kotlinx.serialization и чтение старого Gson-файла).
- Если старые тесты падают из-за нового scope `PASS_VIEW` (точное множество scopes роли или `DemoScope.entries.size`) — обнови ожидания: теперь EMPLOYEE = library:read, library:write, pass:view; SECURITY = library:read, pass:view, scan:execute; ADMIN = все.

## 3. Java-стенды (без телефона, JDK 22+)
```bash
java tools/lab16/Lab16VariantsTest.java . | tee $D/03-lab16.txt          # ожидание: F-01…F-08 REPRODUCED / BLOCKED, OK (42 checks)
java tools/backend/IdentityServerTest.java | tee $D/03-backend.txt        # ожидание: OK (34 checks)
bash tools/run_security_scans.sh 2>&1 | tee $D/03-scans.txt               # если установлены semgrep и gitleaks; иначе запиши «не установлены»
```

## 4. Установка на телефон
```bash
adb uninstall com.phonoteka.labcheck || true
./gradlew :app:installDebug --no-daemon 2>&1 | tee $D/04-install.txt
adb shell monkey -p com.phonoteka.labcheck -c android.intent.category.LAUNCHER 1
adb exec-out screencap -p > $D/04-launch.png
```
Приложение должно стартовать без краша. Параллельно держи лог: `adb logcat -c; adb logcat -v time '*:W' > $D/logcat.txt &` (в фоне; в конце `kill %1`). После всех сценариев проверь лог:
```bash
grep -n -i 'FATAL\|AndroidRuntime' $D/logcat.txt          # должно быть пусто
grep -n -i 'token=\|refresh_token\|access_token\|Bearer \|pin=' $D/logcat.txt   # должно быть пусто (утечка секретов = баг)
```

## 5. Автоматические тесты на устройстве
Сначала все сразу:
```bash
timeout 1800 ./gradlew :app:connectedDebugAndroidTest --no-daemon 2>&1 | tee $D/05-connected-all.txt
cp -r app/build/outputs/androidTest-results $D/05-xml
cp -r app/build/reports/androidTests $D/05-html
```
Если что-то висит/падает — гоняй по группам (`bash tools/run_device_acceptance.sh core|ui|invite|auth-bound|tls`) или по классу:
```bash
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.phonoteka.security.InviteManifestContractTest
```
Классы и что они доказывают:

| Класс | ЛР | Примечания |
|---|---|---|
| `ui.Lab8AccessibilityTest` | 8 | дизайн-система, disabled-кнопки, accessibility |
| `ui.Lab9PhonotekaUiTest`, `ui.ProfileScreenTest`, `ui.TaskTrackerUiTest` | 9 | состояния экрана, списки |
| `data.LocalRoomCatalogContractTest` | 10 | HTTPS MockWebServer + Room, офлайн-кэш |
| `data.RealPublicApiE2ETest` | 10 доп. | **пропускается без флага**, см. шаг 6 |
| `data.freenotes.FreeNotesRepositoryDeviceTest`, `data.notes.AndroidNoteStorageTest`, `ui.TrackNotesDurabilityUiTest`, `ui.NotesConfirmationUiTest`, `data.LibraryBackupFileStoreTest` | 11 | файлы, атомарная запись, шифрование |
| `data.ModeledVersion2MigrationTest` | 11/12 | миграция Room 2→3 |
| `security.RefreshTokenStoreTest`, `security.RefreshStoreKeyLossDeviceTest`, `security.BiometricRefreshVaultPolicyTest` | 13 | KeyStore |
| `security.AuthBoundRefreshPolicyTest` | 13 | нужен свежий вход в систему (разблокировка не старше пары минут) — перед запуском попроси человека заблокировать и разблокировать телефон |
| `security.AccessBoundaryTest`, `security.AndroidAuditJournalTest`, `security.AndroidKeystoreAuditExportTest` | 14 | роли, audit.log, HMAC-экспорт |
| `security.InviteManifestContractTest` | 14 | exported = только InviteActivity + MainActivity(LAUNCHER); invite-ссылка → InviteActivity; details → никто |
| `security.LocalLab15TlsClientTest` | 15 | нужен запущенный локальный HTTPS-сервер из `RUN_MATRIX.md` + `adb reverse tcp:8443 tcp:8443` (скрипт `run_device_acceptance.sh tls` делает reverse) |

Для каждого класса в отчёт: passed / failed / skipped (+причина).

## 6. Доп. тесты с внешними условиями
**6.1 ЛР10 — настоящий публичный API** (на телефоне должен быть интернет):
```bash
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.realApi=true \
  -Pandroid.testInstrumentationRunnerArguments.class=com.phonoteka.data.RealPublicApiE2ETest 2>&1 | tee $D/06-realapi.txt
```
Ожидание: 4/4 passed (страницы 20+20 без дублей, детали+комментарии, поиск, 404).

**6.2 ЛР9 — Macrobenchmark** (если модуль собирается):
```bash
./gradlew -PwithBenchmarks=true :benchmark:connectedBenchmarkAndroidTest --no-daemon 2>&1 | tee $D/06-macrobench.txt
```
Результаты: `benchmark/build/outputs/connected_android_test_additional_output/` → скопируй в `$D/macrobench/`. Запиши startup (median/P90) и frame timing.

**6.3 ЛР13 — identity-backend с телефоном** (сетевая доступность через adb reverse):
```bash
java tools/backend/IdentityServer.java 8085 > $D/06-backend-server.txt 2>&1 &
adb reverse tcp:8085 tcp:8085
adb shell "curl -s -X POST -H 'Content-Type: application/json' -d '{\"login\":\"student\",\"password\":\"demo-pass-123\"}' http://127.0.0.1:8085/auth/login" | head -c 80; echo
```
(если на телефоне нет curl — пропусти и отметь). После — `adb reverse --remove tcp:8085; kill %<job>`. В UI клиент не подключён — это ожидаемо.

## 7. adb-проверки exported-компонентов (ЛР12/14/16)
```bash
P=com.phonoteka.labcheck
adb shell dumpsys package $P | grep -A3 -i 'exported=true' | tee $D/07-exported.txt
adb shell am start -n $P/com.phonoteka.TrackInviteActivity 2>&1 | tee $D/07-trackinvite.txt     # ожидание: SecurityException / Permission Denial / not exported
adb shell am start -a android.intent.action.VIEW -d 'phonoteka://invite?token=abcDEF12_-' 2>&1 | tee $D/07-invite-ok.txt
adb exec-out screencap -p > $D/07-invite-ok.png                                                # ожидание: форма регистрации сотрудника
for u in 'phonoteka://invite?token=short7x' "phonoteka://invite?token=$(printf 'a%.0s' {1..65})" \
         'phonoteka://invite?token=abcdefgh&role=ADMIN' 'phonoteka://invite?token=abcdefgh#x' \
         'phonoteka://evil@invite?token=abcdefgh' 'phonoteka://invite:8080?token=abcdefgh' 'phonoteka://invite'; do
  adb shell am start -a android.intent.action.VIEW -d "'$u'"; sleep 2
  adb exec-out screencap -p > "$D/07-invite-bad-$(echo "$u" | md5sum | cut -c1-6).png"
done                                                                                           # ожидание: везде экран ошибки, формы нет
adb shell am start -a android.intent.action.VIEW -d 'phonoteka://details/1' 2>&1 | tee $D/07-details.txt   # ожидание: «unable to resolve Intent» / ничего не открывается
adb shell am start -a android.intent.action.VIEW -d 'phonoteka://invite?token=abcDEF12_-' -e role ADMIN --ez admin true   # лишние extras игнорируются
```
Повторная регистрация с тем же token должна дать «Уже использовано». Экран приглашения — FLAG_SECURE: `screencap` даст чёрный кадр, это ОЖИДАЕМО (доказательство защиты) — отметь это, а состояние экрана проверяй через `uiautomator dump`:
```bash
adb shell uiautomator dump /sdcard/ui.xml && adb pull /sdcard/ui.xml $D/07-ui.xml && grep -o 'text="[^"]*"' $D/07-ui.xml | head -40
```

## 8. Ручные сценарии через adb/UI Automator
Управляй через `uiautomator dump` + `adb shell input tap X Y` / `input text` / `input keyevent`. На каждом ключевом шаге — скриншот `$D/08-<ЛР>-<шаг>.png`. Чего не можешь сам (отпечаток, скан физического QR камерой, системные диалоги с биометрией) — попроси человека, дай ему точную инструкцию и жди.

### ЛР8–9 — UI/производительность
1. Пройди все основные экраны (библиотека, поиск, профиль, трекер задач, каталог постов, настройки) в светлой и тёмной теме (Настройки → тема SYSTEM/LIGHT/DARK), скриншоты.
2. Крупный шрифт: `adb shell settings put system font_scale 1.3` → скриншоты, текст не обрезается; верни `1.0`.
3. Поворот экрана (`adb shell settings put system accelerometer_rotation 0; adb shell settings put system user_rotation 1`) на поиске и редакторе заметки — введённый текст сохраняется. Верни `user_rotation 0`.
4. Длинный скролл каталога: `adb shell dumpsys gfxinfo com.phonoteka.labcheck reset`, 10× `adb shell input swipe 500 1600 500 400 150`, затем `adb shell dumpsys gfxinfo com.phonoteka.labcheck > $D/08-gfxinfo.txt` — выпиши Janky frames %, 90th/99th percentile.
5. Debug Настройки → «ЛР9 · окно измерения»: старт → поиск/скролл/удаление → стоп → экспорт; сохрани файл.

### ЛР10 — сеть/офлайн
1. Открой каталог постов с интернетом → список грузится, пагинация при быстром скролле без дублей.
2. `adb shell svc wifi disable; adb shell svc data disable` → перезапусти экран: данные из кэша + сообщение об офлайне + кнопка «Повторить». Включи сеть обратно → «Повторить» работает.
3. Process death: открой детали поста и введи поисковый запрос → Home → `adb shell am kill com.phonoteka.labcheck` → вернись из Recents: запрос и открытый id восстановлены (SavedStateHandle).

### ЛР11 — файлы и заметки
1. Свободные заметки: создать 2 заметки → автосохранение → `am force-stop` → открыть снова: заметки на месте.
2. Формат файла (kotlinx.serialization): `adb shell run-as com.phonoteka.labcheck cat files/notes.json > $D/08-notes.json` (если путь другой — `run-as … find files -name '*.json'`). Проверь: `version: 1`, поля id/title/body/updatedAt.
3. Повреждённый файл: скопируй оригинал, запиши `{"version":1,"notes":[` через `run-as … sh -c 'echo … > files/notes.json'`, открой экран → сообщение о повреждении, файл НЕ перезаписан, редактирование заблокировано до решения пользователя. То же с `"updatedAt":"1"` (число в кавычках). Верни оригинал.
4. Экспорт/импорт через SAF: экспорт в Downloads, отмена picker'а ничего не меняет, импорт невалидного файла → ошибка, данные не тронуты.
5. Заметки к трекам (зашифрованные): `run-as … ls -R files/` — файлы `.enc`, текст заметки в них НЕ читается (`grep -r '<слово из заметки>'` — пусто). Корзина: удалить → восстановить.

### ЛР12–13 — локальная аутентификация
1. Первый запуск: установка PIN → показ recovery-кодов (коды НЕ записывай в отчёт; один запомни в памяти сессии для шага 4).
2. Неверный PIN 5 раз → блокировка с таймером (LOCKED); перезапуск приложения блокировку не сбрасывает.
3. Биометрия (если есть отпечаток): попроси человека приложить палец; отмена диалога → доступа нет.
4. **Новое:** восстановление по recovery-коду → на экране входа появляется «Уведомление отправлено: e-mail s***@…, SMS ********4567» (маскировано, без кода). Повторно тот же код — отказ. В журнале аудита — `recovery_notification_sent:email,sms`.
5. Автоблокировка: сверни приложение > тайм-аута → при возврате снова PIN. В Recents превью защищённых экранов скрыто.
6. Детали трека: если вход старше 5 мин — запрос PIN (step-up).
7. **Новое:** Настройки → «Заказы и результаты · ЛР13»:
   - сразу видны обычные заказы (testTag `ordinaryOrder`);
   - подожди > 5 мин после входа, нажми «Открыть результаты» (`openResults`) → пояснение + поле PIN (`stepUpPin`), результатов (`resultRow`) НЕТ;
   - неверный PIN → ошибка, результатов нет; верный → появились `resultRow`;
   - сразу после свежего входа — открывается без PIN.
8. Учебная сессия (mock auth): вход, refresh, выход; после выхода защищённые экраны недоступны. Process death после входа → access не переживает (только в памяти).

### ЛР14 — роли, пропуск, приглашения, аудит
1. Экран ЛР14 (Настройки): строка «Текущие scopes: …» для каждой роли:
   - EMPLOYEE: `library:read library:write pass:view`;
   - SECURITY: `library:read pass:view scan:execute`;
   - ADMIN: все 6.
2. **Новое:** «Показать пропуск» → карточка: фото-заглушка, имя/роль, QR, статус «Действует». Смена роли → карточка скрывается. QR отсканируй другим телефоном (попроси человека) — внутри НЕТ токена сессии.
3. Приглашения треков (TrackInviteActivity, внутренний вход из ЛР14): ADMIN создаёт QR (сначала PIN step-up; Cancel → QR не появляется). SECURITY сканирует: камера запрашивается только по кнопке, после rationale; отказ не ломает приложение; второй отказ → переход в настройки. Сброс разрешения: `adb shell pm revoke com.phonoteka.labcheck android.permission.CAMERA`. Скан: первый успех, повтор → REPLAY, через 5 мин → EXPIRED. EMPLOYEE не может ни выдать, ни сканировать. (Для скана нужен человек со вторым экраном.)
4. Журнал аудита: только ADMIN и только после BiometricPrompt (BIOMETRIC_STRONG) — попроси отпечаток. Для EMPLOYEE/SECURITY — отказ. Записи видны, секретов в них нет.
5. Экспорт аудита через SAF: после возврата из picker права проверяются заново (смени роль на EMPLOYEE пока открыт picker → файл НЕ записан). Файл скачай (`adb pull /sdcard/Download/<файл>`) — JSONL с HMAC-цепочкой.
6. Целостность audit.log: `run-as … cat files/audit.log | tail -3`; измени один символ в копии через run-as → приложение должно показать нарушение цепочки (сделай бэкап и верни).
7. Разрешения (сверь с `PERMISSIONS_LAB14.md`): `adb shell dumpsys package com.phonoteka.labcheck | grep -A30 'requested permissions' > $D/08-perms.txt`. POST_NOTIFICATIONS (API 33+) запрашивается только по кнопке с объяснением; запуск воспроизведения → уведомление плеера (foreground service); с отказом — музыка играет, уведомления нет. Сброс: `adb shell pm revoke com.phonoteka.labcheck android.permission.POST_NOTIFICATIONS`.

### ЛР15 — сеть/TLS/WebSocket
1. По `RUN_MATRIX.md` запусти локальные серверы (`tools/LocalLab15MockServer.java`, `tools/LocalLab15WssServer.java`), `adb reverse` нужных портов.
2. Debug → «ЛР15 live»: подключение WSS, сообщения идут; выключи сеть → переподключение с backoff; смена пользователя/выход → сокет закрывается.
3. `tools/LocalLab15TlsDowngradeTest.java` (на ПК) — TLS1.0/1.1 отвергаются.
4. Release-сборка: cleartext запрещён — `aapt dump xmltree app/build/outputs/apk/release/*.apk res/xml/network_security_config.xml` (или проверь файл в исходниках).

### ЛР16 — hardening
1. **Новое:** буфер обмена. В редакторе свободной заметки — «Копировать» (`noteCopy`). Сразу вставь в любое поле → текст есть. На Android 13+ всплывающее превью буфера показывает точки (sensitive). Через 31+ с — вставка пустая. Повтори: скопируй заметку, затем в течение 30 с скопируй другой текст другим приложением → через 30 с чужой текст ОСТАЛСЯ. Запиши результат для API устройства (на <28 очистка = пустой clip).
2. WebView в приложении отсутствует: `grep -rn 'WebView' app/src/main` — пусто.
3. FLAG_SECURE: скриншот экранов PIN/приглашения/аудита чёрный.
4. Release APK: `./gradlew :app:assembleRelease` — minify включён, `debuggable=false`, `allowBackup` согласно манифесту: `aapt dump badging app/build/outputs/apk/release/*.apk | head; aapt dump xmltree … AndroidManifest.xml | grep -i 'debuggable\|allowBackup\|exported'`.
5. Backup: `adb shell bmgr backupnow com.phonoteka.labcheck` (если доступно) — секреты и токены исключены правилами бэкапа.
6. Сигналы RASP (debug → диагностика): на настоящем телефоне emulator=false; с подключённым отладчиком экспорт аудита требует усиленного подтверждения.

## 9. Уборка
```bash
adb shell settings put global window_animation_scale 1
adb shell settings put global transition_animation_scale 1
adb shell settings put global animator_duration_scale 1
adb shell settings put system font_scale 1.0
adb shell settings put system accelerometer_rotation 1
adb shell svc wifi enable; adb shell svc data enable
adb shell svc power stayon false
adb reverse --remove-all
```

## 10. Формат итогового отчёта (`$D/DEVICE_REPORT.md`)
```
# Проверка на устройстве — <дата>
Устройство: <модель>, Android <версия> (API <N>); JDK; AGP 8.6.1

## Сборка
- assembleDebug/Release: OK/FAIL; JVM: X passed / Y failed; lint: E errors / W warnings
- Исправления кода: <файл — что и почему>

## Тесты на устройстве
| Класс | passed | failed | skipped (причина) |

## Ручные сценарии
| ЛР | Шаг | Ожидание | Факт | Доказательство (файл) | OK/FAIL/НЕ ПРОВЕРЕНО |

## Найденные баги
- <описание, шаги, лог, исправлен ли>

## Что не удалось проверить и почему
```
После отчёта обнови `ACTUAL_VALIDATION.md` (фактические результаты) и статусы в `LAB_REQUIREMENTS.md`: «частично / нужно проверить на устройстве» → «готово» только там, где есть доказательство из этого прогона.

## Дополнение после BUILD_REPORT (этап 16)
- Экран не должен гаснуть: `adb shell svc power stayon true`, `adb shell settings put system screen_off_timeout 1800000`, разблокировать телефон.
- Если прогон завис — прервать и гонять по одному классу (`-Pandroid.testInstrumentationRunnerArguments.class=<FQN>`). В отчёт написать, какой класс завис и на каком тесте (`adb logcat -d | grep TestRunner`).
- Ожидаемо зелёные JVM-тесты: 306/306 (кроме RealPublicApiE2E на устройстве — skipped без сети).
