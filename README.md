# Bitcoin Wallet v50 – Wallet Tools split

Based on v49.

Wallet Tools was reorganized into a compact entry screen with three themed rows: Import WIF, Watch-only Wallet, and Wallet Utility. Each row opens its own page while preserving the existing wallet logic and UI behavior. The entry rows use Android native selectable ripple feedback and theme-derived colors.

The three former Wallet Tools cards were separated into their own activities/layouts. Main wallet, watch-only, Address Book, transaction detail, RBF, sync, backup, and security logic were not intentionally changed.

Gradle compilation was attempted, but this environment could not download Gradle 5.6.4 because `services.gradle.org` was unreachable. The source and XML were structurally checked.
