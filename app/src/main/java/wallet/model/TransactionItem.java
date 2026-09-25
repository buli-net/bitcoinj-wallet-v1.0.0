package wallet.model;

/** Immutable transaction data used by wallet lists. */
public final class TransactionItem {
    public final String type;
    public final String amount;
    public final String time;
    public final String confirmations;
    public final String txid;
    public final String detailTxid;
    public final String detailType;
    public final String detailAmount;
    public final String detailTime;
    public final String detailStatus;
    public final String detailFee;
    public final String detailPeers;
    public final String detailInputs;
    public final String detailOutputs;
    public final String detailSize;
    public final String detailVersion;
    public final String detailBlockHeight;

    public TransactionItem(String type, String amount, String time,
                           String confirmations, String txid,
                           String detailTxid, String detailType,
                           String detailAmount, String detailTime,
                           String detailStatus, String detailFee,
                           String detailPeers, String detailInputs,
                           String detailOutputs, String detailSize,
                           String detailVersion, String detailBlockHeight) {
        this.type = type;
        this.amount = amount;
        this.time = time;
        this.confirmations = confirmations;
        this.txid = txid;
        this.detailTxid = detailTxid;
        this.detailType = detailType;
        this.detailAmount = detailAmount;
        this.detailTime = detailTime;
        this.detailStatus = detailStatus;
        this.detailFee = detailFee;
        this.detailPeers = detailPeers;
        this.detailInputs = detailInputs;
        this.detailOutputs = detailOutputs;
        this.detailSize = detailSize;
        this.detailVersion = detailVersion;
        this.detailBlockHeight = detailBlockHeight;
    }
}
