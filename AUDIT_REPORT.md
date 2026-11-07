> **Текущий этап финальной версии:** см. FINAL_STEP1.md и lab_reports/final_step1/RESULTS.md. Текст ниже сохраняет исторические source-review наблюдения; заявления «не собиралось/не реализовано» нельзя переносить на текущий снимок без сверки с новым ledger. Это не завершённый аудит ЛР16.

> Актуальный этап: FINAL_STEP3.md / lab_reports/final_step3/RESULTS.md. Предыдущие отчёты исторические; Android acceptance не наследуется.

> Текущий этап: FINAL_STEP2.md / lab_reports/final_step2/RESULTS.md. Предыдущие evidence относятся к предыдущим снимкам; device acceptance не наследуется.

> **Следующее дополнение подготовлено:** риск-ориентированный step-up, private QR Activity/camera, server-side downgrade regression, измерительные инструменты. См. MISSING_FEATURES.md / MISSING_VERIFICATION.md. Новые тесты/сборки не запускались; прежние заявления об отсутствии этих исходников ниже исторические.

> Обновление: текущие фактические результаты и оставшиеся ограничения — ACTUAL_VALIDATION.md и lab_reports/current_validation/. Старые заявления «не запускалось» ниже описывают момент подготовки предыдущего кандидата, не текущий итог; device acceptance и исторический baseline не считаются пройденными.

# ЛР16 — аудит подготовленного full-source candidate

## Область и достоверность

Объект: исходный ZIP v3 + priority fixes + full-source расширение. Это **source-review/preparation**, не повторный Android аудит.
Исторический отчёт полностью сохранён в `lab_reports/lab16/AUDIT_REPORT_v3_historical.md`; старые compiler/static evidence не относятся к новой версии.
По сообщению владельца v3: JVM 68/68, lint 51 warning/0 errors, release/androidTest APK собраны; отдельные KeyStore/AES-GCM/TLS/HMAC device cases прошли. Connected suite не завершён: отсутствовала TLS fixture, UI runner завис. Причина всех зависаний не доказана одним сообщением о спящем экране.
Расширенный код/CI/scanner/benchmark **не собраны и не запускались**; в среде подготовки отсутствуют javac/kotlinc. Проверки diff/package — механические, не runtime-доказательства.

## Исторические находки (не переоценивать без baseline)

| ID | Исходная находка | Подготовленная контрмера | Статус нового снимка |
|---|---|---|---|
| F-01 | backup-enabled | allowBackup=false/extraction exclusions | код сохранён; runtime/merged manifest нужны |
| F-02 | отсутствие local auth | связанный mock/system/PIN/recovery, monotonic freshness, secure windows | интеграция подготовлена; полный device acceptance открыт |
| F-03 | неограниченный downloader | byte/playlist/segment caps, recursion cap, downgrade redirect reject | unit negatives подготовлены; тесты не запускались |
| F-04 | destructive Room fallback | удалён fallback; future schema export | неизвестный old schema upgrade НЕ закрыт |
| F-05 | URL в диагностиках | прежние redactions сохранены + safe auth/socket messages | актуальный logcat/secret scan открыт |
| F-06 | release без minify/shrink | R8/resource shrinking сохранены | новый release/runtime не проверен |

CVSS vectors из исторического отчёта не считаются заново подтверждёнными. Unminified release — hardening gap, не самостоятельный exploit. Исторического API-key leak не добавлено: отсутствие gitleaks scan не доказывает ни наличие, ни отсутствие секрета.

## Дополнительные source-review дефекты и патчи

| ID | Дефект / воспроизводимый regression | Изменение | Что считается закрытием |
|---|---|---|---|
| P-01 | +30s signature first accepted at t, replay at t+31 still valid | retention through signed expiry HTTP/WSS/Java | future/boundary regressions pass |
| P-02 | rotated refresh persists unsuccessfully | revoke replacement/old, clear memory/store, safe typed failure | before/after-write failures, failed-clear and restart tests |
| P-03 | detail snapshots taken before API refresh | continuous Room combine + generation/cancel | empty cache/new Room update/old late error tests |
| P-04 | gap between two backup renames | AtomicFile + legacy .old recovery | interrupted write on own Android tests |
| P-05 | any IOException counted as wrong-pin pass | specific SSLPeerUnverifiedException + pinning reason | connection-refused no longer passes negative test |
| P-06 | plain stored audit later signed on export | authenticated stored snapshot, verify before record/export | tampered stored prefs fail before action |
| P-07 | role supplied by client only | opaque mock registry + UI/VM/repository guards | forged scope/token/admin and direct repository test |
| P-08 | refresh AES not auth-bound | auth-required production alias, separate no-auth fixture | fresh auth/cancel/expiry/key invalidation test |
| P-09 | WSS absent from app | debug local client/fixture, bounded reconnect, metrics, direction/channel binding | owned TLS/WSS/reconnect/reflection acceptance |
| P-10 | incomplete file PRO | per-track encrypted revisions/index/backup/trash/migration/draft | ≥4 repository unit tests plus real key/SAF cases |

Команды для каждого повторного теста в RUN_MATRIX.md. Ни одна строка таблицы не является результатом запуска.

## Остаточные блокеры сдачи

- Настоящий baseline commit `eadf4b0` и историческая Room schema/DB отсутствуют в ZIP. `git show` и migration evidence нельзя честно воспроизвести из этих исходников.
- Новые lint/CVE/secret scans отсутствуют. Inventory и правила — подготовка, не clean scan.
- No-auth fixture tests не доказывают production key policy; secure lockscreen/negative auth window нужен отдельно.
- Snapshot MAC не защищает от полного rollback валидного snapshot root-владельцем; локальная role authority не production backend.
- Заметки — адаптация к музыкальным track IDs. Камера/InviteActivity/physical QR pass не реализованы без назначенного сценария.
- Risk-based step-up/real identity server не реализованы; basic 5-minute local gate подготовлен.
- Actual TalkBack/SAF/biometric/process death/backup/logcat/downgrade/MITM/performance evidence не приложены.
- Новый APK не собран, не подписан production key и не проверен после R8. Safe source ZIP не заменяет protected APK.

## Передача без приватных материалов

`tools/package_submission.py` пакует только allowlisted исходники, gradle wrapper, публичные `.cer`, отчёты и tools. Не обходит `work/`, не включает `.git`, private key/PKCS12/keystore/env/build outputs.
Исторический private deploy artifact из исходного v3 не копируется и не используется. Его действительный отзыв должен подтвердить владелец; публикация ключа/его значений не выполняется.
`.gitlab-ci.yml` — конфигурация собственного runner с JDK17/SDK34, не запуск CI и не публикация в GitLab.
