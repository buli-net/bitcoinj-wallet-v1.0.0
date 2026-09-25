# Bitcoin Wallet

A lightweight Android Bitcoin wallet built with Java, XML layouts, and bitcoinj 0.17.1.

## Send flow

- Displays the current wallet balance on the Send screen.
- Shows amount, fee, total debit, and remaining balance.
- Calculates the actual transaction fee while preparing the transaction.
- Replaces the SEND button with an inline send-process card.
- Shows a horizontal 60-second safety progress bar on the Send screen.
- Provides CANCEL and SEND NOW during the safety delay.
- Rechecks wallet balance immediately before committing the transaction.
- Prevents overlapping send operations.
- Supports opt-in Replace-by-Fee (RBF).
- MAX calculates the maximum spendable output after fees.

## UI

- No send confirmation popup.
- The send process stays on the Send screen.
- SEND disappears while a transaction is being prepared or broadcast.
- CANCEL and SEND NOW are visible only while the process is active.
- After completion or cancellation, the process is removed and SEND returns.
- Uses the existing AppCompat/system semantic color theme throughout the application.
- User-facing text is stored in Android string resources.


Phase 2 adds recovery phrase restore, recovery phrase display, and wallet password encryption. Existing file backup and file restore remain available. Passwords are never stored by the app.

## Wallet derivation

- New deterministic wallets use bitcoinj 0.17.1 with BIP43 wallet structure and P2WPKH as the preferred output type.
- The P2WPKH wallet structure also activates the P2PKH chain, so BIP44 and BIP84 accounts can be restored from the same mnemonic.
- Mnemonic restore requires a wallet creation date in YYYY-MM-DD format and passes it directly to bitcoinj as the deterministic seed creation time.
- No birthday fallback or synthetic creation date is used.
- Taproot/BIP86 is not enabled because bitcoinj 0.17.1 does not provide a complete Taproot HD wallet profile.
