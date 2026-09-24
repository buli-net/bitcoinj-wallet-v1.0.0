package wallet.model;

/** Immutable transaction data used by wallet lists. */
public final class TransactionItem {
    public final String type;
    public final String amount;
    public final String time;
    public final String confirmations;
    public final String txid;

    public TransactionItem(String type, String amount, String time,
                           String confirmations, String txid) {
        this.type = type;
        this.amount = amount;
        this.time = time;
        this.confirmations = confirmations;
        this.txid = txid;
    }
}
