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
import java.util.List;
import java.util.Locale;

import wallet.main.MainActivityPresenter;
import wallet.main.R;
import wallet.main.WalletSelection;

/** Simple wallet-style transaction detail. Heavy work stays off the UI thread. */
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

    /** Find the transaction once, then prepare everything once in the worker thread. */
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
                Transaction transaction = findTransaction(wallet, Sha256Hash.wrap(txid));
                DetailData data = transaction == null ? null : prepareDetail(transaction, wallet);
                runOnUiThread(() -> render(data));
            } catch (Exception error) {
                String message = error.getMessage();
                runOnUiThread(() -> showUnavailable(getString(
                        R.string.transaction_detail_failed,
                        message == null ? error.getClass().getSimpleName() : message)));
            }
        }, "transaction-detail").start();
    }

    private Transaction findTransaction(Wallet wallet, Sha256Hash wanted) {
        for (Transaction transaction : wallet.getTransactions(true)) {
            if (wanted.equals(transaction.getTxId())) {
                return transaction;
            }
        }
        return null;
    }

    private DetailData prepareDetail(Transaction transaction, Wallet wallet) {
        Script selectedWatchScript = WalletSelection.findSelectedScript(this, wallet);

        Coin received = selectedWatchScript == null
                ? safeValueSentToMe(transaction, wallet)
                : sumWalletOutputs(transaction, wallet, selectedWatchScript);
        Coin sent = selectedWatchScript == null
                ? safeValueSentFromMe(transaction, wallet)
                : sumWatchedInputs(transaction, selectedWatchScript);

        Coin net = received.subtract(sent);
        boolean isReceived = net.isPositive();

        List<TxEntry> sentEntries;
        List<TxEntry> receivedEntries;

        if (isReceived) {
            // Keep the useful Schildbach-style simplicity: all real inputs are
            // shown for an incoming transaction. If bitcoinj has connected the
            // previous output, use its exact address/type/value. Otherwise use
            // the input's own sender address when the API can provide it.
            sentEntries = collectAllInputEntries(transaction);
            receivedEntries = collectWalletOutputs(transaction, wallet, selectedWatchScript);
        } else {
            sentEntries = collectOwnInputs(transaction, wallet, selectedWatchScript);
            receivedEntries = collectWalletOutputs(transaction, wallet, selectedWatchScript);
        }

        String from = sentEntries.isEmpty()
                ? firstExternalOutput(transaction, wallet, selectedWatchScript)
                : sentEntries.get(0).address;
        String to = receivedEntries.isEmpty()
                ? firstWalletOutput(transaction, wallet, selectedWatchScript)
                : receivedEntries.get(0).address;

        return new DetailData(
                isReceived,
                net,
                from,
                to,
                sentEntries,
                receivedEntries,
                buildTransactionRows(transaction),
                isReceived && !transaction.getInputs().isEmpty(),
                transaction.getTxId().toString());
    }

    private void render(DetailData data) {
        if (data == null) {
            showUnavailable(R.string.transaction_detail_unavailable);
            return;
        }

        boolean isSent = data.net.isNegative();
        transactionType.setText(isSent
                ? R.string.transaction_sent
                : data.isReceived ? R.string.transaction_received : R.string.transaction_generic);
        transactionAmount.setText(formatSigned(data.net));

        transactionFrom.setText(getString(R.string.transaction_from_label) + ": " + data.from);
        transactionTo.setText(getString(R.string.transaction_to_label) + ": " + data.to);
        configureSingleLineMiddleEllipsis(transactionFrom);
        configureSingleLineMiddleEllipsis(transactionTo);

        renderTransactionRows(data.detailRows);
        renderEntries(sentDetailsRows, data.sentEntries, R.string.transaction_total_from);
        renderEntries(receivedDetailsRows, data.receivedEntries, R.string.transaction_total_to);

        sentDetailsCard.setVisibility(
                data.showSentCard || !data.sentEntries.isEmpty() ? View.VISIBLE : View.GONE);
        receivedDetailsCard.setVisibility(
                data.receivedEntries.isEmpty() ? View.GONE : View.VISIBLE);
        transactionIdValue.setText(data.txid);
        configureSingleLineMiddleEllipsis(transactionIdValue);
    }

    private List<RowData> buildTransactionRows(Transaction transaction) {
        List<RowData> rows = new ArrayList<>();
        TransactionConfidence confidence = transaction.getConfidence();
        int depth = confidence == null ? 0 : confidence.getDepthInBlocks();

        rows.add(new RowData(getString(R.string.transaction_status_label), status(transaction)));

        Coin fee = safeFee(transaction);
        rows.add(new RowData(
                getString(R.string.transaction_fee_label),
                fee == null ? "—" : fee.toFriendlyString()));

        // Serialization happens here, on the worker thread, never while drawing the screen.
        int size = transaction.bitcoinSerialize().length;
        long weight = getWeightUnits(transaction, size);
        long vbytes = (weight + 3L) / 4L;
        long feeRate = fee == null || vbytes <= 0L ? 0L : Math.max(0L, fee.value / vbytes);
        rows.add(new RowData(
                getString(R.string.transaction_size_weight_label),
                getString(R.string.transaction_size_weight, size, weight, feeRate)));

        int appearedHeight = getAppearedHeight(transaction);
        MainActivityPresenter presenter = MainActivityPresenter.getActivePresenter();
        int bestHeight = presenter == null ? 0 : presenter.getBestChainHeight();
        String confirmations = depth > 0 && appearedHeight > 0 && bestHeight > 0
                ? getString(R.string.transaction_height_pair, depth, appearedHeight, bestHeight)
                : String.valueOf(depth);
        rows.add(new RowData(getString(R.string.transaction_confirmations_label), confirmations));
        rows.add(new RowData(getString(R.string.transaction_time_label), formatTime(transaction)));
        rows.add(new RowData(getString(R.string.transaction_current_time_label), formatNow()));
        rows.add(new RowData(getString(R.string.transaction_age_label), formatAge(transaction)));
        rows.add(new RowData(getString(R.string.transaction_version_label), String.valueOf(transaction.getVersion())));
        return rows;
    }

    private void renderTransactionRows(List<RowData> rows) {
        transactionDetailsRows.removeAllViews();
        for (RowData row : rows) {
            addRow(row.label, row.value);
        }
    }

    private void renderEntries(LinearLayout container, List<TxEntry> entries, int totalString) {
        container.removeAllViews();
        Coin total = sum(entries);

        TextView summary = valueText(getString(totalString, total.toFriendlyString(), entries.size()));
        summary.setTypeface(null, android.graphics.Typeface.BOLD);
        summary.setPadding(0, dp(8), 0, dp(8));
        container.addView(summary);

        for (TxEntry entry : entries) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(0, dp(7), 0, dp(7));
            row.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
            row.setClipChildren(false);

            TextView address = valueText(entry.address + " (" + entry.type + ")");
            address.setTypeface(android.graphics.Typeface.MONOSPACE);
            address.setTextIsSelectable(true);
            configureSingleLineMiddleEllipsis(address);
            address.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
            row.addView(address);

            row.addView(valueText(entry.amount.toFriendlyString()));
            container.addView(row);
        }
    }

    private List<TxEntry> collectOwnInputs(
            Transaction transaction, Wallet wallet, Script selectedWatchScript) {
        List<TxEntry> result = new ArrayList<>();
        for (TransactionInput input : transaction.getInputs()) {
            TransactionOutput output = connectedOutput(input);
            if (output == null || !belongsToSelectedWallet(output, wallet, selectedWatchScript)) {
                continue;
            }
            result.add(new TxEntry(addressOf(output), scriptType(output), output.getValue()));
        }
        return result;
    }

    private List<TxEntry> collectAllInputEntries(Transaction transaction) {
        List<TxEntry> result = new ArrayList<>();
        for (TransactionInput input : transaction.getInputs()) {
            TransactionOutput output = connectedOutput(input);
            if (output != null) {
                result.add(new TxEntry(addressOf(output), scriptType(output), output.getValue()));
                continue;
            }

            TxEntry simpleSender = senderFromInput(input);
            if (simpleSender != null) {
                result.add(simpleSender);
            } else {
                Coin value = input.getValue();
                if (value != null && !value.isZero()) {
                    result.add(new TxEntry(
                            getString(R.string.transaction_unknown_address),
                            "UNKNOWN",
                            value));
                }
            }
        }
        return result;
    }

    private TxEntry senderFromInput(TransactionInput input) {
        try {
            Method method = input.getClass().getMethod("getFromAddress");
            Object value = method.invoke(input);
            if (value != null) {
                Coin amount = input.getValue();
                if (amount != null && !amount.isZero()) {
                    return new TxEntry(value.toString(), "INPUT", amount);
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private List<TxEntry> collectWalletOutputs(
            Transaction transaction, Wallet wallet, Script selectedWatchScript) {
        List<TxEntry> result = new ArrayList<>();
        for (TransactionOutput output : transaction.getOutputs()) {
            if (belongsToSelectedWallet(output, wallet, selectedWatchScript)) {
                result.add(new TxEntry(addressOf(output), scriptType(output), output.getValue()));
            }
        }
        return result;
    }

    private Coin sumWalletOutputs(Transaction transaction, Wallet wallet, Script selectedWatchScript) {
        Coin total = Coin.ZERO;
        for (TransactionOutput output : transaction.getOutputs()) {
            if (belongsToSelectedWallet(output, wallet, selectedWatchScript)) {
                total = total.add(output.getValue());
            }
        }
        return total;
    }

    private Coin sumWatchedInputs(Transaction transaction, Script selectedWatchScript) {
        Coin total = Coin.ZERO;
        for (TransactionInput input : transaction.getInputs()) {
            TransactionOutput output = connectedOutput(input);
            if (output != null && selectedWatchScript.equals(output.getScriptPubKey())) {
                total = total.add(output.getValue());
            }
        }
        return total;
    }

    private boolean belongsToSelectedWallet(
            TransactionOutput output, Wallet wallet, Script selectedWatchScript) {
        if (selectedWatchScript != null) {
            return selectedWatchScript.equals(output.getScriptPubKey());
        }
        return output.isMine(wallet) && !WalletSelection.isWatchedOutput(wallet, output);
    }

    private TransactionOutput connectedOutput(TransactionInput input) {
        if (input == null) {
            return null;
        }
        try {
            return input.getConnectedOutput();
        } catch (Exception ignored) {
            return null;
        }
    }

    private String firstExternalOutput(
            Transaction transaction, Wallet wallet, Script selectedWatchScript) {
        for (TransactionOutput output : transaction.getOutputs()) {
            if (!belongsToSelectedWallet(output, wallet, selectedWatchScript)) {
                return addressOf(output);
            }
        }
        return getString(R.string.transaction_unknown_address);
    }

    private String firstWalletOutput(
            Transaction transaction, Wallet wallet, Script selectedWatchScript) {
        for (TransactionOutput output : transaction.getOutputs()) {
            if (belongsToSelectedWallet(output, wallet, selectedWatchScript)) {
                return addressOf(output);
            }
        }
        return getString(R.string.transaction_unknown_address);
    }

    private String addressOf(TransactionOutput output) {
        try {
            return output.getScriptPubKey()
                    .getToAddress(MainActivityPresenter.getActiveParameters())
                    .toString();
        } catch (Exception ignored) {
            return getString(R.string.transaction_unknown_address);
        }
    }

    private String scriptType(TransactionOutput output) {
        Object type = output.getScriptPubKey().getScriptType();
        return type == null ? "UNKNOWN" : type.toString();
    }

    private Coin safeValueSentFromMe(Transaction transaction, Wallet wallet) {
        try {
            Coin value = transaction.getValueSentFromMe(wallet);
            return value == null ? Coin.ZERO : value;
        } catch (Exception ignored) {
            return Coin.ZERO;
        }
    }

    private Coin safeValueSentToMe(Transaction transaction, Wallet wallet) {
        try {
            Coin value = transaction.getValueSentToMe(wallet);
            return value == null ? Coin.ZERO : value;
        } catch (Exception ignored) {
            return Coin.ZERO;
        }
    }

    private Coin sum(List<TxEntry> entries) {
        Coin total = Coin.ZERO;
        for (TxEntry entry : entries) {
            total = total.add(entry.amount);
        }
        return total;
    }

    private String formatSigned(Coin net) {
        if (net.isPositive()) return "+" + net.toFriendlyString();
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
            if (value instanceof Number) return ((Number) value).longValue();
        } catch (Exception ignored) {
        }
        return size * 4L;
    }

    private int getAppearedHeight(Transaction transaction) {
        try {
            TransactionConfidence confidence = transaction.getConfidence();
            if (confidence == null) return 0;
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
        if (!transaction.updateTime().isPresent()) return getString(R.string.unknown_time);
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                .format(Date.from(transaction.updateTime().get()));
    }

    private String formatNow() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
    }

    private String formatAge(Transaction transaction) {
        if (!transaction.updateTime().isPresent()) return "—";
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
        return resolveThemeColor(android.R.attr.textColorPrimary, android.graphics.Color.WHITE);
    }

    private int resolveSecondaryTextColor() {
        return resolveThemeColor(android.R.attr.textColorSecondary, resolvePrimaryTextColor());
    }

    private int resolveThemeColor(int attribute, int fallback) {
        android.util.TypedValue value = new android.util.TypedValue();
        if (!getTheme().resolveAttribute(attribute, value, true)) return fallback;
        if (value.resourceId != 0) {
            try {
                return android.support.v4.content.ContextCompat.getColor(this, value.resourceId);
            } catch (Exception ignored) {
            }
        }
        if (value.type >= android.util.TypedValue.TYPE_FIRST_COLOR_INT
                && value.type <= android.util.TypedValue.TYPE_LAST_COLOR_INT) {
            return value.data;
        }
        return fallback;
    }

    private void configureSingleLineMiddleEllipsis(TextView view) {
        view.setSingleLine(true);
        view.setMaxLines(1);
        view.setHorizontallyScrolling(true);
        view.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        view.setIncludeFontPadding(false);
        view.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
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
        if (TextUtils.isEmpty(txid)) return;
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText("TXID", txid));
        Toast.makeText(this, R.string.transaction_id_copied, Toast.LENGTH_SHORT).show();
    }

    private static final class RowData {
        final String label;
        final String value;
        RowData(String label, String value) {
            this.label = label;
            this.value = value;
        }
    }

    private static final class DetailData {
        final boolean isReceived;
        final Coin net;
        final String from;
        final String to;
        final List<TxEntry> sentEntries;
        final List<TxEntry> receivedEntries;
        final List<RowData> detailRows;
        final boolean showSentCard;
        final String txid;

        DetailData(boolean isReceived, Coin net, String from, String to,
                   List<TxEntry> sentEntries, List<TxEntry> receivedEntries,
                   List<RowData> detailRows, boolean showSentCard, String txid) {
            this.isReceived = isReceived;
            this.net = net;
            this.from = from;
            this.to = to;
            this.sentEntries = sentEntries;
            this.receivedEntries = receivedEntries;
            this.detailRows = detailRows;
            this.showSentCard = showSentCard;
            this.txid = txid;
        }
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

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
