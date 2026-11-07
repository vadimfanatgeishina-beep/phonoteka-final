# Финальное приложение — этап 2: настоящий biometric refresh flow

Основа — `Phonoteka-final-step1-source.zip`. Изменения здесь продолжают единый кандидат, **не закрывают все ЛР8–16**. Результаты текущего снимка: `lab_reports/final_step2/RESULTS.md`. Step1 evidence исторические.

## Что изменено

- Удалён `LocalAuthManager.prepareBiometricCipher/completeBiometricAuthentication`, шифровавший постоянную proof-строку. Такой код не доказывал восстановление сессии.
- `AndroidBiometricRefreshVault.kt`: отдельный256bit AES-GCM KeyStore alias, auth-per-operation `BIOMETRIC_STRONG`, invalidation при enrollment; зашифрован именно текущий mock refresh token. Нет no-auth/plaintext fallback. Ciphertext публикуется `AtomicFile`, bounded read, AAD связывает версию/package/alias. Missing/invalidated key не превращается в новый decrypt key.
- `BiometricSessionFlow.kt`: ровно один memory-only ticket, cipher identity, expiry60s на monotonic clock, cancellation, session revision, single completion. Null/wrong/stale/duplicate callbacks не запускают endpoint и не дают свежую локальную аутентификацию.
- `MockAuthSession.kt`: biometric восстановление сверяет расшифрованный refresh с credential-store, проверяет возможность durable write ДО endpoint rotation, требует новый access от endpoint, сохраняет rotated refresh до успеха. Write failure отзывает old/new refresh, очищает client state. Preflight failure не сжигает серверный refresh.
- AAD update выполняется только ПОСЛЕ авторизации per-use Cipher; подготовка prompt не делает криптооперации над данными.
- Logout пытается revoke/clear даже при ошибке очистки biometric blob; экран блокируется в finally, независимо от cleanup результата. Если диск/endpoint недоступны, ошибка не маскируется как полный успешный logout.
- Новый login/normal refresh/logout заранее инвалидируют сохранённый biometric blob. Lock/expiry забывают memory-only access/current pair, но не отзывают ещё пригодный persisted refresh.
- `MainActivity.kt`: настоящий CryptoObject→decrypt→endpoint→persist→fresh local auth; отсутствие CryptoObject не успех. Foreground epoch/cancel-onStop запрещают unlock от позднего callback после rotation/background. Новая Activity не наследует pending ticket; требуется новая попытка.
- `AuthGateScreen.kt`: cold start с сохранённой biometric сессией не делает скрытый refresh до выбора пользователя; biometric restore либо system confirmation+PIN, либо новый mock login. Параллельный PIN/login во время biometric операции заблокирован.

## Почему есть отдельный optional enrollment prompt

Per-use DECRYPT Cipher нельзя переиспользовать для ENCRYPT нового токена. После успешного restore старый biometric blob потреблён; новый refresh уже хранится в credential-capable auth-bound store. **Отдельный необязательный второй prompt** шифрует rotated refresh per-use biometric key. Отмена этого второго prompt НЕ отменяет уже успешно восстановленную сессию; следующий вход остаётся через system authentication+PIN. Первый PIN вход также предлагает такой enrollment.

Это намеренно прозрачный, безопасный двухоперационный UX, не попытка получить silent re-encrypt с неавторизованным Cipher. Без second prompt biometric shortcut не сохраняется. На некоторых Android/OEM recent biometric может не открыть отдельный300s credential-capable key: preflight отклонит restore без endpoint consume; требуется fallback. Этот compatibility пункт требует устройства и не объявляется выполненным.

Новый biometric enrollment нельзя сделать одной константной proof строкой. Старый alias с proof не используется для восстановления; переустановка не выполнялась и имеющиеся пользовательские данные не удаляются.

## Границы защиты

