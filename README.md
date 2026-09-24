# BitcoinJ Wallet v1.0.0

A small Android Bitcoin wallet built around BitcoinJ 0.17.1.

## Structure

- XML layouts define the static UI.
- Activities handle screen events and navigation.
- RecyclerView adapters bind transaction data to XML row layouts.
- `MainActivityPresenter` owns the existing BitcoinJ wallet and sync lifecycle.
- QR generation and the receive QR dialog are isolated from the main activity.
- Legacy wallet-file migration is isolated in `WalletFileMigration`.
- Wallet files stay in app-private persistent storage.
- Backup and restore use the Android document provider.
- Release builds use R8 and resource shrinking.

## UI rules

- Do not build static UI with `new View(...)` in Java.
- Keep layout structure in XML.
- Keep Java focused on events, state updates, navigation, and adapters.
- Keep reusable UI behavior in small dedicated classes.

## Build

The project intentionally keeps the existing Java 8 / Android Support Library 28 toolchain for this cleanup stage so the BitcoinJ wallet behavior is not migrated at the same time as the UI.

```bash
./gradlew assembleDebug
./gradlew assembleRelease
```

The GitHub Actions release workflow is manual-only.
