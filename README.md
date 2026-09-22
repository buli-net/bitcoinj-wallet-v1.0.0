# Bitcoin Wallet - clean toolbar/menu revision

This revision keeps the existing Bitcoin wallet logic and cleans the Android UI resources around the toolbar and overflow menu.

## Toolbar / overflow

- Uses the standard AppCompat `Toolbar` and overflow menu.
- Secondary menu items remain `showAsAction="never"` and appear only under `⋮`.
- AppCompat `MenuBuilder.setOptionalIconsVisible(true)` enables icons in the overflow popup.
- Menu icons are vector drawables that resolve `?attr/colorControlNormal` from the active popup theme.
- No runtime icon-tint helper is used when the popup opens.

## Light / Dark mode

- Uses `Theme.AppCompat.DayNight.NoActionBar`.
- The overflow popup uses the corresponding AppCompat Light/Dark overlay.
- Toolbar and menu icon colors resolve from the active Android theme instead of fixed white/black values.
- No app `colors.xml` palette is used.

## Source cleanup

- Removed unused `HistorySharedPreferences.java`.
- Removed unused legacy drawable resources and the unused `view_my_qr_code.xml` layout.
- Removed unused Java imports and the unused scan request constant.
- Renamed menu icon resources so their names no longer claim a fixed black/white color.
- Reformatted Android XML resources with one attribute per line for readability.
- Reformatted the touched Java callbacks and menu setup without changing wallet/sync/send/backup/restore behavior.

## Build

The project uses Gradle 5.6.4 and Android Gradle Plugin 3.6.4. Build with:

```bash
./gradlew clean assembleDebug --stacktrace --no-daemon
```