- Local mock endpoint — не настоящий удалённый security boundary. Root/инструментированный процесс не защищён от владельца устройства; anti-rollback всего filesystem не заявлен.
- Существующий fallback refresh остаётся AES-GCM за hardware/auth-required key с300s system auth; приложение PIN не заменяет Android authentication. Два знания password+app PIN сами по себе не MFA: отдельная составляющая — auth-bound device key.
- Java/Kotlin String refresh кратковременно нужен endpoint; mutable plaintext bytes vault обнуляются, но гарантировать zeroization JVM String нельзя. Ни tokens, ни cipherbytes, ни raw exceptions не пишутся в UI/log/reports. Ticket/request toString redacted.
- Key invalidation при biometric enrollment: restore отклоняется; явный ENROLL после успешного fallback/PIN может заменить лишь invalidated biometric key. Credential/session namespace не сбрасывается молча.
- Device runtime, enrollment change, реальный process kill, rotation во время native prompt, per-use KeyInfo и restore/reenroll/cancel видео ещё не выполнены в этой среде.

## Тесты / команды

`BiometricSessionFlowTest.kt` —22 JVM policy cases: real mock refresh rotation, separate reenroll, wrong/null/duplicate/cancel/timeout/backwards-clock/replaced-ticket/session-changed callbacks, revoke/expiry/mismatch, preflight locked, write-after-rotation failure, biometric invalidation ordering, no-session enrollment. Fake vault проверяет orchestration, НЕ эмулирует hardware authorization.

`BiometricRefreshVaultPolicyTest.kt` —новый instrumentation negative/policy:256bit/per-use/auth-required/enrollment invalidation, unauthorized Cipher не публикует blob. Требуется secure own Android и biometric enrollment; отсутствие предусловий отдельно фиксируется как skip, не success roundtrip.

```bash
./gradlew :app:testDebugUnitTest :app:lintDebug \
  :app:compileDebugAndroidTestKotlin :app:assembleDebug \
  :app:assembleDebugAndroidTest :app:assembleRelease \
  --no-daemon --max-workers=1 -Pkotlin.compiler.execution.strategy=in-process

# На собственном secure устройстве; здесь НЕ запускалось:
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.phonoteka.security.BiometricRefreshVaultPolicyTest \
  --rerun-tasks
```

### Ручные acceptance критерии

1. Own mock login → system credential → PIN setup/entry → optional enrollment success; blob ciphertext, proof string отсутствует.
2. Убийство процесса → cold start → biometric restore → rotated endpoint session, protected screen; повтор старого refresh отклонён. Никакие значения tokens не выводить.
3. Отменить ПЕРВЫЙ prompt: no unlock/no rotation. Wrong biometric оставляет prompt живым, terminal error закрывает action. Wrong/missingCrypto callbacks проверены JVM seam.
4. Успешно restore, отменить ВТОРОЙ enrollment: текущая сессия доступна, следующий cold entry только fallback; не обещать biometric shortcut.
5. Rotation/background во время prompt: no stale unlock; новая попытка после resume. Device credential preflight failure: server refresh не consume, доступ через system+PIN.
6. Expired/revoked/tampered blob: никакой свежей local auth; новый mock login/fallback. Enrollment change: старый per-use key не восстанавливает session; явная новая настройка после login/PIN.
7. Logout: encrypted fallback+biometric blobs очищены, old refresh revoked, cold start не восстанавливает session. Lock: current/access память забыта, persisted refresh оставлен для подтверждённого restore.
8. Запись после rotation failing: new/old refresh revoked, memory/store очищены; новый mock login, не retry old token.

Применение unified diff только к чистой step1 source: `git apply --check <patch>` затем `git apply <patch>`. Synthetic Git baseline этой подготовки не подлинные vulnerable/hardened tags.

## Что остаётся за этим этапом

Append-only actor/subject security audit ЛР14, literal profile/TaskTracker8/9, independent notes/autocommit11, user-bound WSS tickets15, isolated vulnerable/hardened16 и actual Android acceptance. Изменение biometric code не превращает старые device facts в PASS нового снимка.
