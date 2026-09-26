# BitcoinJ Wallet Phase 3

Baseline: Phase 2 v5.

Phase 3 adds:

- Coin control for the next send using bitcoinj `CoinSelector` filtering.
- WIF private-key import using bitcoinj `DumpedPrivateKey`.
- Private-key import remains encrypted when the wallet is encrypted.
- Watch-only address monitoring using bitcoinj `Wallet.addWatchedAddress`.
- Optional first-use date for watched addresses; no hardcoded date is used.

Existing backup/restore, mnemonic restore, optional wallet birthday, wallet encryption, recovery phrase, SEND/RBF lifecycle, transaction UI, and semantic color rules are preserved.

Taproot/BIP86 is not added because bitcoinj 0.17.1 does not expose it as a native HD wallet profile.

## Phase 3 v25 additions

- Foreground `BitcoinSyncService` remains the owner of `WalletAppKit`; Activities attach/detach UI without stopping blockchain sync.
- Sync dashboard reports reconnecting/no-peer state and identifies the current block-download peer.
- Background sync continues through Home/Send/Sync navigation and `START_STICKY` service lifecycle.
- Transaction notifications are deduplicated by transaction ID and persisted across service restarts.
- Watch-only tools show the last scanned wallet block.
- Wallet Tools includes a read-only Wallet Diagnostics view with chain, peer, wallet, UTXO and consistency information.
- Backup restore verifies the installed wallet file before deleting the previous-wallet safety copy.
- Recovery-phrase restore validates wallet consistency/network before removing its temporary safety copy.
- Transaction history is loaded without a hard data limit. The transaction card defaults to showing all transactions and provides a menu for all / 10 / 20 / 30 / 50 / 100 transactions per type.
- Debug-only `Log.d`/`System.out` output is not used; error/warning logs remain for failure diagnosis and never include recovery phrases or private keys.

The current production build is configured for Bitcoin Mainnet (`Constants.IS_PRODUCTION = true`).
