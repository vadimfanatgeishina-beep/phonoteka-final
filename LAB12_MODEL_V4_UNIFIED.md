# ЛР12 — модель v4 объединённой Фонотеки (PRO)

**Версия:** 4.0 — обновление для объединённой архитектуры с двумя сборками.

**Объект:** Phonoteka, unified codebase, product flavors `vulnerable`/`hardened`.
**Статус:** контрмеры реализованы в коде; верификация сборок и тестов выполнена (см. Stage I report)

Этот документ дополняет v1/v2/v3, а не заменяет их. Ключевое изменение — две сборки из одного кода (`SecurityProfile`, `SecurityDecisions`) и единый журнал (`UnifiedAuditLog`).

## 1. Дельта относительно v3

| Аспект | v3 | v4 (текущая) |
|---|---|---|
| Сборки | единая debug | две: `vulnerableDebug` + `hardenedDebug`, разные `applicationId` |
| Политика безопасности | флаги в коде | `SecurityProfile` читает `BuildConfig`; `SecurityDecisions` централизованно решает |
| QR билет | одноразовый всегда | `singleUse = SecurityDecisions.qrTicketSingleUse()` — уязвимая разрешает повтор |
| OTP recovery | одноразовый всегда | `used_recovery_macs` ledger включён только при `otpSingleUse()` |
| Step-up / fresh auth | всегда | gated by `requireStepUp()` / `requireFreshAuthForLibrary()` |
| NSC | system+user CA | hardened: system only, cleartext false; vulnerable: system+user, cleartext true |
| allowBackup | true | hardened: false; vulnerable: true |
| SPKI pins | нет | оба варианта: primary + backup, fail-closed для localhost |
| Заметки/архив | JSON без картинок | PTKA1 зашифрованный архив с картинками; JSON явно без картинок |
| WSS URL | wss:// напрямую | `normalizeLabWsUrl` конвертирует wss→https; ws:// запрещён |
| Смена пользователя | нет | `DemoRoleSession.switchUser` + `PrincipalChangeSignal` закрывает сокет |
| Журнал | раздельные | `UnifiedAuditLog` — hash-chained JSONL для ЛР13/14/15/16 |

## 2. STRIDE — обновлённый реестр

| ID | Категория | Угроза | Контрмера (hardened) | Уязвимая сборка | Остаток |
|---|---|---|---|---|---|
| V4-01 | S | Recovery code replay | `used_recovery_macs` ledger, OTP single-use | Нет проверки — повтор разрешён | Root может очистить prefs |
| V4-02 | S | QR ticket replay | `MockInviteAuthority.singleUse=true`, билет сгорает | `singleUse=false` — повторное сканирование | In-process mock, не настоящий сервер |
| V4-03 | E | Доступ к библиотеке без fresh auth | `requireFreshAuthForLibrary()` → SecurityException | Проверка пропущена | Fresh auth окно 300s by design |
| V4-04 | E | Step-up bypass для sensitive actions | `requireStepUp()` → biometric + risk | Проверка пропущена | Biometric bypass на root |
| V4-05 | T | MITM TLS сертификат | NSC system CA only + SPKI pins fail-closed | User CA trusted, cleartext true | Pin rotation lifecycle |
| V4-06 | T | WSS frame reflection/replay | channel suffix, per-connection UUID, HMAC signer | Те же контрмеры (код общий) | Общий mock HMAC key |
| V4-07 | T | Смена пользователя не закрывает сокет | `PrincipalChangeSignal` → `onPrincipalChanged()` → close | Те же (код общий) | Signal race в async |
| V4-08 | I | Backup извлекает данные | `allowBackup=false`, `fullBackupContent=false` | `allowBackup=true` | Root backup extraction |
| V4-09 | I | Cleartext утечка трафика | `usesCleartextTraffic=false` + NSC | `usesCleartextTraffic=true` | — |
| V4-10 | R | Действие без аудит-записи | `UnifiedAuditLog` hash-chained, record before action | Те же (код общий) | Chain break detection |
| V4-11 | T | Повреждённый архив заметок — тихий успех | `NoteArchiveCodec` SHA-256 digest + count checks → `NoteException` | Те же (код общий) | — |
| V4-12 | I | JSON экспорт содержит картинки | `Export.attachmentsOmitted=true`, UI явно предупреждает | Те же (код общий) | — |

## 3. Attack tree — двухсборочный сценарий

