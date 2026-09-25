# Bitcoin Wallet

A lightweight Android Bitcoin wallet built with Java, XML layouts, and bitcoinj 0.17.1.

## Send flow

- Shows the current wallet balance on the Send screen.
- Shows the selected fee rate and the actual transaction fee after transaction preparation.
- Shows amount + fee and the remaining balance on the same screen.
- Shows recipient, balance, amount, actual fee, total debit, remaining balance, and RBF state in the send process panel.
- Starts a 60-second safety countdown directly on the Send screen.
- Replaces SEND with CANCEL and SEND NOW while a send is being prepared or held.
- SEND NOW skips the remaining safety delay and starts broadcast immediately.
- CANCEL discards the prepared transaction before wallet commit.
- Hides the process actions while broadcasting and restores SEND after completion or failure.
- Prevents overlapping send operations across SendActivity instances.
- Rechecks the current wallet balance immediately before committing the transaction.
- MAX uses bitcoinj's empty-wallet transaction flow so the fee is included in the maximum spendable amount.
- Supports opt-in Replace-by-Fee (RBF) by setting transaction input sequences before signing.

## UI

- Uses the existing AppCompat/system semantic color theme throughout the application.
- User-facing text is stored in Android string resources.
- The Send screen follows the same card, spacing, toolbar, and system-color conventions as the main wallet screen.
- No send confirmation dialog is used.
