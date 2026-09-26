# Bitcoin Wallet v44 – RBF Boost compile fix

Based on v43 Address Book / Contacts.

Fixed bitcoinj 0.17.1 API incompatibilities in TransactionDetailActivity RBF boost:
- Build replacement transaction with `new Transaction(NetworkParameters)` instead of the unsupported `(NetworkParameters, byte[])` constructor.
- Copy inputs and outputs explicitly into the replacement transaction.
- Cast transaction version to int for bitcoinj 0.17.1.
- Preserve `TransactionOutput.setValue(Coin)` on the replacement change output.

The source was checked structurally. Gradle compilation could not be executed in this environment because Gradle 5.6.4 was not cached and `services.gradle.org` was unreachable.
