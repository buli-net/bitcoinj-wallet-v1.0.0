/**
 * Release documentation:
 * Release documentation for the immutable transaction display model.
 * Stores the already formatted transaction type, amount, time, confirmation state, and shortened transaction identifier used by the history adapter.
 */

package wallet.history;

public final class TransactionItem {

    public final String type;
    public final String amount;
    public final String time;
    public final String confirmations;
    public final String txid;

    public TransactionItem(
            String type,
            String amount,
            String time,
            String confirmations,
            String txid) {
        this.type = type;
        this.amount = amount;
        this.time = time;
        this.confirmations = confirmations;
        this.txid = txid;
    }
}
