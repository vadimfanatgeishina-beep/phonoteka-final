# Реальная попытка нового Android-прогона

Запускался собственный пустой AOSP Android13 x86_64 AVD `PhonotekaOwned13` без аккаунтов/камеры. Идентичность AVD подтверждена через emulator console перед ожиданием и перед остановкой; реальные пользовательские устройства не выбирались.

На host отсутствует `/dev/kvm`; использован software TCG (`-accel off`). Первая попытка остановлена из-за конкуренции за память со сборкой. Во второй попытке после завершения Gradle AVD достиг состояния adb `device`, но `sys.boot_completed=1` не появился за отдельные ограниченные300+75 секунд ожидания. Есть kernel/init progress, что НЕ является успешной загрузкой или UI-проверкой. Эмулятор остановлен после этого лимита.

- Новая instrumentation НЕ запускалась: runner требует подтверждённый boot.
- APK installation, UI screenshots, AtomicFile/Room migration/KeyStore/device pin/replay/ADB component acceptance для этого AVD НЕ получены.
- Никакого пропущенного/подвисшего runner не назвали pass.
- Исторические результаты владельца на Android13 относятся к v3; новая сборка ими не сертифицирована.
- Own Java loopback TLS evidence получен отдельно и не переименован в Android evidence.

Один предварительный повтор запуска имел неподдерживаемый `-dpi` аргумент и остановился сразу; параметр удалён перед второй действительной попыткой. Это ошибка harness, не дефект Phonoteka. Не приписываем software-emulator boot failure приложению: оно не запускалось.

Для воспроизводимого device acceptance используйте только свой заранее загрузившийся/разблокированный Android и `tools/run_device_acceptance.sh` по отдельным modes. `tls` запускать только с собственным поднятым fixture/совпадающими debug pins; `auth-bound` требует свежего Android system authentication. `core/ui/invite` не доказывают hardware biometric policy/TalkBack/реальную камеру. Значения PIN/key/password/recovery не нужны в отчёте/чате.
