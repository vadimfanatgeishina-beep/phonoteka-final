# ЛР15: локальный WSS-стенд

Экран «Журнал → Живой сетевой канал» работает в debug-сборке с собственным сервером на компьютере. Сервер слушает только `127.0.0.1:8444`; телефон получает доступ через `adb reverse tcp:8444 tcp:8444`. После переподключения USB команду `adb reverse` нужно повторить.

Сертификат стенда включён в debug-ресурсы, его SPKI-пин проверяется клиентом. Закрытый ключ хранится только локально в `.local/lab15/localhost.p12` и исключён из Git. Debug HMAC-ключ публичный и предназначен только для учебного стенда.

Запуск из корня проекта в PowerShell:

```powershell
$env:PHONOTEKA_MOCK_KEYSTORE=(Resolve-Path '.local/lab15/localhost.p12').Path
$env:PHONOTEKA_MOCK_KEYSTORE_PASSWORD='phonoteka-lab15-local'
$env:PHONOTEKA_MOCK_HMAC_KEY='phonoteka-owned-lab15-demo-key-v1'
javac -d '.local/lab15' 'tools/LocalTlsPolicy.java' 'tools/LocalLab15MockServer.java' 'tools/LocalLab15WssServer.java'
java -cp '.local/lab15' LocalLab15WssServer
```

В другом окне: `adb reverse tcp:8444 tcp:8444`. Затем в приложении нажать «Подключиться» и «Отправить настоящий запрос». Счётчики отправленных и принятых кадров должны расти вместе.

Если `.local/lab15/localhost.p12` утрачен, нужен новый сертификат и обновление доверенного сертификата и пина в debug-конфигурации. Release-сборка этот стенд не включает.
