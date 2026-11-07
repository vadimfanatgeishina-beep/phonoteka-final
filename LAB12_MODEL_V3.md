# ЛР12 — модель v3 подготовленного комплекта

Снимок v3 расширен патчами; **контрмеры ниже подготовлены, не проверены в runtime**.
Этот документ дополняет v1/v2, а не задним числом меняет их исходные наблюдения.

## Изменившиеся активы и границы

- Access token: клиентская память; mock ledger содержит только digest/expiry. Refresh: auth-bound AES-GCM prefs; mock endpoint знает только digest→user/expiry.
- PIN/recovery: HMAC AndroidKeyStore; recovery дополнительно требует успешный учебный пароль в auth gate. Android credential не равен собственному PIN приложения.
- Role grant: opaque random token, server-side in-process digest registry и expiry. Полное владение процессом обходит учебную границу.
- Track note: private encrypted immutable revision, HMAC checksum, authenticated atomic index, backup/trash; clear SAF JSON только после пользовательского выбора.
- Audit: authenticated stored snapshot; не только подпись экспортируемого файла. Full snapshot rollback владельцем root не покрыт.
- Local WSS: localhost-only pinning, signed HTTP handshake, signed frames, request/response channel separation и новый UUID канала при reconnect.
- Build boundary: debug fixture key разрешён только как публичный учебный материал; release не доверяет local CA и не включает runtime WSS fixture UI.

## STRIDE / конкретные контрмеры и остаточный риск

| ID | Категория | Угроза / поверхность | Подготовленная контрмера | Остаток / безопасная проверка |
|---|---|---|---|---|
| V3-01 | S | forged ADMIN scopes на прямом repository вызове | authority digest lookup + declared/server role equality, три уровня | in-process authority не настоящий сервер; direct-call test |
| V3-02 | S/E | старый ADMIN grant после смены роли | issue clears previous registry, bounded expiry | root может вызвать fixture issue; debug-only selection |
| V3-03 | T | правка stored audit до export | verify-before-read/write + Keystore HMAC snapshot | rollback старого signed snapshot не обнаруживается |
| V3-04 | R | deny decision без аудит-события | repository requires authorization+audit before action | KeyStore/audit failure должен закрыть action; device tamper test |
| V3-05 | I | extraction client refresh blob | auth-required AES key 300s + backup exclusions | свежая system authentication/root runtime capture не покрыты |
| V3-06 | I | token значения в mock ledger/logs | digest keys, safe UI messages, SAST | full memory compromise остаётся; no реальные секреты в тестах |
| V3-07 | T | future timestamp replay после 31s | retain nonce through signed expiry inclusive | clock rollback/server restart требуют отдельной модели; current tests fixed clock |
| V3-08 | T/S | подмена локального TLS сертификата | system/owned CA scope + explicit original SPKI pin | pin rotation/lifecycle требуют настоящего стенда |
| V3-09 | T | WSS reflection/cross-reconnect replay | request/response suffix, unique per-connection channel | HMAC общий mock key; не полноценный identity scheme |
| V3-10 | D | oversized body/audio/recursive HLS/queued WSS sockets | 64KiB signed body, media/playlist caps, recursion4, bounded WSS workers | CPU/slow-peer abuse остаётся ограниченной local fixture задачей |
| V3-11 | T/D | crash между file revision и index update | immutable revision→atomic index commit; previous backup | directory durability/power loss/OEM не подтверждены |
| V3-12 | I | SAF export заметок | явный пользовательский выбор/обычный JSON disclosure | файл вне sandbox не шифруется; нельзя экспортировать секреты сессии |
| V3-13 | D | отсутствие historical Room schema | fail without destructive wipe; schema export дальше | availability при старой установке не закрыта без actual old DB |
| V3-14 | E/I | overlay/screenshot на PIN/notes | FLAG_SECURE all windows, hide overlay API31+ | compromised accessibility/root не покрыты |

## Дерево атаки: открыть защищённые данные без свежей аутентификации

- 1. Обойти клиентский вход
  - 1.1. Direct deep link
    - 1.1.1. Открыть export entry point → auth gate (код; device-проверка открыта)
    - 1.1.2. Malformed/query/userInfo path → validation reject (код; runtime открыт)
  - 1.2. Extract ciphertext
    - 1.2.1. Без system authentication → auth-bound key deny (нужен negative device test)
    - 1.2.2. После свежего Android unlock → окно 300s остаётся по design
- 2. Подменить полномочия
  - 2.1. Изменить role/scopes при том же token
    - 2.1.1. Server registry equality → deny (тест подготовлен)
  - 2.2. Захватить процесс/root
    - 2.2.1. Вызвать issue/delete prefs/hook methods → за пределами local mock boundary

## Проверки / detection

1. Unit future nonce и swapped scopes: фиксированные часы/фальшивая сессия, без сети/аккаунтов.
2. Device auth-bound key: свой Android, cancel/expiry/enrollment/process death; observer не публикует PIN/токен/ciphertext.
3. Owned certificate substitution: два локально генерируемых сертификата; отдельный fixture client; нет global proxy/чужого трафика.
4. Audit/index tamper: меняется только тестовый prefs/temp directory; hard fail before action.
5. Notes failed commit/corrupt revision/trash TTL: temporary directory and fake crypto for unit tests; production AES policy проверяется отдельно.

MASVS: AUTH (gate/step-up/roles), STORAGE (refresh/notes/audit/backup), NETWORK (TLS/HMAC/WSS), PLATFORM (overlay/deeplink), RESILIENCE (R8/advisory RASP).
DREAD/ALE v2 остаются учебными гипотезами; новая численная «переоценка после исправлений» без фактических проверок не заявляется. Новые риски в первую очередь V3-05/07/11/13; их acceptance в RUN_MATRIX.md.
