> **Следующее дополнение подготовлено:** риск-ориентированный step-up, private QR Activity/camera, server-side downgrade regression, измерительные инструменты. См. MISSING_FEATURES.md / MISSING_VERIFICATION.md. Новые тесты/сборки не запускались; прежние заявления об отсутствии этих исходников ниже исторические.

> Текущий фактический итог нового кандидата: ACTUAL_VALIDATION.md / lab_reports/current_validation/. Ниже сохранён исторический план/контекст; сообщения о неподтверждённой сборке относятся к предыдущему source-only этапу. Device/historical критерии остаются открытыми, если не подтверждены отдельно.

# Phonoteka: полный исходный комплект-кандидат ЛР8–16

База — ZIP `Phonoteka-Notion-updated-2026-10-08-v3.zip`, затем priority fixes, затем расширение.
**Это подготовленный код/тесты/инструкции, не проверенная Android-сборка и не заявление о закрытии всех лабораторных.**
В этой среде не найдены javac/kotlinc; Android-сборка не настроена, компиляция и тесты расширенного комплекта не выполнялись.
Проверяются только структура, отсутствие приватных артефактов в allowlist-пакете и точное механическое применение diff.

## Как интегрировать актуальное расширение

Выберите ровно один вариант (подробнее MISSING_FEATURES.md):
1. Чистый v3 → Phonoteka-expanded-from-v3.patch.
2. v3 + пять прежних priority patches → Phonoteka-expanded-after-priority.patch.
3. Предыдущий FULL комплект → Phonoteka-missing-after-full.patch.
4. Phonoteka-expanded-source-candidate.zip — все изменения уже внесены, патчи не нужны.

Из корня отдельной ветки: `git apply --check <chosen.patch>`, затем `git apply <chosen.patch>`.
Не смешивать варианты и не применять старые patches поверх expanded source ZIP.

## Подготовлено

- ЛР8: существующие компоненты/тема сохранены; усиленные UI-тесты + runtime-generated PNG light/dark из отдельной тестовой композиции без личных данных.
- ЛР9: управляемый suspend-gate автосохранения; реальный STARTED-only timer; UI semantics/resource IDs; опциональный Macrobenchmark без auth bypass.
- ЛР10: живые detail flows, отмена stale load, detail SavedStateHandle; сериализация страниц, сеть retry 1/2/4 секунды (3 повтора после первого запроса); raw query не отстаёт от ввода; локальный Retrofit+HTTPS+настоящий Room contract test.
- ЛР11 базовая: AtomicFile/recovery и SAF IO вне UI thread. PRO: заметки существующих треков, отдельные AES-GCM revisions, HMAC index, previous backup, корзина 30 дней, v1→v2 import, лимиты 5 МБ/10 000, encrypted drafts, типизированные ошибки/подтверждаемый key-loss reset, repository JVM tests.
- ЛР12: модель v3 и честная атомарная сверка; прежние ALE-оценки не выдаются за измеренную статистику.
- ЛР13: mock password → системное подтверждение ключа → PIN; recovery + mock password; auth-bound AES key 300 секунд (Android credential/strong biometric), no-auth fixture отдельно для crypto tests; прежний unbound blob инвалидируется, ledger хешированный; rotation failure fail-closed; PIN freshness monotonic; подписанный локальный auth audit; FLAG_SECURE и overlay hiding Android 12+.
- ЛР14: opaque grants из mock authority; forged role/scopes/token не авторизуют; проверки в UI/VM/repository; исходный access audit подписан ДО экспорта; UI объясняет notification permission при первом запросе; role switch debug-only, release EMPLOYEE.
- ЛР15: HTTP/WSS/Java replay retention; bounded signed body; owned localhost-only signer; WSS reconnect/backoff/metrics, новая сессия канала на reconnect, направления request/response исключают reflection; debug-only экран/fixture, release системные CA. Собственный certificate-substitution regression вместо перехвата чужого трафика.
- ЛР16: downloader negative tests (size/chunked/playlist/HTTPS→HTTP), ограничение HLS recursion, Room schema export; SAST rule, CI для собственного runner, безопасный source packager и verification script.

## Ограничения, не маскируемые кодом

- Auth-bound key не открывается собственным PIN приложения: нужен Android system credential/strong biometric. Без secure lockscreen эта вертикаль закрыта fail-closed; нет небезопасного fallback.
- Тест no-auth refresh использует отдельные prefs/alias и НЕ подтверждает production auth-bound policy. Для `AuthBoundRefreshPolicyTest` нужна свежая системная аутентификация; отсутствие secure lockscreen — skipped precondition, не pass.
- Полный откат подписанного audit/index к старой валидной копии root-владельцем не предотвращён аппаратным monotonic counter. HMAC обнаруживает изменение, не гарантирует anti-rollback.
- Mock authority находится в том же процессе: это учебная граница, не production server. Role switch не связан с реальными аккаунтами/JWT.
- Встроенный импорт assets — trusted bootstrap, не пользовательская операция. Пользовательские mutation API проверяют role.
- Notes PRO адаптирует объект задания к существующему track ID; это не буквальная копия независимого приложения заметок. Базовый экспорт фонотеки остаётся schemaVersion 1; отдельный notes export — schemaVersion 2, обычный JSON по SAF.
- Draft cache — лучшее усилие при debounce; мгновенная гибель процесса до задержки может потерять последний символ. SavedStateHandle и cache не заменяют реальные process-death тесты.
- AtomicFile и immutable revisions тестируют прерванную запись логически; реальный power loss, межпроцессные записи, OEM-filesystem durability не подтверждены.
- WSS Java fixture минимален: masked complete text frames, без compression/fragmentation; только loopback, собственный сертификат; не публичный сервер.
- В debug APK допускается только публичный учебный fixture key из `PHONOTEKA_MOCK_HMAC_KEY`; release поле пусто. Настоящие сервисные секреты запрещены. В чате/документах значений нет.
- CI/benchmark/Semgrep-конфигурации также не запускались. Их наличие не является доказательством успешного прогона.

## Что ещё необходимо фактически сделать владельцу

1. JDK17/SDK34: compilation, JVM, lint, release/androidTest APK; результаты именно новой версии.
2. Android 13 + свежая system authentication: auth-bound store, PIN/recovery, cancel/rotation, lockout, logout/process death, tamper/access boundary.
3. UI, TalkBack, SAF отмена/битый файл/перезапись, роли/permission/deep link.
4. Свой localhost HTTPS/WSS fixture + adb reverse 8443/8444; invalid signature/query/time, replay future/boundary, wrong pin, certificate substitution, reconnect, TLS downgrade.
5. Layout Inspector до/после и Macrobenchmark на одинаковых данных/устройстве. Никакие численные результаты не заполнены заранее.
6. Lint warning triage, SCA (inventory не scan), gitleaks directory + настоящая история Git, Semgrep.
7. Историческая Room schema/БД прежней установки и фактический исходный Git commit для baseline/tagging; эти данные отсутствуют в ZIP. Их нельзя придумать.

Все команды и критерии — в RUN_MATRIX.md; границы лабораторных — в LAB_REQUIREMENTS.md.

Auth-bound instrumentation также использует отдельный UUID namespace (та же policy-конфигурация, НЕ рабочий refresh token/alias); тест не должен заменять действующую сессию. AccessBoundaryTest сохраняет ContextWrapper.applicationContext, чтобы tamper проверял только отдельные test prefs.
