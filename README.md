# BitcoinJ Wallet v1.0.0

A lightweight Android Bitcoin wallet built around BitcoinJ 0.17.1.

## Architecture

- XML defines static screen layouts and reusable row layouts.
- Activities handle navigation and screen-level events.
- `MainActivityPresenter` owns the existing BitcoinJ wallet lifecycle and synchronization.
- Transaction data is mapped into immutable `TransactionItem` objects and rendered by `TransactionAdapter`.
- QR generation and the receive QR dialog are isolated in the `wallet.qr` package.
- Backup, restore, and wallet-file migration are isolated from the main screen.
- Wallet files remain in application-private storage.
- Backup and restore use the Android document provider.

## UI

- The main screen contains the full transaction list; there is no separate Recent or transaction-history screen.
- Static UI is defined in XML rather than constructed in Java.
- The application uses AppCompat semantic theme attributes for UI colors.
- No application color palette is defined. The existing logo is the only fixed-color brand asset.
- User-visible text is stored in `res/values/strings.xml`.
- Day and night appearance are provided by the AppCompat `DayNight` theme.

## Build

The project intentionally retains the Java 8 and Android Support Library 28 toolchain during this cleanup stage so wallet behavior is not migrated at the same time as the UI.

```bash
./gradlew assembleDebug
./gradlew assembleRelease
```

The release workflow is manual-only.
