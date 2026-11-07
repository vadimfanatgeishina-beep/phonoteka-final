# Triage текущего lintDebug

58 Warning, 4 Information, 0 Error. Это не clean-lint и не device certification.

- `ApplySharedPref` (5): не менять `commit()` на `apply()` автоматически в `security/LocalAuthManager.kt`. Эти auth/lockout transitions используют синхронный результат записи и fail-closed. Проверить задержку UI на реальном устройстве; переносить IO только с сохранением атомарного перехода состояния.
- `StaticFieldLeak`: `viewmodel/PlayerViewModel.kt` хранит `application.applicationContext`, не Activity. По текущему пути это предупреждение не подтверждает Activity leak. Нужен profiler/lifecycle test, не бессмысленное suppression.
- `DefaultLocale` (2): формат времени в `ui/screens/TrackDetailScreen.kt` зависит от locale. Не влияет на canonical signature, но нужен UI locale acceptance/явный Locale для стабильных ASCII-строк при таком критерии.
- `GradleDependency` (20): не равнозначно 20 CVE. Отдельно проверены публичные resolved Maven coordinates через OSV; результаты и пределы покрытия приложены.
- `ModifierParameter`, `AutoboxingStateCreation`, unused resources, mono-icon/KAPT: поддержка API, эффективность и качество, не доказательство security failure. Не удалять resources без проверки R8/dynamic resolution.

Ни одна строка не объявляет profiler/UI/release runtime выполненными. Security-sensitive synchronous writes не «исправлены» вслепую ради снижения числа warning.
