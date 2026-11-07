# Phonoteka UI design system

## Color roles

Use `MaterialTheme.colorScheme` in screens and components; do not hardcode component colors.

| Role | Light | Dark | Use |
|---|---|---|---|
| primary | `#6750A4` | `#B5A0FF` | Main action, selected controls |
| secondary | `#5C4FAE` | `#9F8FE5` | Secondary action |
| background | `#F5F4F1` | `#1A181E` | App canvas |
| surface | `#FFFFFF` | `#252329` | Cards and sheets |
| surfaceVariant | `#E5E2E9` | `#3D3A43` | Secondary cards |
| error | `#C0392B` | `#FF6B5B` | Errors |

On Android 12 and newer, the system palette is used while the theme mode is `SYSTEM`. Manual `LIGHT` and `DARK` modes use the Phonoteka palettes. `forceDarkContent` deliberately uses the dark palette in any mode.

`LocalExtendedColors` provides semantic accents that are not in Material 3: success, warning, and the home-header gradient. Read these values through the CompositionLocal; do not repeat literal colors in feature screens.

## Typography

- `headlineMedium`: 28 sp, semibold — screen-level headings.
- `bodyLarge`: 16 sp with 24 sp line height — long-form body text.
- Other Material 3 text styles use their defaults.

## Shapes

- `small`: 8 dp.
- `medium`: 16 dp — standard cards.
- `large`: 24 dp — large surfaces.

## Components

- `AppButton`: primary, tonal secondary, and outlined variants; small, medium, and large sizes; disables itself while loading.
- `StatusButton`: idle/loading/error/success states; non-idle states are disabled and expose `stateDescription` and a screen-reader announcement.
- `FormField`: idle/error/warning/disabled variants with semantic error text and minimum touch size.
- `ProfileCard`: slot-based leading icon and trailing content, with merged TalkBack description.
- `TrackCard`, `StatusBadge`, and `MiniPlayer`: app-specific track, status, and playback controls.

## Accessibility rules

- Every actionable icon has an action-focused `contentDescription`; decorative icons use `null`.
- Custom touch targets must be at least 48 × 48 dp.
- Group related card text for TalkBack only when the card behaves as one semantic item.
- Use live regions for important save/error announcements.
- List semantics should expose collection and item position when the list is presented as a meaningful collection.
- Error and save feedback uses an assertive live region; test selectors use stable tags such as `track-search` and `track-row-{id}`.

## Экран «Мой профиль» (этап 4)
Файл `ui/screens/ProfileScreen.kt`, маршрут `settings/profile`. Использует AppButton (LARGE/MEDIUM/SMALL), LoadingButton, StatusButton, FormField (IDLE/ERROR/WARNING/DISABLED), ProfileCard, ExtendedColors (градиент шапки, success), SnackbarLikeMessage. Компоненты не получают ViewModel.
