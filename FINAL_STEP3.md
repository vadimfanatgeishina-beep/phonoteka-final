# Финальный кандидат — этап3: append-only аудит ЛР14

Основа — `Phonoteka-final-step2-source.zip`. Это продолжение единого приложения, НЕ готовность всех ЛР8–16. Результаты именно этого снимка: `lab_reports/final_step3/RESULTS.md`; прежние факты не наследуются новым Android-кодом.

## Изменения и точные пути

Все Kotlin пути ниже относительно `app/src/main/java/com/phonoteka/`.

| Пункт | Файл | Реальная реализация |
|---|---|---|
| Append-only JSONL | security/SignedAuditJournal.kt | sequence/timestamp/actor/role/action/subject/ALLOWED–DENIED/previousHash/HMAC; не перезапись списка последних200 |
| Цепочка и witness | тот же | HMAC каждого canonical row и подписанный checkpoint `audit.head` с count/byteLength/tailHash; tail deletion/reorder/edit/missinghead выявляются |
| Crash recovery | тот же | До append durable signed `audit.pending`; после partial write дописывается только недостающий suffix проверенного intent; committed bytes не изменяются/не удаляются |
| Android файлы/ключ | security/AndroidAuditJournal.kt | `filesDir/audit.log`, AtomicFile head/pending, file+directory fsync, bounded reads, per-path mutex+cross-process FileChannel lock,256bit HMAC KeyStore |
| Key loss | тот же | Missing key при имеющихся log/head/pending НЕ создаёт replacement key, НЕ сбрасывает историю; операции закрыты |
| Идентичность | security/MockRoleAuthority.kt | В mock grant собственный actorId; copy(actorId=forged) не принимается; auditActor неизвестной/отозванной сессии — UNVERIFIED, не доверяет заявленному ADMIN |
| Нет bearer в diagnostics | тот же | DemoSession.toString redacted; actorId в fixture — `mock:student`, это НЕ реальная пользовательская учётная запись |
| ADMIN read/export | security/AuditAccessGate.kt / DemoRoles.kt | Role/scopes отдельны от UI; fresh/step-up gate, повторная проверка grant, verified grant lock через protected read/export; отказы тоже в аудите |
| Конкретный subject | data/TrackInvites.kt / PhonotekaApp.kt | Invite outcome содержит track:id; collection/journal/permission scopes для generic gates. Raw QR, title, URI, body, token не логируются |
| Auth-denied trace | PhonotekaApp.kt | Отсутствующая свежая локальная auth пишет DENIED с unverified actor, не утверждает успешную идентификацию человека |
| SAF stale permission | ui/screens/Lab14AccessScreen.kt | После picker получатель/ADMIN/freshness проверяются заново; writeSignedExport работает под verified grant. Ранее подписанный payload не служит вечной лицензией |
| UI IO/state | тот же | Read/export/access checks через Dispatchers.IO, busy UI, старые результаты после смены сессии не отображаются; показ last200 явно, export ВСЕХ canonical rows |
| Legacy evidence | security/DemoRoles.kt | Старый валидный signed snapshot сохраняется отдельным `audit.legacy.snapshot.json`, SHA256 witnessed одной migration row; старые prefs не стираются. Нельзя восстановить утраченную прежним200-row cap историю |
| Экспорт HMAC | security/AndroidKeystoreAuditExport.kt | Existing envelope хранит ВСЕ raw JSONL rows с отдельным HMAC; namespaces для own tests изолированы от настоящего app journal/export key |

Canonical fields ASCII/typed/ограничены. Не допускаются unknown/duplicate поля, alternative JSON whitespace/number aliases, injected newline/control fields. Подпись rows/head/intent использует разные domain labels. Ordering — sequence, не предположение о монотонности wall clock.

## Протокол транзакции / recovery

1. Проверить всю committed цепочку и head, восстановить только valid pending intent.
2. Durable AtomicFile signed intent (exact previous checkpoint + будущая canonical line).
3. Append в audit.log + fd/directory sync.
4. Durable AtomicFile новый signed checkpoint.
5. Удалить только временный pending, sync directory.

Crash до durable intent: событие не изобретается. Crash после него: завершение ровно одной запланированной строки, без duplicate. Already committed head + оставшийся intent: только уборка intent. Corrupt intent/неожиданный tail/missing committed bytes — отказ без truncate/reset.

ALLOWED означает **решение доступа**, а не доказательство успешного выполнения следующей бизнес-операции. Если fsync/cleanup завершился ошибкой, вызывающая операция закрывается; при recovery может остаться её ранее запланированное разрешающее решение. Не объявлять такую строку runtime-успехом операции.

Изолированный AtomicFile `.new`, который не был опубликован, не считается committed head/intent; создание genesis разрешено лишь при отсутствии committed log bytes/pending. `.bak` восстанавливается AtomicFile. OEM/power-loss durability не доказывается JVM fault injection.

