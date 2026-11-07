# Этап 9 — ЛР16: изолированные vulnerable/hardened варианты + исправление minSdk

## ЛР16
- `tools/lab16/Lab16Variants.java` — пары vulnerable/hardened для находок аудита F-01…F-06:
  - F-01/F-06: проверка manifest (backup, cleartext, debuggable) и release-сборки (R8, shrinkResources);
  - F-02: каталог без локальной аутентификации ↔ каталог с сессией и TTL 5 минут;
  - F-03: загрузка без лимита ↔ лимит по Content-Length и по фактическому потоку (chunked и ложный Content-Length);
  - F-04: деструктивная миграция ↔ отказ без потери данных, известная миграция 2→3;
  - F-05: URL с токеном в диагностике ↔ только host, URL в причине ошибки маскируется.
- `tools/lab16/Lab16VariantsTest.java` — для каждой находки vulnerable воспроизводит проблему, hardened её блокирует. Реальные `AndroidManifest.xml` и `app/build.gradle.kts` проверяются теми же правилами.
- **Реально запущено** (Java 25): `java tools/lab16/Lab16VariantsTest.java .` → `Lab16 vulnerable/hardened variants: OK (26 checks)`. Вывод: `lab_reports/lab16/variants_run.txt`.
- Изоляция: код лежит в `tools/`, не подключён к Gradle-модулю `app` и не попадает в APK. Уязвимые варианты работают только с данными в памяти.

**Честное ограничение:** это реконструкция поведения из аудита, а не подлинные git-теги исходного коммита `eadf4b0` (его нет в архиве). Пункт «authentic vulnerable/hardened tags» поэтому закрыт частично.

## minSdk 24
- `FreeNotesRepository.save`: `java.nio.file.Files.move` (API 26+) заменён на `File.renameTo` с запасным путём «удалить старый файл → переименовать». На Android rename в пределах каталога атомарен; запасной путь атомарным не является.
- `TestFileNoteIndex.kt` (только JVM-тесты) оставлен с `Files.move` — в APK не попадает.

## Не проверено
- Kotlin-код (FreeNotes) не компилировался: в песочнице нет Gradle и Android SDK. Существующие тесты FreeNotes нужно перезапустить.
