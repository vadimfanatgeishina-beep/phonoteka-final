# Фактический фрагмент Compose Compiler report

Source: `app_debug-composables.txt`; данные извлечены, не baseline и не runtime counts.

```text
restartable scheme("[androidx.compose.ui.UiComposable]") fun Navigation(
  unstable repository: PhonotekaRepository
  unstable settingsViewModel: SettingsViewModel
  unstable playerViewModel: PlayerViewModel
  unstable authManager: LocalAuthManager
  stable onLock: Function0<Unit>? = @static {

}

)
```

```text
restartable scheme("[androidx.compose.ui.UiComposable]") fun MiniPlayerHost(
  unstable playerViewModel: PlayerViewModel
  stable modifier: Modifier? = @static Companion
)
```

```text
restartable scheme("[androidx.compose.ui.UiComposable]") fun RiskStepUpPrompt(
  unstable app: PhonotekaApp
)
```
