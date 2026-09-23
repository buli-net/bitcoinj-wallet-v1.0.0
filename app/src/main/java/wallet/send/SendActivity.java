/**
 * Release documentation:
 * Release documentation for the Bitcoin send screen.
 * Displays the active receive address and QR code, accepts a recipient and amount, and exposes the fee-rate selector.
 * Delegates transaction preparation and broadcast to SendPresenter.
 * All user-visible release text is resolved from Android string resources.
 */

package wallet.send;

import wallet.main.R;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.os.Bundle;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;
import net.glxn.qrgen.android.QRCode;
import org.bitcoinj.kits.WalletAppKit;

import wallet.main.MainActivityPresenter;
import wallet.ui.ThemeTextColors;

public class SendActivity extends AppCompatActivity implements SendPresenter.View {
    public static final String EXTRA_RECIPIENT = "recipient";
    private EditText recipient, amount;
    private SeekBar fee;
    private TextView feeLabel, estimatedFee, walletPath, myAddress;
    private ImageView myQr;
    private Button send;
    private SendPresenter presenter;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_send);
        Toolbar toolbar = findViewById(R.id.toolbar_send);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(R.string.send_title);
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        recipient = findViewById(R.id.etRecipient_send);
        amount = findViewById(R.id.etAmount_send);
        fee = findViewById(R.id.seekFee_send);
        feeLabel = findViewById(R.id.tvFeeRate_send);
        estimatedFee = findViewById(R.id.tvEstimatedFee_send);
        walletPath = findViewById(R.id.tvWalletPath_send);
        myAddress = findViewById(R.id.tvMyAddress_send);
        myQr = findViewById(R.id.ivMyQr_send);
        send = findViewById(R.id.btnSend_send);
        Button scan = findViewById(R.id.btnScan_send);
        Button max = findViewById(R.id.btnMax_send);

        fee.setMax(9);
        fee.setProgress(4);
        fee.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar b, int p, boolean user) {
                updateFeeLabel();
            }

            @Override
            public void onStartTrackingTouch(SeekBar b) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar b) {
            }
        });
        updateFeeLabel();
        amount.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus && "0.00".equals(amount.getText().toString())) {
                amount.selectAll();
            }
        });
        scan.setOnClickListener(v -> new IntentIntegrator(this).setPrompt(getString(R.string.scan_recipient_address)).initiateScan());
        max.setOnClickListener(v -> fillMax());
        send.setOnClickListener(v -> presenter.send());
        presenter = new SendPresenter(this);

        String passed = getIntent().getStringExtra(EXTRA_RECIPIENT);
        if (!TextUtils.isEmpty(passed)) recipient.setText(passed);
        loadWalletDisplay();
    }

    private void loadWalletDisplay() {
        WalletAppKit kit = MainActivityPresenter.getActiveWalletAppKit();
        if (kit == null) return;
        new Thread(() -> {
            try {
                String address = kit.wallet().currentReceiveAddress().toString();
                String path = new java.io.File(getFilesDir(), wallet.Constants.WALLET_NAME + ".wallet").getAbsolutePath();
                runOnUiThread(() -> {
                    myAddress.setText(address);
                    walletPath.setText(path);
                    try {
                        Bitmap qr = QRCode.from(address).bitmap();
                        myQr.setImageBitmap(qr);
                    } catch (Exception ignored) {
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() ->
                        showMessage(getString(R.string.error_wallet_read, e.getMessage())));
            }
        }, "send-wallet-display").start();
    }

    private void fillMax() {
        WalletAppKit kit = MainActivityPresenter.getActiveWalletAppKit();
        if (kit == null) return;
        new Thread(() -> {
            try {
                String max = kit.wallet().getBalance().toPlainString();
                runOnUiThread(() -> amount.setText(max));
            } catch (Exception e) {
                showMessage(getString(R.string.error_balance_read));
            }
        }).start();
    }

    private void updateFeeLabel() {
        int satVb = fee.getProgress() + 1;
        feeLabel.setText(getString(R.string.fee_rate_label, satVb, satVb * 1000));
        estimatedFee.setText(getString(R.string.estimated_fee_title) + "\n" + getString(R.string.estimated_fee_description));
    }

    @Override
    public String recipient() {
        return recipient.getText().toString();
    }
    @Override
    public String amount() {
        return amount.getText().toString();
    }
    @Override
    public int feeSatVb() {
        return fee.getProgress() + 1;
    }

    @Override
    public String getStringResource(int resId, Object... formatArgs) {
        return formatArgs == null || formatArgs.length == 0
                ? getString(resId)
                : getString(resId, formatArgs);
    }
    @Override
    public void showMessage(String message) {
        runOnUiThread(() ->
                Toast.makeText(this, message, Toast.LENGTH_LONG).show());
    }
    @Override
    public void showSending(boolean sending) {
        runOnUiThread(() -> send.setEnabled(!sending));
    }
    @Override
    public void clearForm() {
        runOnUiThread(() -> {
            amount.setText(R.string.amount_default);
            recipient.setText("");
            loadWalletDisplay();
        });
    }

    @Override
    public void showConfirmation(
            String details,
            boolean feeWarning,
            Runnable confirm) {
        runOnUiThread(() -> {
            AlertDialog dialog = new AlertDialog.Builder(this)
                    .setTitle(
                            feeWarning
                                    ? R.string.confirmation_warning_title
                                    : R.string.confirmation_title)
                    .setView(buildConfirmationContent(details))
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(
                            feeWarning
                                    ? R.string.send_anyway
                                    : R.string.send_action,
                            (d, w) -> confirm.run())
                    .create();

            dialog.setOnShowListener(ignored -> {
                if (dialog.getButton(AlertDialog.BUTTON_POSITIVE) != null) {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                            .setTextColor(ThemeTextColors.primary(this));
                }
                if (dialog.getButton(AlertDialog.BUTTON_NEGATIVE) != null) {
                    dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
                            .setTextColor(ThemeTextColors.secondary(this));
                }
            });

            dialog.show();
        });
    }

    private View buildConfirmationContent(String details) {
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(0, 8, 0, 0);

        String[] sections = details.trim().split("\\n\\s*\\n+");
        for (int index = 0; index < sections.length; index++) {
            String section = sections[index].trim();
            if (section.length() == 0) {
                continue;
            }

            TextView text = new TextView(this);
            text.setTextColor(ThemeTextColors.secondary(this));
            text.setTextSize(15);
            text.setGravity(Gravity.START);
            text.setLineSpacing(0f, 1.08f);
            text.setPadding(0, 10, 0, 10);
            text.setText(buildConfirmationSection(section));

            container.addView(
                    text,
                    new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT));

            if (index < sections.length - 1) {
                View divider = new View(this);
                divider.setBackgroundColor(ThemeTextColors.divider(this));
                LinearLayout.LayoutParams dividerParams =
                        new LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                1);
                dividerParams.setMargins(0, 0, 0, 0);
                container.addView(divider, dividerParams);
            }
        }

        return container;
    }

    private CharSequence buildConfirmationSection(String section) {
        String[] lines = section.split("\\n");
        android.text.SpannableStringBuilder result =
                new android.text.SpannableStringBuilder();

        for (int lineIndex = 0; lineIndex < lines.length; lineIndex++) {
            String line = lines[lineIndex].trim();
            if (line.length() == 0) {
                continue;
            }

            int start = result.length();
            result.append(line);

            boolean important = startsWithResource(line, R.string.details_actual_fee)
                    || startsWithResource(line, R.string.details_total)
                    || startsWithResource(line, R.string.details_fee_details)
                    || startsWithResource(line, R.string.details_full_balance);

            if (important) {
                result.setSpan(
                        new android.text.style.StyleSpan(Typeface.BOLD),
                        start,
                        result.length(),
                        android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                result.setSpan(
                        new android.text.style.ForegroundColorSpan(
                                ThemeTextColors.primary(this)),
                        start,
                        result.length(),
                        android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }

            if (lineIndex < lines.length - 1) {
                result.append("\n");
            }
        }

        return result;
    }

    private boolean startsWithResource(
            String line,
            int resourceId) {
        String prefix = getString(resourceId);
        int placeholder = prefix.indexOf("%");
        if (placeholder >= 0) {
            prefix = prefix.substring(0, placeholder);
        }
        return line.startsWith(prefix.trim());
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        IntentResult result = IntentIntegrator.parseActivityResult(requestCode, resultCode, data);
        if (result != null && !TextUtils.isEmpty(result.getContents())) {
            recipient.setText(result.getContents());
            recipient.selectAll();
        }
    }
}
