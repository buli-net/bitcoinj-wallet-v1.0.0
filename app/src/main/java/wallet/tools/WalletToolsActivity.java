package wallet.tools;

import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.text.InputType;
import android.text.TextUtils;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.bitcoinj.base.Address;
import org.bitcoinj.base.BitcoinNetwork;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.TransactionOutput;
import org.bitcoinj.crypto.DumpedPrivateKey;
import org.bitcoinj.crypto.ECKey;
import org.bitcoinj.kits.WalletAppKit;
import org.bitcoinj.script.Script;
import org.bitcoinj.wallet.Wallet;
import org.bitcoinj.wallet.listeners.WalletChangeEventListener;

import java.time.Instant;
import java.util.Collections;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import wallet.Constants;
import wallet.main.MainActivityPresenter;
import wallet.main.R;
import wallet.security.WalletSecurity;

/** Wallet tools for private-key import and watch-only monitoring. */
public final class WalletToolsActivity extends AppCompatActivity {

    private EditText privateKeyInput;
    private EditText watchAddressInput;
    private EditText watchDateInput;
    private Button importPrivateKeyButton;
    private Button addWatchAddressButton;
    private LinearLayout watchedAddressList;
    private TextView watchedBalanceSummary;
    private Wallet watchedWallet;
    private WalletChangeEventListener walletChangeListener;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_wallet_tools);

        Toolbar toolbar = findViewById(R.id.toolbar_wallet_tools);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(R.string.wallet_tools_title);
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        privateKeyInput = findViewById(R.id.privateKeyInput);
        watchAddressInput = findViewById(R.id.watchAddressInput);
        watchDateInput = findViewById(R.id.watchDateInput);
        importPrivateKeyButton = findViewById(R.id.importPrivateKeyButton);
        addWatchAddressButton = findViewById(R.id.addWatchAddressButton);
        watchedBalanceSummary = findViewById(R.id.watchedBalanceSummary);
        watchedAddressList = findViewById(R.id.watchedAddressList);

        privateKeyInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        importPrivateKeyButton.setOnClickListener(v -> importPrivateKey());
        addWatchAddressButton.setOnClickListener(v -> addWatchAddress());
        attachWalletListener();
        refreshWatchedAddresses();
    }

    @Override
    protected void onResume() {
        super.onResume();
        attachWalletListener();
        refreshWatchedAddresses();
    }

    @Override
    protected void onPause() {
        detachWalletListener();
        super.onPause();
    }

    private Wallet getWallet() {
        WalletAppKit kit = MainActivityPresenter.getActiveWalletAppKit();
        return kit == null ? null : kit.wallet();
    }

    private NetworkParameters getParameters() {
        return MainActivityPresenter.getActiveParameters();
    }

    private void importPrivateKey() {
        String encoded = privateKeyInput.getText().toString().trim();
        if (TextUtils.isEmpty(encoded)) {
            show(R.string.private_key_required);
            return;
        }

        Wallet wallet = getWallet();
        NetworkParameters parameters = getParameters();
        if (wallet == null || parameters == null) {
            show(R.string.wallet_not_ready);
            return;
        }
        if (WalletSecurity.isEncrypted(wallet) && WalletSecurity.getSessionKey() == null) {
            show(R.string.wallet_locked);
            return;
        }

        importPrivateKeyButton.setEnabled(false);
        new Thread(() -> {
            try {
                ECKey key = DumpedPrivateKey.fromBase58(
                        Constants.IS_PRODUCTION ? BitcoinNetwork.MAINNET : BitcoinNetwork.TESTNET,
                        encoded).getKey();
                int added = WalletSecurity.isEncrypted(wallet)
                        ? wallet.importKeysAndEncrypt(
                                Collections.singletonList(key),
                                WalletSecurity.getSessionKey())
                        : (wallet.importKey(key) ? 1 : 0);
                MainActivityPresenter presenter = MainActivityPresenter.getActivePresenter();
                if (presenter != null) {
                    presenter.saveWalletNow();
                    presenter.refresh();
                }
                runOnUiThread(() -> {
                    importPrivateKeyButton.setEnabled(true);
                    privateKeyInput.setText("");
                    Toast.makeText(
                            this,
                            getString(added == 0 ? R.string.private_key_already_present : R.string.private_key_imported),
                            Toast.LENGTH_LONG).show();
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    importPrivateKeyButton.setEnabled(true);
                    Toast.makeText(this, getString(
                            R.string.private_key_import_failed,
                            error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage()),
                            Toast.LENGTH_LONG).show();
                });
            }
        }, "wallet-import-key").start();
    }

    private void addWatchAddress() {
        String encoded = watchAddressInput.getText().toString().trim();
        if (TextUtils.isEmpty(encoded)) {
            show(R.string.watch_address_required);
            return;
        }

        Wallet wallet = getWallet();
        NetworkParameters parameters = getParameters();
        if (wallet == null || parameters == null) {
            show(R.string.wallet_not_ready);
            return;
        }

        final Address address;
        try {
            address = Address.fromString(parameters, encoded);
        } catch (Exception error) {
            show(R.string.watch_address_invalid);
            return;
        }

        Instant creationTime;
        try {
            creationTime = parseDate(watchDateInput.getText().toString().trim());
        } catch (Exception error) {
            show(R.string.invalid_wallet_birthday);
            return;
        }

        addWatchAddressButton.setEnabled(false);
        new Thread(() -> {
            try {
                boolean added = creationTime == null
                        ? wallet.addWatchedAddress(address)
                        : wallet.addWatchedAddress(address, creationTime);
                MainActivityPresenter presenter = MainActivityPresenter.getActivePresenter();
                if (presenter != null) {
                    presenter.saveWalletNow();
                    presenter.refresh();
                }
                runOnUiThread(() -> {
                    addWatchAddressButton.setEnabled(true);
                    if (added) {
                        watchAddressInput.setText("");
                        watchDateInput.setText("");
                        refreshWatchedAddresses();
                        Toast.makeText(this, R.string.watch_address_added, Toast.LENGTH_LONG).show();
                    } else {
                        refreshWatchedAddresses();
                        Toast.makeText(this, R.string.watch_address_already_present, Toast.LENGTH_LONG).show();
                    }
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    addWatchAddressButton.setEnabled(true);
                    Toast.makeText(this, getString(
                            R.string.watch_address_failed,
                            error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage()),
                            Toast.LENGTH_LONG).show();
                });
            }
        }, "wallet-watch-address").start();
    }


    private void attachWalletListener() {
        Wallet wallet = getWallet();
        if (wallet == watchedWallet) {
            return;
        }

        detachWalletListener();
        if (wallet == null) {
            return;
        }

        watchedWallet = wallet;
        walletChangeListener = changedWallet ->
                runOnUiThread(this::refreshWatchedAddresses);
        wallet.addChangeEventListener(walletChangeListener);
    }

    private void detachWalletListener() {
        if (watchedWallet != null && walletChangeListener != null) {
            watchedWallet.removeChangeEventListener(walletChangeListener);
        }
        watchedWallet = null;
        walletChangeListener = null;
    }

    private void refreshWatchedAddresses() {
        Wallet wallet = getWallet();
        if (wallet == null || watchedAddressList == null) {
            return;
        }

        watchedAddressList.removeAllViews();

        List<Script> scripts = wallet.getWatchedScripts();
        if (scripts == null || scripts.isEmpty()) {
            if (watchedBalanceSummary != null) {
                watchedBalanceSummary.setText(
                        getString(R.string.watch_balance_total,
                                org.bitcoinj.base.Coin.ZERO.toFriendlyString()));
            }
            TextView empty = new TextView(this);
            empty.setText(R.string.watch_address_empty);
            empty.setPadding(0, 8, 0, 8);
            watchedAddressList.addView(empty);
            return;
        }

        NetworkParameters parameters = getParameters();
        List<TransactionOutput> outputs = wallet.getWatchedOutputs(false);
        Map<Script, WatchBalance> balances = new HashMap<>();
        for (Script script : scripts) {
            balances.put(script, new WatchBalance());
        }

        for (TransactionOutput output : outputs) {
            if (!output.isAvailableForSpending()) {
                continue;
            }

            Script outputScript = output.getScriptPubKey();
            WatchBalance balance = balances.get(outputScript);
            if (balance == null) {
                continue;
            }

            long value = output.getValue().value;
            if (output.getParentTransactionDepthInBlocks() > 0) {
                balance.confirmed += value;
            } else {
                balance.pending += value;
            }
        }

        long watchedTotal = 0L;
        for (Script script : scripts) {
            WatchBalance balance = balances.get(script);
            watchedTotal += balance.total();

            TextView item = new TextView(this);
            item.setTextIsSelectable(true);
            item.setPadding(0, 12, 0, 12);

            String type = script.getScriptType() == null
                    ? "UNKNOWN"
                    : script.getScriptType().name();
            String address;
            try {
                address = script.getToAddress(parameters).toString();
            } catch (Exception error) {
                address = script.toString();
            }

            item.setText(getString(
                    R.string.watch_address_row,
                    type,
                    address,
                    org.bitcoinj.base.Coin.valueOf(balance.confirmed).toFriendlyString(),
                    org.bitcoinj.base.Coin.valueOf(balance.pending).toFriendlyString(),
                    org.bitcoinj.base.Coin.valueOf(balance.total()).toFriendlyString()));
            watchedAddressList.addView(item);
        }

        if (watchedBalanceSummary != null) {
            watchedBalanceSummary.setText(getString(
                    R.string.watch_balance_total,
                    org.bitcoinj.base.Coin.valueOf(watchedTotal).toFriendlyString()));
        }
    }

    private static final class WatchBalance {
        private long confirmed;
        private long pending;

        private long total() {
            return confirmed + pending;
        }
    }

    private Instant parseDate(String value) {
        if (TextUtils.isEmpty(value)) {
            return null;
        }
        return LocalDate.parse(value)
                .atStartOfDay()
                .toInstant(ZoneOffset.UTC);
    }

    private void show(int messageId) {
        Toast.makeText(this, messageId, Toast.LENGTH_LONG).show();
    }
}
