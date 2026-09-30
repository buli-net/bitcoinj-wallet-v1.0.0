package wallet.tools;

import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.widget.TextView;
import android.widget.Toast;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.base.Coin;
import org.bitcoinj.script.Script;
import org.bitcoinj.kits.WalletAppKit;
import org.bitcoinj.wallet.Wallet;
import wallet.main.MainActivityPresenter;
import wallet.main.R;
import android.text.InputType;
import android.text.TextUtils;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.view.View;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.support.v7.app.AlertDialog;
import org.bitcoinj.base.BitcoinNetwork;
import org.bitcoinj.base.LegacyAddress;
import wallet.main.WalletSelection;
import wallet.main.ImportedWalletStore;
import wallet.qr.ReceiveQrDialog;
import org.bitcoinj.crypto.DumpedPrivateKey;
import org.bitcoinj.crypto.ECKey;
import java.util.Collections;
import wallet.Constants;
import wallet.security.WalletSecurity;

public final class WalletImportWifActivity extends AppCompatActivity {

    private Wallet getWallet() { WalletAppKit kit = MainActivityPresenter.getActiveWalletAppKit(); return kit == null ? null : kit.wallet(); }
    private NetworkParameters getParameters() { return MainActivityPresenter.getActiveParameters(); }
    private final Runnable walletUpdateCallback = this::onWalletUpdated;
    private int renderRetries;
    private void show(int messageId) { Toast.makeText(this, messageId, Toast.LENGTH_LONG).show(); }
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state); setContentView(R.layout.activity_wallet_import_wif);
        Toolbar toolbar = findViewById(R.id.toolbar_wallet_import_wif); setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) { getSupportActionBar().setTitle(R.string.wallet_import_wif_title); getSupportActionBar().setDisplayHomeAsUpEnabled(true); }
        toolbar.setNavigationOnClickListener(v -> finish());
        EditText input = findViewById(R.id.privateKeyInput); Button importButton = findViewById(R.id.importPrivateKeyButton);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        importButton.setOnClickListener(v -> importPrivateKey(input, importButton));
        findViewById(R.id.wifInfoButton).setOnClickListener(v -> showWifInfo());

        MainActivityPresenter presenter = MainActivityPresenter.getActivePresenter();
        if (presenter != null) {
            presenter.addWalletUpdateListener(walletUpdateCallback);
        }
        renderRetries = 0;
        renderImportedWallets();
        scheduleWalletListRetry();
    }

    @Override protected void onResume() {
        super.onResume();
        renderRetries = 0;
        renderImportedWallets();
        scheduleWalletListRetry();
    }

    private void scheduleWalletListRetry() {
        View card = findViewById(R.id.importedWalletsCard);
        if (card == null) return;
        card.postDelayed(() -> {
            if (isFinishing()) return;
            renderImportedWallets();
            Wallet wallet = getWallet();
            if (wallet == null && renderRetries < 12) {
                renderRetries++;
                scheduleWalletListRetry();
            }
        }, 250L);
    }

    private void onWalletUpdated() {
        runOnUiThread(() -> {
            if (!isFinishing()) {
                renderImportedWallets();
            }
        });
    }

    @Override protected void onDestroy() {
        MainActivityPresenter presenter = MainActivityPresenter.getActivePresenter();
        if (presenter != null) {
            presenter.removeWalletUpdateListener(walletUpdateCallback);
        }
        super.onDestroy();
    }

    private void importPrivateKey(EditText privateKeyInput, Button importPrivateKeyButton) {
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
                final String importedAddress = LegacyAddress.fromKey(parameters, key).toString();
                WalletSelection.selectImportedAddress(this, importedAddress);
                if (added > 0) {
                    int index = WalletSelection.getManagedImportedAddresses(this, wallet).indexOf(importedAddress) + 1;
                    if (index < 1) index = 1;
                    String currentName = ImportedWalletStore.getName(this, importedAddress, index);
                    ImportedWalletStore.setName(this, importedAddress, currentName);
                }
                MainActivityPresenter presenter = MainActivityPresenter.getActivePresenter();
                if (presenter != null) {
                    presenter.saveWalletNow();
                    presenter.refresh();
                }
                runOnUiThread(() -> {
                    importPrivateKeyButton.setEnabled(true);
                    privateKeyInput.setText("");
                    new android.support.v7.app.AlertDialog.Builder(this)
                            .setTitle(added == 0
                                    ? R.string.private_key_already_present
                                    : R.string.private_key_imported)
                            .setMessage(getString(R.string.private_key_import_address, importedAddress))
                            .setPositiveButton(R.string.close, null)
                            .show();
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
    private void renderImportedWallets() {
        LinearLayout container = findViewById(R.id.importedWalletsContainer);
        TextView empty = findViewById(R.id.importedWalletsEmpty);
        if (container == null || empty == null) return;
        container.removeAllViews();

        Wallet wallet = getWallet();
        if (wallet == null) {
            empty.setVisibility(View.VISIBLE);
            return;
        }

        // The management screen is driven by the persistent registry. Merge any
        // keys already present in bitcoinj so wallets imported by older builds are
        // also registered automatically. Do not hide the row just because the
        // wallet is temporarily rebuilding/refreshing.
        java.util.LinkedHashSet<String> merged = new java.util.LinkedHashSet<>();
        merged.addAll(ImportedWalletStore.getAddresses(this));
        merged.addAll(WalletSelection.getImportedAddresses(wallet));
        for (String address : merged) {
            ImportedWalletStore.register(this, address);
        }
        java.util.List<String> addresses = new java.util.ArrayList<>(merged);
        empty.setVisibility(addresses.isEmpty() ? View.VISIBLE : View.GONE);
        for (int i = 0; i < addresses.size(); i++) {
            addImportedWalletRow(container, wallet, addresses.get(i), i + 1);
        }
    }

    private void addImportedWalletRow(LinearLayout container, Wallet wallet,
                                      String address, int index) {
        View row = getLayoutInflater().inflate(R.layout.item_imported_wif_wallet, container, false);
        TextView name = row.findViewById(R.id.importedWalletName);
        TextView addressView = row.findViewById(R.id.importedWalletAddress);
        TextView balance = row.findViewById(R.id.importedWalletBalance);
        Button select = row.findViewById(R.id.importedWalletSelect);
        Button manage = row.findViewById(R.id.importedWalletManage);

        name.setText(ImportedWalletStore.getName(this, address, index));
        addressView.setText(address);
        Script script = WalletSelection.findImportedScriptForAddress(wallet, address);
        Coin amount = script == null
                ? Coin.ZERO
                : WalletSelection.selectedImportedEstimatedBalance(wallet, script);
        balance.setText(getString(R.string.imported_wallet_balance, amount.toFriendlyString()));

        boolean selected = address.equals(WalletSelection.getSelectedImportedAddress(this));
        select.setText(selected ? R.string.imported_wallet_selected : R.string.imported_wallet_select);
        select.setEnabled(!selected);
        select.setOnClickListener(v -> {
            WalletSelection.selectImportedAddress(this, address);
            MainActivityPresenter presenter = MainActivityPresenter.getActivePresenter();
            if (presenter != null) presenter.refresh();
            renderImportedWallets();
        });
        manage.setOnClickListener(v -> showImportedWalletManager(address));
    }

    private void showImportedWalletManager(String address) {
        Wallet wallet = getWallet();
        if (wallet == null) return;
        int index = WalletSelection.getManagedImportedAddresses(this, wallet).indexOf(address) + 1;
        String currentName = ImportedWalletStore.getName(this, address, Math.max(1, index));

        String[] actions = new String[] {
                getString(R.string.imported_wallet_show_address),
                getString(R.string.imported_wallet_show_qr),
                getString(R.string.imported_wallet_rename),
                getString(R.string.imported_wallet_remove)
        };
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.imported_wallet_manage_title) + "\n" + currentName)
                .setItems(actions, (dialog, which) -> {
                    if (which == 0) showImportedAddress(address);
                    else if (which == 1) ReceiveQrDialog.show(this, address);
                    else if (which == 2) renameImportedWallet(address, currentName);
                    else if (which == 3) confirmRemoveImportedWallet(address, currentName);
                })
                .setNegativeButton(R.string.close, null)
                .show();
    }

    private void showImportedAddress(String address) {
        TextView content = new TextView(this);
        int pad = Math.round(20 * getResources().getDisplayMetrics().density);
        content.setPadding(pad, pad, pad, pad);
        content.setText(address);
        content.setTextIsSelectable(true);
        new AlertDialog.Builder(this)
                .setTitle(R.string.imported_wallet_address_title)
                .setView(content)
                .setPositiveButton(R.string.imported_wallet_copy_address, (d, w) -> {
                    ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    if (clipboard != null) {
                        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.imported_wallet_clipboard_label), address));
                        show(R.string.imported_wallet_address_copied);
                    }
                })
                .setNegativeButton(R.string.close, null)
                .show();
    }

    private void renameImportedWallet(String address, String currentName) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(currentName);
        input.setSelection(input.length());
        input.setHint(R.string.imported_wallet_name_hint);
        new AlertDialog.Builder(this)
                .setTitle(R.string.imported_wallet_rename_title)
                .setView(input)
                .setNegativeButton(R.string.close, null)
                .setPositiveButton(R.string.imported_wallet_rename, (d, w) -> {
                    String value = input.getText().toString().trim();
                    if (!value.isEmpty()) {
                        ImportedWalletStore.setName(this, address, value);
                        renderImportedWallets();
                        show(R.string.imported_wallet_renamed);
                    }
                })
                .show();
    }

    private void confirmRemoveImportedWallet(String address, String name) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.imported_wallet_remove_title)
                .setMessage(R.string.imported_wallet_remove_message)
                .setNegativeButton(R.string.close, null)
                .setPositiveButton(R.string.imported_wallet_remove, (d, w) -> removeImportedWallet(address))
                .show();
    }

    private void removeImportedWallet(String address) {
        Wallet wallet = getWallet();
        if (wallet == null) return;
        try {
            org.bitcoinj.crypto.ECKey key = WalletSelection.findImportedKey(wallet, address);
            if (key == null) throw new IllegalStateException(getString(R.string.imported_wallet_not_found));
            wallet.removeKey(key);
            ImportedWalletStore.remove(this, address);
            if (address.equals(WalletSelection.getSelectedImportedAddress(this))) {
                WalletSelection.selectMain(this);
            }
            MainActivityPresenter presenter = MainActivityPresenter.getActivePresenter();
            if (presenter != null) {
                presenter.saveWalletNow();
                presenter.refresh();
            }
            renderImportedWallets();
            show(R.string.imported_wallet_removed);
        } catch (Exception error) {
            Toast.makeText(this, getString(
                    R.string.imported_wallet_remove_failed,
                    error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage()),
                    Toast.LENGTH_LONG).show();
        }
    }

    private void showWifInfo() {
        TextView content = (TextView) getLayoutInflater().inflate(R.layout.dialog_text, null);
        content.setText(R.string.wif_info_message);
        new android.support.v7.app.AlertDialog.Builder(this)
                .setTitle(R.string.what_is_wif)
                .setView(content)
                .setPositiveButton(R.string.close, null)
                .show();
    }
}