```
Цель: получить доступ к защищённым данным/функциям
├── 1. Обойти аутентификацию
│   ├── 1.1. Recovery code replay
│   │   ├── hardened: used_recovery_macs → REPLAY_DENIED ✓
│   │   └── vulnerable: нет проверки → повтор принят (демо)
│   ├── 1.2. QR ticket replay
│   │   ├── hardened: singleUse → билет сгорает ✓
│   │   └── vulnerable: билет не сгорает → повтор (демо)
│   └── 1.3. Fresh auth bypass
│       ├── hardened: requireFreshAuthForLibrary → DENIED ✓
│       └── vulnerable: проверка пропущена → доступ (демо)
├── 2. Перехват трафика
│   ├── 2.1. MITM с user CA
│   │   ├── hardened: system CA only → отклонён ✓
│   │   └── vulnerable: user CA trusted → перехвачен (демо)
│   └── 2.2. Cleartext fallback
│       ├── hardened: cleartext false → заблокирован ✓
│       └── vulnerable: cleartext true → разрешён (демо)
└── 3. Извлечь данные
    ├── 3.1. Backup
    │   ├── hardened: allowBackup false → нет backup ✓
    │   └── vulnerable: allowBackup true → backup доступен (демо)
    └── 3.2. Заметки из архива
        ├── corrupt archive → NoteException, не тихий успех ✓ (оба)
        └── JSON export → картинки отсутствуют, UI предупреждает ✓ (оба)
```

## 4. Defense plan — контрмеры по Kill Chain

| Kill Chain stage | Контрмера | Реализация | MASVS |
|---|---|---|---|
| Reconnaissance | NSC скрывает сетевую конфигурацию | `network_security_config.xml` per variant | MSTG-NETWORK-2 |
| Delivery | Deep link validation | Схема/host/path проверяются в Navigation | MSTG-PLATFORM-3 |
| Exploitation | Step-up biometric | `BiometricStepUpGate` + risk assessment | MSTG-AUTH-5 |
| Installation | Backup protection | `allowBackup=false` в hardened | MSTG-STORAGE-8 |
| Command & Control | WSS pinning + HMAC | SPKI pins + `HmacTextFrameSigner` | MSTG-NETWORK-3/4 |
| Actions on Objectives | Audit trail | `UnifiedAuditLog` hash-chained | MSTG-STORAGE-5 |

## 5. Detection points

| Событие | Источник | Audit type | Что записывается |
|---|---|---|---|
| Login success/fail | AUTH | AUTH_LOGIN | user, result, timestamp |
| Recovery replay denied | AUTH | RECOVERY_REPLAY_DENIED | user, mac digest |
| QR scan | ACCESS | QR_SCAN | actor, role, trackId, result |
| Access denied | ACCESS | ACCESS_DENIED | resource, reason |
| WSS connect/disconnect | NETWORK | WSS | boundUserTag, channel |
| User switch | NETWORK | WSS_USER_CHANGED | oldActor → newActor |
| Note archive import | STORAGE | NOTE_IMPORT | count, result |

## 6. Количественная оценка риска (DREAD для V4 угроз)

| ID | D | R | E | A | Dsc | Среднее | Уровень |
|---|---|---|---|---|---|---|---|
| V4-01 | 8 | 7 | 5 | 4 | 6 | 6.0 | Средний |
| V4-02 | 6 | 8 | 6 | 4 | 7 | 6.2 | Средний |
| V4-03 | 7 | 7 | 5 | 5 | 6 | 6.0 | Средний |
| V4-04 | 7 | 6 | 4 | 4 | 6 | 5.4 | Средний |
| V4-05 | 9 | 5 | 4 | 5 | 5 | 5.6 | Средний |
| V4-08 | 8 | 4 | 4 | 5 | 6 | 5.4 | Средний |

Примечание: оценки для hardened сборки. В уязвимой сборке V4-01..V4-05 и V4-08 имеют E=9-10 (легко эксплуатируется), что поднимает среднее до 7.0-8.0 (высокий).

## 7. MASVS mapping

| MASVS control | Статус в hardened | Статус в vulnerable |
|---|---|---|
| MSTG-AUTH-5 (step-up) | ✓ Enforced | ✗ Skipped |
| MSTG-AUTH-8 (OTP single-use) | ✓ Enforced | ✗ Skipped |
| MSTG-NETWORK-2 (cleartext) | ✓ Blocked | ✗ Allowed |
| MSTG-NETWORK-3 (pinning) | ✓ Fail-closed | ✓ Pinned but user CA |
| MSTG-STORAGE-8 (backup) | ✓ Disabled | ✗ Enabled |
| MSTG-STORAGE-5 (audit) | ✓ Hash-chained | ✓ Hash-chained |
| MSTG-PLATFORM-3 (deep link) | ✓ Validated | ✓ Validated |
