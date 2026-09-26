package wallet.transaction;

import android.content.Context;

import org.bitcoinj.base.Coin;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.TransactionConfidence;
import org.bitcoinj.wallet.Wallet;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import wallet.main.R;
import wallet.model.TransactionItem;

/** Converts bitcoinj transactions into UI data. */
public final class TransactionMapper {

    private TransactionMapper() {
    }

    /** Maps only transactions containing outputs paying to the selected watched script. */
    public static List<TransactionItem> mapForWatchedScript(
            Context context, Wallet wallet, org.bitcoinj.script.Script watchedScript) {
        List<TransactionItem> items = new ArrayList<>();
        if (wallet == null || watchedScript == null) {
            return items;
        }

        SimpleDateFormat dateFormat = new SimpleDateFormat(
                "yyyy-MM-dd HH:mm:ss", Locale.US);

        for (Transaction transaction : wallet.getTransactionsByTime()) {
            Coin received = Coin.ZERO;
            for (org.bitcoinj.core.TransactionOutput output : transaction.getOutputs()) {
                if (watchedScript.equals(output.getScriptPubKey())) {
                    received = received.add(output.getValue());
                }
            }

            if (received.isZero()) {
                continue;
            }

            String type = context.getString(R.string.transaction_received);
            String amount = "+" + received.toFriendlyString();

            String txId = transaction.getTxId().toString();
            String shortTxId = txId.length() > 16
                    ? txId.substring(0, 8) + "..." + txId.substring(txId.length() - 8)
                    : txId;

            String time = transaction.updateTime().isPresent()
                    ? dateFormat.format(Date.from(transaction.updateTime().get()))
                    : context.getString(R.string.unknown_time);

            TransactionConfidence confidence = transaction.getConfidence();
            int depth = confidence == null ? 0 : confidence.getDepthInBlocks();
            int peers = confidence == null ? 0 : confidence.numBroadcastPeers();

            String confirmations = depth > 0
                    ? context.getString(depth == 1
                    ? R.string.confirmed_one
                    : R.string.confirmed_many, depth)
                    : context.getString(R.string.unconfirmed);

            String state;
            if (depth > 0) {
                state = context.getString(R.string.transaction_state_confirmed);
            } else if (confidence != null
                    && confidence.getConfidenceType() == TransactionConfidence.ConfidenceType.DEAD) {
                state = context.getString(R.string.transaction_state_dead);
            } else if (peers > 0) {
                state = context.getString(R.string.transaction_state_broadcast, peers);
            } else {
                state = context.getString(R.string.transaction_state_pending);
            }

            items.add(new TransactionItem(
                    type,
                    amount,
                    time,
                    confirmations,
                    shortTxId,
                    context.getString(R.string.transaction_peers, peers),
                    state));
        }

        return items;
    }

    public static List<TransactionItem> map(Context context, Wallet wallet) {
        List<TransactionItem> items = new ArrayList<>();
        SimpleDateFormat dateFormat = new SimpleDateFormat(
                "yyyy-MM-dd HH:mm:ss", Locale.US);

        for (Transaction transaction : wallet.getTransactionsByTime()) {
            Coin received = transaction.getValueSentToMe(wallet);
            Coin sent = transaction.getValueSentFromMe(wallet);
            Coin net = received.minus(sent);

            if (net.isZero()) {
                continue;
            }

            String type;
            String amount;
            if (net.isPositive()) {
                type = context.getString(R.string.transaction_received);
                amount = "+" + net.toFriendlyString();
            } else if (net.isNegative()) {
                type = context.getString(R.string.transaction_sent);
                amount = net.toFriendlyString();
            } else {
                type = context.getString(R.string.transaction_generic);
                amount = net.toFriendlyString();
            }

            String txId = transaction.getTxId().toString();
            String shortTxId = txId.length() > 16
                    ? txId.substring(0, 8) + "..." + txId.substring(txId.length() - 8)
                    : txId;

            String time = transaction.updateTime().isPresent()
                    ? dateFormat.format(Date.from(transaction.updateTime().get()))
                    : context.getString(R.string.unknown_time);

            TransactionConfidence confidence = transaction.getConfidence();
            int depth = confidence == null ? 0 : confidence.getDepthInBlocks();
            int peers = confidence == null ? 0 : confidence.numBroadcastPeers();

            String confirmations = depth > 0
                    ? context.getString(depth == 1
                    ? R.string.confirmed_one
                    : R.string.confirmed_many, depth)
                    : context.getString(R.string.unconfirmed);

            String state;
            if (depth > 0) {
                state = context.getString(R.string.transaction_state_confirmed);
            } else if (confidence != null
                    && confidence.getConfidenceType() == TransactionConfidence.ConfidenceType.DEAD) {
                state = context.getString(R.string.transaction_state_dead);
            } else if (peers > 0) {
                state = context.getString(R.string.transaction_state_broadcast, peers);
            } else {
                state = context.getString(R.string.transaction_state_pending);
            }

            items.add(new TransactionItem(
                    type,
                    amount,
                    time,
                    confirmations,
                    shortTxId,
                    context.getString(R.string.transaction_peers, peers),
                    state));
        }

        return items;
    }
}
