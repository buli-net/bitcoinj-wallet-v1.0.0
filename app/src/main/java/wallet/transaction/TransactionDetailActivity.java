package wallet.transaction;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.bitcoinj.base.Coin;
import org.bitcoinj.base.Sha256Hash;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.TransactionConfidence;
import org.bitcoinj.core.TransactionInput;
import org.bitcoinj.core.TransactionOutput;
import org.bitcoinj.kits.WalletAppKit;
import org.bitcoinj.script.Script;
import org.bitcoinj.wallet.Wallet;

import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import wallet.main.MainActivityPresenter;
import wallet.main.R;
import wallet.main.WalletSelection;

/** Clean, wallet-style transaction detail screen. */
public final class TransactionDetailActivity extends AppCompatActivity {

    public static final String EXTRA_TXID = "txid";

    private String txid;
    private TextView transactionType;
    private TextView transactionAmount;
    private TextView transactionFrom;
    private TextView transactionTo;
    private LinearLayout transactionDetailsRows;
    private LinearLayout sentDetailsCard;
    private LinearLayout sentDetailsRows;
    private LinearLayout receivedDetailsCard;
    private LinearLayout receivedDetailsRows;
    private TextView transactionIdValue;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_transaction_detail);

        Toolbar toolbar = findViewById(R.id.toolbar_transaction_detail);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(R.string.transaction_detail_title);
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        transactionType = findViewById(R.id.transactionType);
        transactionAmount = findViewById(R.id.transactionAmount);
        transactionFrom = findViewById(R.id.transactionFrom);
        transactionTo = findViewById(R.id.transactionTo);
        transactionDetailsRows = findViewById(R.id.transactionDetailsRows);
        sentDetailsCard = findViewById(R.id.sentDetailsCard);
        sentDetailsRows = findViewById(R.id.sentDetailsRows);
        receivedDetailsCard = findViewById(R.id.receivedDetailsCard);
        receivedDetailsRows = findViewById(R.id.receivedDetailsRows);
        transactionIdValue = findViewById(R.id.transactionIdValue);

        Button copy = findViewById(R.id.copyTransactionIdButton);
        copy.setOnClickListener(v -> copyTxid());

        txid = getIntent().getStringExtra(EXTRA_TXID);
        loadTransaction();
    }

    private void loadTransaction() {
        if (TextUtils.isEmpty(txid)) {
            showUnavailable(R.string.transaction_detail_unavailable);
            return;
        }

        WalletAppKit kit = MainActivityPresenter.getActiveWalletAppKit();
        if (kit == null) {
            showUnavailable(R.string.wallet_not_ready);
            return;
        }

        new Thread(() -> {
            try {
                Wallet wallet = kit.wallet();
                Transaction found = null;
                Sha256Hash wanted = Sha256Hash.wrap(txid);
                for (Transaction candidate : wallet.getTransactions(true)) {
                    if (wanted.equals(candidate.getTxId())) {
                        found = candidate;
                        break;
                    }
                }
                final Transaction transaction = found;
                runOnUiThread(() -> render(transaction));
            } catch (Exception error) {
                runOnUiThread(() -> showUnavailable(getString(
                        R.string.transaction_detail_failed,
                        error.getMessage() == null
                                ? error.getClass().getSimpleName()
                                : error.getMessage())));
            }
        }, "transaction-detail").start();
    }

    private void render(Transaction transaction) {
        if (transaction == null) {
            showUnavailable(R.string.transaction_detail_unavailable);
            return;
        }

        WalletAppKit kit = MainActivityPresenter.getActiveWalletAppKit();
        Wallet wallet = kit == null ? null : kit.wallet();
        if (wallet == null) {
            showUnavailable(R.string.wallet_not_ready);
            return;
        }

        Map<Sha256Hash, Transaction> walletTransactions = indexWalletTransactions(wallet);
        Script selectedWatchScript = WalletSelection.findSelectedScript(this, wallet);
        List<TxEntry> sentEntries = collectSentEntries(transaction, wallet, walletTransactions, selectedWatchScript);
        List<TxEntry> receivedEntries = collectReceivedEntries(transaction, wallet, selectedWatchScript);

        Coin sent = sum(sentEntries);
        Coin received = sum(receivedEntries);
        Coin net = received.subtract(sent);
        boolean isSent = net.isNegative();
        boolean isReceived = net.isPositive();

        // For an incoming transaction, the detail screen must show the
        // complete transaction inputs under Sent Details, while Received
        // Details must contain only the output(s) belonging to the selected
        // wallet/watch-only address. The amount shown at the top is therefore
        // exactly what this wallet received, not the transaction's total output.
        if (isReceived) {
            sentEntries = collectAllInputEntries(transaction, walletTransactions);
            receivedEntries = collectWalletReceivedEntries(transaction, wallet, selectedWatchScript);
            sent = sum(sentEntries);
            received = sum(receivedEntries);
            net = received;
        }

        transactionType.setText(isSent
                ? R.string.transaction_sent
                : isReceived ? R.string.transaction_received : R.string.transaction_generic);
        transactionAmount.setText(formatSigned(net));
        transactionFrom.setText(getString(R.string.transaction_from_label) + ": "
                + summarizeAddress(firstSenderAddress(
                transaction, wallet, walletTransactions, selectedWatchScript, sentEntries)));
        transactionTo.setText(getString(R.string.transaction_to_label) + ": "
                + summarizeAddress(firstReceiverAddress(
                transaction, wallet, selectedWatchScript, receivedEntries)));

        renderTransactionRows(transaction, wallet);
        renderEntries(sentDetailsRows, sentEntries, R.string.transaction_total_from);
        renderEntries(receivedDetailsRows, receivedEntries, R.string.transaction_total_to);

        sentDetailsCard.setVisibility(sentEntries.isEmpty() ? View.GONE : View.VISIBLE);
        receivedDetailsCard.setVisibility(receivedEntries.isEmpty() ? View.GONE : View.VISIBLE);

        transactionIdValue.setText(transaction.getTxId().toString());
    }

    private void renderTransactionRows(Transaction transaction, Wallet wallet) {
        transactionDetailsRows.removeAllViews();

        int depth = transaction.getConfidence() == null
                ? 0 : transaction.getConfidence().getDepthInBlocks();
        String status = status(transaction);
        addRow(getString(R.string.transaction_status_label), status);

        Coin fee = safeFee(transaction);
        addRow(getString(R.string.transaction_fee_label),
                fee == null ? "—" : fee.toFriendlyString());

        int size = transaction.bitcoinSerialize().length;
        long weight = getWeightUnits(transaction, size);
        long vbytes = (weight + 3L) / 4L;
        long feeRate = fee == null || vbytes <= 0L ? 0L : Math.max(0L, fee.value / vbytes);
        addRow(getString(R.string.transaction_size_weight_label),
                getString(R.string.transaction_size_weight, size, weight, feeRate));

        int appearedHeight = getAppearedHeight(transaction);
        int bestHeight = MainActivityPresenter.getActivePresenter() == null
                ? 0 : MainActivityPresenter.getActivePresenter().getBestChainHeight();
        String confirmations;
        if (depth > 0 && appearedHeight > 0 && bestHeight > 0) {
            confirmations = getString(R.string.transaction_height_pair, depth, appearedHeight, bestHeight);
        } else {
            confirmations = String.valueOf(depth);
        }
        addRow(getString(R.string.transaction_confirmations_label), confirmations);

        addRow(getString(R.string.transaction_time_label), formatTime(transaction));
        addRow(getString(R.string.transaction_current_time_label), formatNow());
        addRow(getString(R.string.transaction_age_label), formatAge(transaction));
        addRow(getString(R.string.transaction_version_label), String.valueOf(transaction.getVersion()));
    }

    private void renderEntries(LinearLayout container, List<TxEntry> entries, int totalString) {
        container.removeAllViews();
        Coin total = sum(entries);
        TextView summary = valueText(getString(totalString, total.toFriendlyString(), entries.size()));
        summary.setTextColor(resolvePrimaryTextColor());
        summary.setTypeface(null, android.graphics.Typeface.BOLD);
        summary.setPadding(0, dp(8), 0, dp(8));
        container.addView(summary);

        for (TxEntry entry : entries) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(0, dp(7), 0, dp(7));

            TextView address = valueText(entry.address + " (" + entry.type + ")");
            address.setTextIsSelectable(true);
            address.setTypeface(android.graphics.Typeface.MONOSPACE);
            row.addView(address);

            TextView amount = valueText(entry.amount.toFriendlyString());
            amount.setTextColor(resolvePrimaryTextColor());
            row.addView(amount);
            container.addView(row);
        }
    }

    private List<TxEntry> collectSentEntries(
            Transaction transaction,
            Wallet wallet,
            Map<Sha256Hash, Transaction> walletTransactions,
            Script selectedWatchScript) {
        List<TxEntry> result = new ArrayList<>();
        for (TransactionInput input : transaction.getInputs()) {
            TransactionOutput output = findConnectedOutput(walletTransactions, input);
            if (output == null || !belongsToSelectedWallet(output, wallet, selectedWatchScript)) {
                continue;
            }
            result.add(new TxEntry(addressOf(output), scriptType(output), output.getValue()));
        }
        return result;
    }

    private List<TxEntry> collectReceivedEntries(
            Transaction transaction, Wallet wallet, Script selectedWatchScript) {
        List<TxEntry> result = new ArrayList<>();
        for (TransactionOutput output : transaction.getOutputs()) {
            if (belongsToSelectedWallet(output, wallet, selectedWatchScript)) {
                // For an outgoing transaction this is wallet change; the
                // existing sent-detail presentation keeps external outputs
                // under Received Details. Preserve that behavior.
                continue;
            }
            result.add(new TxEntry(addressOf(output), scriptType(output), output.getValue()));
        }

        if (result.isEmpty()) {
            for (TransactionOutput output : transaction.getOutputs()) {
                if (belongsToSelectedWallet(output, wallet, selectedWatchScript)) {
                    result.add(new TxEntry(addressOf(output), scriptType(output), output.getValue()));
                }
            }
        }
        return result;
    }

    private List<TxEntry> collectWalletReceivedEntries(
            Transaction transaction, Wallet wallet, Script selectedWatchScript) {
        List<TxEntry> result = new ArrayList<>();
        for (TransactionOutput output : transaction.getOutputs()) {
            if (belongsToSelectedWallet(output, wallet, selectedWatchScript)) {
                result.add(new TxEntry(addressOf(output), scriptType(output), output.getValue()));
            }
        }
        return result;
    }

    private List<TxEntry> collectAllInputEntries(
            Transaction transaction, Map<Sha256Hash, Transaction> walletTransactions) {
        List<TxEntry> result = new ArrayList<>();
        for (TransactionInput input : transaction.getInputs()) {
            TransactionOutput output = findConnectedOutput(walletTransactions, input);
            if (output != null) {
                result.add(new TxEntry(
                        addressOf(output),
                        scriptType(output),
                        output.getValue()));
                continue;
            }

            // Keep Sent Details visible even when the previous output is not
            // present in the wallet transaction index. bitcoinj can still
            // retain the input value after the transaction was connected.
            Coin inputValue = input.getValue();
            if (inputValue != null && !inputValue.isZero()) {
                result.add(new TxEntry(
                        getString(R.string.transaction_unknown_address),
                        "UNKNOWN",
                        inputValue));
            }
        }

        // As a final safety net, never hide the Sent Details card for an
        // incoming transaction just because individual input metadata was
        // unavailable. The aggregate input sum is still authoritative.
        if (result.isEmpty() && !transaction.getInputs().isEmpty()) {
            try {
                Coin inputSum = transaction.getInputSum();
                if (inputSum != null && !inputSum.isZero()) {
                    result.add(new TxEntry(
                            getString(R.string.transaction_unknown_address),
                            "UNKNOWN",
                            inputSum));
                }
            } catch (Exception ignored) {
            }
        }
        return result;
    }

    private String firstSenderAddress(
            Transaction transaction,
            Wallet wallet,
            Map<Sha256Hash, Transaction> walletTransactions,
            Script selectedWatchScript,
            List<TxEntry> sentEntries) {
        if (!sentEntries.isEmpty()) {
            return sentEntries.get(0).address;
        }
        for (TransactionInput input : transaction.getInputs()) {
            TransactionOutput output = findConnectedOutput(walletTransactions, input);
            if (output == null || belongsToSelectedWallet(output, wallet, selectedWatchScript)) {
                continue;
            }
            return addressOf(output);
        }
        return getString(R.string.transaction_unknown_address);
    }

    private String firstReceiverAddress(
            Transaction transaction,
            Wallet wallet,
            Script selectedWatchScript,
            List<TxEntry> receivedEntries) {
        if (!receivedEntries.isEmpty()) {
            return receivedEntries.get(0).address;
        }
        for (TransactionOutput output : transaction.getOutputs()) {
            if (belongsToSelectedWallet(output, wallet, selectedWatchScript)) {
                return addressOf(output);
            }
        }
        return getString(R.string.transaction_unknown_address);
    }

    private boolean belongsToSelectedWallet(TransactionOutput output, Wallet wallet, Script selectedWatchScript) {
        if (selectedWatchScript != null) {
            return selectedWatchScript.equals(output.getScriptPubKey());
        }
        return output.isMine(wallet) && !WalletSelection.isWatchedOutput(wallet, output);
    }

    private String addressOf(TransactionOutput output) {
        try {
            return output.getScriptPubKey()
                    .getToAddress(MainActivityPresenter.getActiveParameters()).toString();
        } catch (Exception ignored) {
            return getString(R.string.transaction_unknown_address);
        }
    }

    private String scriptType(TransactionOutput output) {
        Object type = output.getScriptPubKey().getScriptType();
        return type == null ? "UNKNOWN" : type.toString();
    }

    private TransactionOutput findConnectedOutput(
            Map<Sha256Hash, Transaction> walletTransactions, TransactionInput input) {
        if (input == null) {
            return null;
        }

        // Prefer bitcoinj's connected-output information when available.
        // This lets Received Details show every real input even when the
        // parent transaction is not currently present in the wallet index.
        try {
            TransactionOutput connected = input.getConnectedOutput();
            if (connected != null) {
                return connected;
            }
        } catch (Exception ignored) {
        }

        try {
            if (input.getOutpoint() != null) {
                TransactionOutput connected = input.getOutpoint().getConnectedOutput();
                if (connected != null) {
                    return connected;
                }
            }
        } catch (Exception ignored) {
        }

        if (input.getOutpoint() == null) {
            return null;
        }

        Transaction parent = walletTransactions.get(input.getOutpoint().hash());
        if (parent == null) {
            return null;
        }

        long index = input.getOutpoint().index();
        return index >= 0 && index < parent.getOutputs().size()
                ? parent.getOutput((int) index) : null;
    }

    private Map<Sha256Hash, Transaction> indexWalletTransactions(Wallet wallet) {
        Map<Sha256Hash, Transaction> result = new HashMap<>();
        for (Transaction transaction : wallet.getTransactions(true)) {
            result.put(transaction.getTxId(), transaction);
        }
        return result;
    }

    private Coin sum(List<TxEntry> entries) {
        Coin total = Coin.ZERO;
        for (TxEntry entry : entries) {
            total = total.add(entry.amount);
        }
        return total;
    }

    private String formatSigned(Coin net) {
        if (net.isPositive()) {
            return "+" + net.toFriendlyString();
        }
        if (net.isNegative()) {
            return net.toFriendlyString();
        }
        return net.toFriendlyString();
    }

    private Coin safeFee(Transaction transaction) {
        try {
            return transaction.getFee();
        } catch (Exception ignored) {
            return null;
        }
    }

    private long getWeightUnits(Transaction transaction, int size) {
        try {
            Method method = transaction.getClass().getMethod("getWeight");
            Object value = method.invoke(transaction);
            if (value instanceof Number) {
                return ((Number) value).longValue();
            }
        } catch (Exception ignored) {
        }
        return size * 4L;
    }

    private int getAppearedHeight(Transaction transaction) {
        try {
            TransactionConfidence confidence = transaction.getConfidence();
            if (confidence == null) {
                return 0;
            }
            Method method = confidence.getClass().getMethod("getAppearedAtChainHeight");
            Object value = method.invoke(confidence);
            return value instanceof Number ? ((Number) value).intValue() : 0;
        } catch (Exception ignored) {
            return 0;
        }
    }

    private String status(Transaction transaction) {
        TransactionConfidence confidence = transaction.getConfidence();
        if (confidence != null && confidence.getDepthInBlocks() > 0) {
            return getString(R.string.transaction_state_confirmed);
        }
        if (confidence != null
                && confidence.getConfidenceType() == TransactionConfidence.ConfidenceType.DEAD) {
            return getString(R.string.transaction_state_dead);
        }
        return getString(R.string.transaction_state_pending);
    }

    private String formatTime(Transaction transaction) {
        if (!transaction.updateTime().isPresent()) {
            return getString(R.string.unknown_time);
        }
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                .format(Date.from(transaction.updateTime().get()));
    }

    private String formatNow() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                .format(new Date());
    }

    private String formatAge(Transaction transaction) {
        if (!transaction.updateTime().isPresent()) {
            return "—";
        }
        long age = Math.max(0L, System.currentTimeMillis()
                - transaction.updateTime().get().toEpochMilli());
        long seconds = age / 1000L;
        if (seconds < 60L) return seconds + " seconds ago";
        if (seconds < 3600L) return (seconds / 60L) + " minutes " + (seconds % 60L) + " seconds ago";
        if (seconds < 86400L) return (seconds / 3600L) + " hours "
                + ((seconds % 3600L) / 60L) + " minutes " + (seconds % 60L) + " seconds ago";
        return (seconds / 86400L) + " days " + ((seconds % 86400L) / 3600L)
                + " hours " + ((seconds % 3600L) / 60L) + " minutes "
                + (seconds % 60L) + " seconds ago";
    }

    private void addRow(String label, String value) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.TOP);
        row.setPadding(0, dp(7), 0, dp(7));

        TextView labelView = valueText(label);
        labelView.setTextColor(resolveSecondaryTextColor());
        labelView.setLayoutParams(new LinearLayout.LayoutParams(dp(112),
                LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView valueView = valueText(value);
        valueView.setTextColor(resolvePrimaryTextColor());
        valueView.setTextIsSelectable(true);
        valueView.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        row.addView(labelView);
        row.addView(valueView);
        transactionDetailsRows.addView(row);
    }

    private TextView valueText(String value) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextSize(13f);
        text.setTextColor(resolvePrimaryTextColor());
        return text;
    }

    private int resolvePrimaryTextColor() {
        android.util.TypedValue value = new android.util.TypedValue();
        getTheme().resolveAttribute(android.support.v7.appcompat.R.attr.colorControlNormal, value, true);
        return getResources().getColor(value.resourceId);
    }

    private int resolveSecondaryTextColor() {
        android.util.TypedValue value = new android.util.TypedValue();
        getTheme().resolveAttribute(android.R.attr.textColorSecondary, value, true);
        return getResources().getColor(value.resourceId);
    }

    private String summarizeAddress(String address) {
        if (address == null) return "—";
        if (address.length() <= 44) return address;
        return address.substring(0, 20) + "..." + address.substring(address.length() - 20);
    }

    private void showUnavailable(String text) {
        transactionType.setText(R.string.transaction_detail_title);
        transactionAmount.setText(text);
        transactionFrom.setText("");
        transactionTo.setText("");
        transactionDetailsRows.removeAllViews();
        sentDetailsCard.setVisibility(View.GONE);
        receivedDetailsCard.setVisibility(View.GONE);
        transactionIdValue.setText(txid == null ? "—" : txid);
    }

    private void showUnavailable(int resource) {
        showUnavailable(getString(resource));
    }

    private void copyTxid() {
        if (TextUtils.isEmpty(txid)) {
            return;
        }
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText("TXID", txid));
        Toast.makeText(this, R.string.transaction_id_copied, Toast.LENGTH_SHORT).show();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static final class TxEntry {
        final String address;
        final String type;
        final Coin amount;

        TxEntry(String address, String type, Coin amount) {
            this.address = address;
            this.type = type;
            this.amount = amount;
        }
    }
}
