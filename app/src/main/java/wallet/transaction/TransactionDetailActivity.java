package wallet.transaction;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.app.AlertDialog;
import android.support.v7.widget.Toolbar;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import org.bitcoinj.base.Coin;
import org.bitcoinj.base.Sha256Hash;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.TransactionInput;
import org.bitcoinj.core.TransactionOutput;
import org.bitcoinj.kits.WalletAppKit;
import org.bitcoinj.wallet.Wallet;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import wallet.main.MainActivityPresenter;
import wallet.main.R;

/** Detailed view for a wallet transaction. */
public final class TransactionDetailActivity extends AppCompatActivity {

    public static final String EXTRA_TXID = "txid";

    private String txid;
    private TextView content;

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

        content = findViewById(R.id.transactionDetailContent);
        Button copy = findViewById(R.id.copyTransactionIdButton);
        copy.setOnClickListener(v -> copyTxid());

        txid = getIntent().getStringExtra(EXTRA_TXID);
        loadTransaction();
    }

    private void loadTransaction() {
        if (txid == null || txid.trim().isEmpty()) {
            content.setText(R.string.transaction_detail_unavailable);
            return;
        }

        WalletAppKit kit = MainActivityPresenter.getActiveWalletAppKit();
        if (kit == null) {
            content.setText(R.string.wallet_not_ready);
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
                runOnUiThread(() -> content.setText(getString(
                        R.string.transaction_detail_failed,
                        error.getMessage() == null
                                ? error.getClass().getSimpleName()
                                : error.getMessage())));
            }
        }, "transaction-detail").start();
    }

    private void render(Transaction transaction) {
        if (transaction == null) {
            content.setText(R.string.transaction_detail_unavailable);
            return;
        }

        WalletAppKit kit = MainActivityPresenter.getActiveWalletAppKit();
        Wallet wallet = kit == null ? null : kit.wallet();
        StringBuilder out = new StringBuilder();
        out.append(label(R.string.transaction_detail_status))
                .append(status(transaction)).append("\n\n");
        out.append(label(R.string.transaction_detail_txid)).append(transaction.getTxId()).append("\n\n");

        int depth = transaction.getConfidence() == null
                ? 0 : transaction.getConfidence().getDepthInBlocks();
        out.append(label(R.string.transaction_detail_confirmations)).append(depth).append("\n");
        out.append(label(R.string.transaction_detail_time)).append(formatTime(transaction)).append("\n");
        out.append(label(R.string.transaction_detail_version)).append(transaction.getVersion()).append("\n");
        out.append(label(R.string.transaction_detail_size)).append(transaction.bitcoinSerialize().length).append(" bytes\n");

        if (wallet != null) {
            try {
                Coin received = transaction.getValueSentToMe(wallet);
                Coin sent = transaction.getValueSentFromMe(wallet);
                Coin net = received.subtract(sent);
                out.append(label(R.string.transaction_detail_net)).append(net.toFriendlyString()).append("\n");
            } catch (Exception ignored) {
            }
        }

        try {
            Coin fee = transaction.getFee();
            if (fee != null) {
                out.append(label(R.string.transaction_detail_fee)).append(fee.toFriendlyString()).append("\n");
            }
        } catch (Exception ignored) {
            out.append(label(R.string.transaction_detail_fee)).append("—\n");
        }

        out.append("\n").append(label(R.string.transaction_detail_inputs))
                .append(transaction.getInputs().size()).append("\n");
        for (TransactionInput input : transaction.getInputs()) {
            out.append("  ").append(input.getOutpoint()).append("\n");
        }

        out.append("\n").append(label(R.string.transaction_detail_outputs))
                .append(transaction.getOutputs().size()).append("\n");
        for (TransactionOutput output : transaction.getOutputs()) {
            out.append("  ").append(output.getValue().toFriendlyString()).append("\n");
            try {
                out.append("     ").append(output.getScriptPubKey()
                        .getToAddress(MainActivityPresenter.getActiveParameters())).append("\n");
            } catch (Exception ignored) {
                out.append("     ").append(output.getScriptPubKey()).append("\n");
            }
        }

        content.setText(out.toString());
    }

    private String label(int id) {
        return getString(id) + ": ";
    }

    private String status(Transaction transaction) {
        if (transaction.getConfidence() != null
                && transaction.getConfidence().getDepthInBlocks() > 0) {
            return getString(R.string.transaction_state_confirmed);
        }
        if (transaction.getConfidence() != null
                && transaction.getConfidence().getConfidenceType()
                == org.bitcoinj.core.TransactionConfidence.ConfidenceType.DEAD) {
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

    private void copyTxid() {
        if (txid == null || txid.isEmpty()) {
            return;
        }
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText("TXID", txid));
        Toast.makeText(this, R.string.transaction_id_copied, Toast.LENGTH_SHORT).show();
    }
}
