# Команды/критерии следующего дополнения

> Обновление: текущие фактические результаты и оставшиеся ограничения — ACTUAL_VALIDATION.md и lab_reports/current_validation/. Старые заявления «не запускалось» ниже описывают момент подготовки предыдущего кандидата, не текущий итог; device acceptance и исторический baseline не считаются пройденными.

JDK17/SDK34 установлены для фактических локальных проверок. Получены JVM112/112, lint58 Warning/4 Information/0 Error и результаты Java/SAST/secret/OSV; точный build/device итог — ACTUAL_VALIDATION.md. Остальные команды ниже — acceptance plan, не результат.

## Компиляция и JVM (из корня)
```bash
bash tools/verify_local.sh
# Только новые JVM группы:
./gradlew :app:testDebugUnitTest --tests 'com.phonoteka.security.RiskStepUpTest' --tests 'com.phonoteka.security.MockInviteAuthorityTest' --tests 'com.phonoteka.security.OwnedQrEncodingTest' --tests 'com.phonoteka.ui.permissions.PermissionFlowPolicyTest' --tests 'com.phonoteka.viewmodel.TrackInviteViewModelTest' --tests 'com.phonoteka.viewmodel.SettingsPersistenceTest' --tests 'com.phonoteka.data.notes.AuthorizedNotesStepUpTest' --rerun-tasks
```
CameraX/ZXing — новые dependencies; нужен доступ сборки к Google/Maven repositories. Наличие diff не доказывает dependency resolution или совместимость CameraX/Compose/R8.

## Server-side TLS downgrade на своём PKCS12
После `verify_local.sh` (Java классы собраны) и локальной установки владельцем PHONOTEKA_MOCK_KEYSTORE / PHONOTEKA_MOCK_KEYSTORE_PASSWORD:
```bash
java --add-modules jdk.httpserver -cp .local/java-fixtures LocalLab15TlsDowngradeTest > .local/evidence/server-tls-downgrade.txt 2>&1
```
PASS только если BOTH позитивные handshakes1.2/1.3 и BOTH старые ClientHello получили server fatal protocol_version. Certificate mismatch/EOF/client policy refusal/другой alert — fail, не «MITM блокируется». Работает на ephemeral loopback, production сеть не используется.

## Instrumentation и ограниченный runner
Своё ANDROID_SERIAL задайте локально, экран разблокируйте вручную:
```bash
bash tools/run_device_acceptance.sh core
bash tools/run_device_acceptance.sh ui
bash tools/run_device_acceptance.sh invite
# После свежего Android system confirmation:
bash tools/run_device_acceptance.sh auth-bound
# После запуска своего HTTPS fixture из RUN_MATRIX.md:
bash tools/run_device_acceptance.sh tls
```
exit124 — hung runner, не pass. Awake не гарантирует unlocked; это владелец подтверждает сам. Проверить XML на failed/skipped, а не только exit.

Прямой вызов закрытой Activity из shell (без лишних extras/секретов):
```bash
adb -s "$ANDROID_SERIAL" shell am start -n com.phonoteka.labcheck/com.phonoteka.TrackInviteActivity   # ожидание: SecurityException (exported=false)
adb -s "$ANDROID_SERIAL" shell am start -a android.intent.action.VIEW -d 'phonoteka://invite?token=abcDEF12_-'   # ожидание: форма регистрации
adb -s "$ANDROID_SERIAL" shell am start -a android.intent.action.VIEW -d 'phonoteka://invite?token=bad'   # ожидание: ошибка формата
adb -s "$ANDROID_SERIAL" shell am start -a android.intent.action.VIEW -d 'phonoteka://details/1'   # ожидание: нет обработчика
```
Критерий: Permission Denial/not exported, не открывается QR. Сам Intent из своего приложения допустим только при свежем login; чужой caller не получает доступ. Manifest test не заменяет direct-ADB evidence.

## Ручные functional acceptance
1. Учебный вход → ADMIN → ЛР14 → приглашение. Первый create требует PIN step-up; Cancel — QR не появляется, mutation не выполняется. Верный PIN → повтор create для существующего ID.
2. Экспортировать QR только на свой второй экран; переключить mock роль на SECURITY, снова открыть invitation screen. Камера запрашивается только по кнопке после rationale. Отказ не ломает библиотеку; повторный отказ ведёт к settings.
3. Сканировать QR: один успех; повтор — REPLAY; новый code после 5m — EXPIRED; после process restart/revoke — отказ. EMPLOYEE ни выдаёт, ни сканирует. Подмена ID/signature — отказ. Кадры/QR/ключи не должны появляться в logcat.
4. Изменить роль/закрыть экран пока issue/scan ожидает: late response не должен вернуть receipt. Rotation не повторяет consume; permission revoke в settings — камера не открывается без grant.
5. Library clear/import, notes reset/import, audit export: сначала deny + step-up, затем повтор после нового PIN. Approval60s только для операции и auth version. Cancel/timeout/role switch/logout/process death не сохраняют approval. Debugger/background≥60s усиливают экспорт/scan, emulator сам по себе не банит приложение.
6. Сверить audit: `.role` allowed НЕ означает выполненную операцию; `.stepup` denied и итоговый resource allowed записываются отдельно. Actual issue/scan success записывается после authoritative результата.

## Измерения и сканеры
Debug Настройки → «ЛР9 · окно измерения»: старт → один tick/search/scroll/delete → стоп/экспорт. Повторить одинаковые сценарии на настоящем baseline и текущей версии. Не заполнять before/after таблицу заранее.
```bash
mkdir -p .local/evidence
python tools/summarize_compose_reports.py app/build/compose_reports/app_debug-composables.txt .local/evidence/unstable-functions.md
bash tools/run_security_scans.sh
# Только в настоящем репозитории с историей:
bash tools/run_security_scans.sh history
```
Название compiler report зависит от compiler/plugin; если файла нет, найти существующий `*-composables.txt` в app/build/compose_reports и передать ТОЧНЫЙ путь. Старый lab_reports/lab9 не новый результат. SCA требует отдельного актуального CVE tool/db и triage, inventory им не становится.

Controlled migration test проверяет текущую модель2→3; для подлинного historical upgrade нужны реальные schema/DB владельца. Не создавать выдуманные schema JSON или baseline tags.
