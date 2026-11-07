# Реальная проверка — финальный кандидат, этап2

Основа — safe step1 source, не подлинная vulnerable история. Это ещё не завершение всех лабораторных.

- Итоговый полный Gradle: BUILD SUCCESSFUL. Исходники app/src/build config побайтно совпадают с началом проверенного прогона (`verified-source-hashes.json`), код во время него не изменялся.
- JVM: **184/184**, failure0/error0/skipped0, классов36. В том числе22 новых biometric orchestration/negative cases. Полные имена: jvm-results.json.
- Lint debug: **57 Warning / 4 Information /0 Error/Fatal**, полный XML приложен.
- Скомпилирован androidTest, собраны debug/androidTest/minified unsigned release APK. APK hashes/размеры приложены. Новый instrumentation KeyInfo/unauthorized-Cipher negative ТОЛЬКО скомпилирован, НЕ запущен на Android.
- Debug/androidTest подпись проверена apksigner verify, release unsigned (не считаем его готовым к установке).
- Synthetic clean step1 Git apply/check + содержимое изменённых файлов проверены отдельно перед передачей. Не реальные vulnerable/hardened tags.

```bash
./gradlew :app:testDebugUnitTest :app:lintDebug \
  :app:compileDebugAndroidTestKotlin :app:assembleDebug \
  :app:assembleDebugAndroidTest :app:assembleRelease \
  --no-daemon --max-workers=1 -Pkotlin.compiler.execution.strategy=in-process
```

JDK17/SDK34/Gradle8.10.2. Инструменты восстановлены из официальных источников после сброса среды. Был начальный compile и промежуточный прерванный build во время доработки; PASS относится только к final-build.txt и зафиксированному итоговому source snapshot.

## Не заявлено как выполненное

Connected/device нет. Hardware per-use prompt, system-key compatibility, enrollment-change, cancel/rotation native callbacks, process kill/restart, biometric2prompt UX, minified runtime на Android НЕ запускались. JVM Fake vault не доказывает TEE authorization. Результаты предыдущих снимков не наследуются автоматически. Новых HTTPS fixture прогонов этого этапа нет: соответствующий code не менялся; старые step1 logs сохранены как исторические.

Реальное восстановление теперь реализовано в коде вместо constant proof, но ЛР13 device acceptance остаётся открытым. На отдельных Android recent strong biometric может не открыть300s fallback key; ошибка НЕ consumes endpoint refresh, нужен system+PIN fallback. Политика отдельного optional reenrollment, причины и точные команды/критерии: FINAL_STEP2.md.

Key/token/credential values не запрашивались и не публиковались. Отчёты содержат только результаты/пути/public metadata; логи нормализуют локальный `<project>` путь и trailing whitespace. Полное erase/revoke при одновременно недоступных disk+endpoint не обещается: ошибки cleanup видимы как failure, UI всё равно lock.

Следующий крупный остаток: actor/subject append-only security JSONL ЛР14; затем literal8/9/11, WSS15 и isolated vulnerable/hardened16. Страницы Notion не создавались и не менялись.