## Честные границы

- **Полный rollback** согласованной пары log+head вместе с остальным private filesystem НЕ выявляется этой локальной схемой. Root/скомпрометированный процесс может использовать signing key. Для сильной anti-rollback нужен внешний/аппаратный monotonic witness; здесь его нет. JVM test специально демонстрирует это ограничение, а не скрывает.
- JSONL — открытые безопасные metadata в app-private filesDir, не encrypted PII. Читать/экспортировать через app API может только ADMIN с configured fresh/step-up gate; KeyStore HMAC не требует пользователя, чтобы записывать отказы ещё до auth. Не путать это с биометрическим refresh key ЛР13.
- Legacy signed snapshot — отдельный архив прежнего ограниченного списка. Его не переименовывают в «полную историю» и не придумывают actor IDs/timestamps прошлого. Старые unsigned entries сохранены в prefs, но не считаются verified new JSONL evidence.
- Auth journal `LocalAuthManager.auditEntries` пока отдельный прежний signed snapshot с cap100; **этот этап заменяет access-аудит ЛР14**, не утверждает единый бесконечный auth+access журнал.
- Журнал bounded10000 rows/8MiB, при заполнении fail-closed, БЕЗ silent purge. Сами core read/jsonLines работают на полном verified журнале; public read/export тоже записывают decision и при hard capacity могут отказать. Automated archive/rotation policy пока нет.
- Полная verification O(n), включая KeyStore MAC; нагрузки и latency на Android НЕ измерены. Основной экран вынесен на IO, но остальные синхронные security gates могут задерживать вызывающий поток. Это local учебный кандидат, не production high-volume audit service.
- Обязательный именно native BiometricPrompt перед audit screen из дополнительного пункта ЛР14 ещё не интегрирован: configured fresh/risk gate поддерживает PIN step-up. Не засчитывать как completed biometric-only PRO.

## Тесты

- `app/src/test/java/com/phonoteka/security/SignedAuditJournalTest.kt` —22 JVM cases.250 событий без200-row pruning; intact committed prefix, same-size edit, valid tail truncate, reorder/middle delete, modified/missing head, missing log/wrong key, partial append,6 durable failure points, no-intent failure, forged intent/unexpected suffix, missing intent/replay/domain mismatch, capacity/injection, whole-pair rollback LIMITATION, concurrent instances.
- `.../AuditAccessGateTest.kt` —10 JVM cases: forged ADMIN/actor, fresh deny, export gate, write failure, actor/token redaction/revoke, subject types, revoke during gate, protected snapshot.
- `app/src/androidTest/java/com/phonoteka/security/AndroidAuditJournalTest.kt` —4 own namespace cases: real Keystore+file roundtrip, tail truncate, key deletion no reset, unpublished AtomicFile.new. Только compile/build, runtime требует Android.
- `.../AccessBoundaryTest.kt` — tampered stored audit test переведён с прежней prefs snapshot на новый isolated JSONL.

## Проверка / применение

```bash
chmod +x gradlew
./gradlew :app:testDebugUnitTest :app:lintDebug \
  :app:compileDebugAndroidTestKotlin :app:assembleDebug \
  :app:assembleDebugAndroidTest :app:assembleRelease \
  --no-daemon --max-workers=1 -Pkotlin.compiler.execution.strategy=in-process

# Только свой Android/AVD, здесь НЕ запускалось:
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.phonoteka.security.AndroidAuditJournalTest,com.phonoteka.security.AccessBoundaryTest,com.phonoteka.security.AndroidKeystoreAuditExportTest \
  --rerun-tasks

# Только чистая step2 source база:
git apply --check Phonoteka-final-step3.patch
git apply Phonoteka-final-step3.patch
```

Manual acceptance: EMPLOYEE/SECURITY read/export DENIED, ADMIN свежий разрешён; audit step-up denial recorded; переключение роли/expiry во время SAF picker не пишет журнал; отмена picker не пишет bytes; полная HMAC export verification и tamper failure; own interrupted write recovery не теряет committed prefix; missing key не обнуляет журнал; real filesystem directory fsync не выдавать за пройденный по source. Runtime UI negative для picker pending-role ещё нужно выполнить, не заменять его JVM gate test.

Debug APK signing может отличаться от прежнего. НЕ uninstall приложение с данными; отдельный own AVD либо собственная локальная сборка. Release unsigned. История Git этого пакета — synthetic imported snapshot, НЕ подлинный vulnerable/hardened baseline.

Остальной backlog: буквальные Profile/TaskTracker8/9, independent notes/autocommit11, auth KeyStore-loss recovery+runtime13, mandatory biometric audit step-up14, user-bound WSS15, isolated vulnerable/hardened16 и реальные Android acceptance всех механизмов.
