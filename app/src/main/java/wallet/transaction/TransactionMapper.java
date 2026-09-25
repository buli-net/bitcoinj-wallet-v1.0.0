package wallet.transaction;

import android.content.Context;

import org.bitcoinj.base.Coin;
import org.bitcoinj.core.Transaction;
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

            int depth = transaction.getConfidence() == null
                    ? 0
                    : transaction.getConfidence().getDepthInBlocks();
            int peers = transaction.getConfidence() == null
                    ? 0
                    : transaction.getConfidence().numBroadcastPeers();

            String confirmations;
            if (depth > 0) {
                confirmations = context.getString(depth == 1
                        ? R.string.confirmed_one
                        : R.string.confirmed_many, depth);
            } else if (peers > 0) {
                confirmations = context.getString(R.string.broadcasting_peers, peers);
            } else {
                confirmations = context.getString(R.string.unconfirmed);
            }

            Coin fee = transaction.getFee();
            String feeText = fee == null
                    ? context.getString(R.string.not_available)
                    : fee.toFriendlyString();

            String blockHeight = depth > 0 && transaction.getConfidence() != null
                    ? String.valueOf(transaction.getConfidence().getAppearedAtChainHeight())
                    : context.getString(R.string.not_available);

            items.add(new TransactionItem(
                    type,
                    amount,
                    time,
                    confirmations,
                    shortTxId,
                    context.getString(R.string.transaction_detail_txid, txId),
                    context.getString(R.string.transaction_detail_type, type),
                    context.getString(R.string.transaction_detail_amount, amount),
                    context.getString(R.string.transaction_detail_time, time),
                    context.getString(R.string.transaction_detail_status, confirmations),
                    context.getString(R.string.transaction_detail_fee, feeText),
                    context.getString(R.string.transaction_detail_peers, peers),
                    context.getString(R.string.transaction_detail_inputs, transaction.getInputs().size()),
                    context.getString(R.string.transaction_detail_outputs, transaction.getOutputs().size()),
                    context.getString(R.string.transaction_detail_size, transaction.bitcoinSerialize().length),
                    context.getString(R.string.transaction_detail_version, transaction.getVersion()),
                    context.getString(R.string.transaction_detail_block_height, blockHeight)));
        }

        return items;
    }
}
