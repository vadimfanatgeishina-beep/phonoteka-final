# Фактический SCA version lookup и правка jsoup

Исходная разрешённая транзитивная версия `org.jsoup:jsoup:1.22.2` дала две version-match записи OSV. Это реальные ответы публичной базы, но НЕ доказательство exploitability Phonoteka.

1. GHSA-65r4-943x-97jj / CVE-2026-75140: XML namespace memory exhaustion. Structured affected range в OSV отмечает исправление в1.23.2; описание advisory имеет неоднозначную формулировку диапазона. В main app не найден вызов XmlTreeBuilder/xmlParser; транзитивная реализация и shaded code не доказаны безопасными одним grep.
2. GHSA-pmhh-3w7g-xqp8 / CVE-2026-71497: custom Cleaner Safelist/raw-text parsing. Structured fixed1.23.1. В main app не найден собственный Cleaner/Safelist. Это ограничение applicability triage, НЕ suppression/отрицание advisory.

Правка: `app/build.gradle.kts` явно и строго разрешает jsoup1.23.2. Maven Central публикует эту версию; новая фактическая debug/release compilation и R8 packaging прошли. NewPipe runtime на реальной сети не выполнялся и не выдаётся за проверенный.

После правки:
- Debug lint package-model: 136 public coordinates, 0 version matches.
- Настоящий Gradle `releaseRuntimeClasspath` resolutionResult: 167 public coordinates, 0 version matches; исходный inventory + ответ attached в current_validation.
- Это разные scope/counts, не before/after сокращение dependencies. Для сравнения jsoup использованы одинаковые debug136 scope до/после, release167 — отдельная более широкая проверка.
- OSV не устанавливает absence of vulnerabilities, runtime compatibility, наличие/отсутствие shaded уязвимых классов или состояние секретов Git history.
- Инструмент отправляет только публичные group/name/version. Не отправляет исходники/APK/секреты.

Источники:
- https://osv.dev/vulnerability/GHSA-65r4-943x-97jj
- https://github.com/jhy/jsoup/pull/2556
- https://github.com/jhy/jsoup/releases/tag/jsoup-1.23.2
- https://github.com/jhy/jsoup/security/advisories/GHSA-pmhh-3w7g-xqp8

Дата наблюдения и полный исходный ответ — JSON reports. Частичная применимость не заменена утверждением «проверен эксплойт». Опасный XML/OOM payload, настоящие endpoints и чужой трафик не запускались.
