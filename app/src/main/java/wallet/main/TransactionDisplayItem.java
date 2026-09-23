/**
 * Release documentation:
 * Immutable presentation data for one transaction displayed on the main wallet screen.
 * Keeps transaction fields separate so the view never has to parse a preformatted multiline string.
 * User-visible labels remain owned by Android string resources; this class contains only display data.
 */
package wallet.main;

public final class TransactionDisplayItem {
    private final int index;
    private final String typeLabel;
    private final String amount;
    private final String date;
    private final String status;
    private final String transactionId;

    public TransactionDisplayItem(
            int index,
            String typeLabel,
            String amount,
            String date,
            String status,
            String transactionId) {
        this.index = index;
        this.typeLabel = typeLabel;
        this.amount = amount;
        this.date = date;
        this.status = status;
        this.transactionId = transactionId;
    }

    public int getIndex() {
        return index;
    }

    public String getTypeLabel() {
        return typeLabel;
    }

    public String getAmount() {
        return amount;
    }

    public String getDate() {
        return date;
    }

    public String getStatus() {
        return status;
    }

    public String getTransactionId() {
        return transactionId;
    }
}
