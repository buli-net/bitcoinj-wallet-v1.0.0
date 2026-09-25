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
