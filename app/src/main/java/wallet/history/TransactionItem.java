package wallet.history;

public final class TransactionItem {
    public final String type, amount, time, confirmations, txid;
    public TransactionItem(String type, String amount, String time, String confirmations, String txid) {
        this.type = type; this.amount = amount; this.time = time; this.confirmations = confirmations; this.txid = txid;
    }
}
