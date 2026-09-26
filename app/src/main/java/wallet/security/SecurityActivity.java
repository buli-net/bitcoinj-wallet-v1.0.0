package wallet.security;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import org.bitcoinj.wallet.DeterministicSeed;
import org.bitcoinj.wallet.Wallet;
import org.bitcoinj.kits.WalletAppKit;

import wallet.main.MainActivityPresenter;
import wallet.main.R;

public class SecurityActivity extends AppCompatActivity {

    private EditText passwordInput;
    private TextView statusView;
    private Button setPasswordButton;
    private Button unlockButton;
    private Button lockButton;
    private Button removePasswordButton;
    private Button recoveryButton;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_security);

        Toolbar toolbar = findViewById(R.id.toolbar_security);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(R.string.security_title);
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        passwordInput = findViewById(R.id.etSecurityPassword);
        passwordInput.setInputType(
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        statusView = findViewById(R.id.tvSecurityStatus);
        setPasswordButton = findViewById(R.id.btnSetPassword);
        unlockButton = findViewById(R.id.btnUnlockWallet);
        lockButton = findViewById(R.id.btnLockWallet);
        removePasswordButton = findViewById(R.id.btnRemovePassword);
        recoveryButton = findViewById(R.id.btnShowRecoveryPhrase);

        setPasswordButton.setOnClickListener(v -> setPassword());
        unlockButton.setOnClickListener(v -> unlockWallet());
        lockButton.setOnClickListener(v -> lockWallet());
        removePasswordButton.setOnClickListener(v -> removePassword());
        recoveryButton.setOnClickListener(v -> showRecoveryPhrase());

        refreshStatus();
    }

    private void refreshStatus() {
        Wallet wallet = getWallet();
        if (wallet == null) {
            statusView.setText(R.string.wallet_not_ready);
            return;
        }

        boolean encrypted = WalletSecurity.isEncrypted(wallet);
        boolean unlocked = WalletSecurity.getSessionKey() != null;
        statusView.setText(
                encrypted
                        ? (unlocked
                        ? R.string.wallet_status_unlocked
                        : R.string.wallet_status_locked)
                        : R.string.wallet_status_unencrypted);

        setPasswordButton.setVisibility(encrypted ? View.GONE : View.VISIBLE);
        unlockButton.setVisibility(encrypted && !unlocked ? View.VISIBLE : View.GONE);
        lockButton.setVisibility(encrypted && unlocked ? View.VISIBLE : View.GONE);
        removePasswordButton.setVisibility(encrypted && unlocked ? View.VISIBLE : View.GONE);
        recoveryButton.setVisibility(View.VISIBLE);
    }

    private Wallet getWallet() {
        WalletAppKit kit = MainActivityPresenter.getActiveWalletAppKit();
        return kit == null ? null : kit.wallet();
    }

    private String password() {
        return passwordInput.getText().toString();
    }

    private boolean validPassword(String password) {
        if (password.length() < 8) {
            Toast.makeText(this, R.string.password_too_short, Toast.LENGTH_LONG).show();
            return false;
        }
        return true;
    }

    private void setPassword() {
        String password = password();
        if (!validPassword(password)) {
            return;
        }

        Wallet wallet = getWallet();
        if (wallet == null) {
            showMessage(R.string.wallet_not_ready);
            return;
        }

        setBusy(true);
        new Thread(() -> {
            try {
                WalletSecurity.encrypt(wallet, password);
                MainActivityPresenter presenter = MainActivityPresenter.getActivePresenter();
                if (presenter != null) {
                    presenter.saveWalletNow();
                }
                runOnUiThread(() -> {
                    passwordInput.setText("");
                    setBusy(false);
                    refreshStatus();
                    Toast.makeText(this, R.string.password_set_success, Toast.LENGTH_LONG).show();
                });
            } catch (Exception error) {
                WalletSecurity.clearSessionKey();
                runOnUiThread(() -> {
                    setBusy(false);
                    showError(error);
                });
            }
        }, "wallet-encrypt").start();
    }

    private void unlockWallet() {
        String password = password();
        if (password.isEmpty()) {
            showMessage(R.string.password_required);
            return;
        }

        Wallet wallet = getWallet();
        if (wallet == null) {
            showMessage(R.string.wallet_not_ready);
            return;
        }

        setBusy(true);
        new Thread(() -> {
            try {
                boolean unlocked = WalletSecurity.unlock(wallet, password);
                runOnUiThread(() -> {
                    setBusy(false);
                    if (!unlocked) {
                        Toast.makeText(this, R.string.wrong_password, Toast.LENGTH_LONG).show();
                        return;
                    }
                    passwordInput.setText("");
                    refreshStatus();
                    Toast.makeText(this, R.string.wallet_unlocked, Toast.LENGTH_SHORT).show();
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    setBusy(false);
                    showError(error);
                });
            }
        }, "wallet-unlock").start();
    }

    private void lockWallet() {
        WalletSecurity.clearSessionKey();
        passwordInput.setText("");
        refreshStatus();
        Toast.makeText(this, R.string.wallet_locked_success, Toast.LENGTH_SHORT).show();
    }

    private void removePassword() {
        Wallet wallet = getWallet();
        if (wallet == null) {
            showMessage(R.string.wallet_not_ready);
            return;
        }

        new AlertDialog.Builder(this)
                .setTitle(R.string.remove_password_title)
                .setMessage(R.string.remove_password_message)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.remove_password_action, (dialog, which) -> {
                    setBusy(true);
                    new Thread(() -> {
                        try {
                            WalletSecurity.decrypt(wallet);
                            MainActivityPresenter presenter = MainActivityPresenter.getActivePresenter();
                            if (presenter != null) {
                                presenter.saveWalletNow();
                            }
                            runOnUiThread(() -> {
                                setBusy(false);
                                refreshStatus();
                                Toast.makeText(
                                        this,
                                        R.string.password_removed,
                                        Toast.LENGTH_LONG).show();
                            });
                        } catch (Exception error) {
                            runOnUiThread(() -> {
                                setBusy(false);
                                showError(error);
                            });
                        }
                    }, "wallet-decrypt").start();
                })
                .show();
    }

    private void showRecoveryPhrase() {
        Wallet wallet = getWallet();
        if (wallet == null) {
            showMessage(R.string.wallet_not_ready);
            return;
        }

        setBusy(true);
        new Thread(() -> {
            try {
                if (WalletSecurity.isEncrypted(wallet)
                        && WalletSecurity.getSessionKey() == null) {
                    String password = password();
                    if (password.isEmpty() || !WalletSecurity.unlock(wallet, password)) {
                        runOnUiThread(() -> {
                            setBusy(false);
                            showMessage(R.string.unlock_before_recovery_phrase);
                        });
                        return;
                    }
                }

                DeterministicSeed seed = WalletSecurity.getDecryptedSeed(wallet);
                if (seed.getMnemonicCode() == null || seed.getMnemonicCode().isEmpty()) {
                    throw new IllegalStateException("Recovery phrase is unavailable.");
                }

                String phrase = joinWords(seed.getMnemonicCode());
                runOnUiThread(() -> {
                    setBusy(false);
                    showPhraseDialog(phrase);
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    setBusy(false);
                    showError(error);
                });
            }
        }, "wallet-recovery-phrase").start();
    }

    private String joinWords(java.util.List<String> words) {
        StringBuilder result = new StringBuilder();
        for (String word : words) {
            if (result.length() > 0) {
                result.append(' ');
            }
            result.append(word);
        }
        return result.toString();
    }

    private void showPhraseDialog(String phrase) {
        TextView phraseView = new TextView(this);
        phraseView.setText(phrase);
        phraseView.setTextIsSelectable(true);
        phraseView.setTextSize(17);
        phraseView.setPadding(24, 16, 24, 16);

        new AlertDialog.Builder(this)
                .setTitle(R.string.recovery_phrase_title)
                .setMessage(R.string.recovery_phrase_warning)
                .setView(phraseView)
                .setNeutralButton(R.string.copy_recovery_phrase, (dialog, which) -> {
                    ClipboardManager clipboard =
                            (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    clipboard.setPrimaryClip(ClipData.newPlainText(
                            getString(R.string.recovery_phrase_clip_label), phrase));
                    Toast.makeText(this, R.string.recovery_phrase_copied, Toast.LENGTH_SHORT).show();
                })
                .setPositiveButton(R.string.got_it, null)
                .show();
    }

    private void setBusy(boolean busy) {
        passwordInput.setEnabled(!busy);
        setPasswordButton.setEnabled(!busy);
        unlockButton.setEnabled(!busy);
        lockButton.setEnabled(!busy);
        removePasswordButton.setEnabled(!busy);
        recoveryButton.setEnabled(!busy);
    }

    private void showMessage(int messageId) {
        Toast.makeText(this, messageId, Toast.LENGTH_LONG).show();
    }

    private void showError(Exception error) {
        Toast.makeText(
                this,
                getString(
                        R.string.security_operation_failed,
                        error.getMessage() == null
                                ? error.getClass().getSimpleName()
                                : error.getMessage()),
                Toast.LENGTH_LONG).show();
    }
}
