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
import android.os.Bundle;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;
import net.glxn.qrgen.android.QRCode;
import org.bitcoinj.kits.WalletAppKit;

import wallet.main.MainActivityPresenter;

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
            java.util.List<ConfirmationDisplayItem> items,
            boolean feeWarning,
            Runnable confirm) {
        runOnUiThread(() -> {
            View content = getLayoutInflater().inflate(
                    R.layout.dialog_confirm_send, null, false);

            ImageView headerIcon = content.findViewById(R.id.confirmation_header_icon);
            headerIcon.setImageResource(
                    feeWarning
                            ? R.drawable.ic_dialog_warning_24dp
                            : R.drawable.ic_menu_send_24dp);

            LinearLayout confirmationContent = content.findViewById(R.id.confirmation_content);
            for (ConfirmationDisplayItem item : items) {
                addConfirmationItem(confirmationContent, item);
            }

            AlertDialog dialog = new AlertDialog.Builder(this)
                    .setView(content)
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(
                            feeWarning ? R.string.send_anyway : R.string.send_action,
                            (d, w) -> confirm.run())
                    .create();

            dialog.setOnShowListener(ignored -> styleConfirmationButtons(dialog));
            dialog.show();
            styleConfirmationButtons(dialog);

            if (dialog.getWindow() != null) {
                int width = (int) (getResources().getDisplayMetrics().widthPixels * 0.90f);
                dialog.getWindow().setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
            }
        });
    }

    private void addConfirmationItem(
            LinearLayout container,
            ConfirmationDisplayItem item) {
        if (item.hasDividerBefore() && container.getChildCount() > 0) {
            View divider = getLayoutInflater().inflate(
                    R.layout.dialog_confirmation_divider, container, false);
            container.addView(divider);
        }

        if (item.isWarning()) {
            addWarningItem(container, item);
            return;
        }

        if (item.isSectionHeader()) {
            addSectionHeader(container, item);
            return;
        }

        if (!TextUtils.isEmpty(item.getSectionTitle())) {
            addSectionHeader(container, item.getSectionTitle(), item.getIconResId());
            addDetailRow(container, item, false);
            return;
        }

        addDetailRow(container, item, true);
    }

    private void addSectionHeader(
            LinearLayout container,
            ConfirmationDisplayItem item) {
        addSectionHeader(container, item.getLabel(), item.getIconResId());
    }

    private void addSectionHeader(
            LinearLayout container,
            String title,
            int iconResId) {
        View view = getLayoutInflater().inflate(
                R.layout.dialog_confirmation_section, container, false);
        ImageView icon = view.findViewById(R.id.confirmation_section_icon);
        TextView titleView = view.findViewById(R.id.confirmation_section_title);
        icon.setImageResource(iconResId);
        titleView.setText(title);
        container.addView(view);
    }

    private void addDetailRow(
            LinearLayout container,
            ConfirmationDisplayItem item,
            boolean showIcon) {
        View view = getLayoutInflater().inflate(
                R.layout.dialog_confirmation_row, container, false);
        ImageView icon = view.findViewById(R.id.confirmation_row_icon);
        TextView label = view.findViewById(R.id.confirmation_row_label);
        TextView value = view.findViewById(R.id.confirmation_row_value);

        if (showIcon && item.getIconResId() != 0) {
            icon.setImageResource(item.getIconResId());
        } else {
            icon.setVisibility(View.INVISIBLE);
        }

        label.setText(item.getLabel());
        value.setText(item.getValue());

        if (item.isImportant()) {
            value.setTypeface(value.getTypeface(), android.graphics.Typeface.BOLD);
            value.setTextSize(15);
            value.setTextColor(resolveThemeColor(android.R.attr.textColorPrimary));
        }

        container.addView(view);
    }

    private void addWarningItem(
            LinearLayout container,
            ConfirmationDisplayItem item) {
        View view = getLayoutInflater().inflate(
                R.layout.dialog_confirmation_warning, container, false);
        ImageView icon = view.findViewById(R.id.confirmation_warning_icon);
        TextView title = view.findViewById(R.id.confirmation_warning_title);
        TextView message = view.findViewById(R.id.confirmation_warning_message);

        icon.setImageResource(item.getIconResId());
        title.setText(
                TextUtils.isEmpty(item.getSectionTitle())
                        ? item.getLabel()
                        : item.getSectionTitle());
        message.setText(item.getValue());
        container.addView(view);
    }

    private void styleConfirmationButtons(AlertDialog dialog) {
        Button positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        if (positive != null) {
            positive.setTextSize(14);
            positive.setTypeface(positive.getTypeface(), android.graphics.Typeface.BOLD);
            positive.setMinHeight(dp(44));
            positive.setPadding(dp(18), 0, dp(18), 0);
            positive.setBackgroundResource(R.drawable.bg_dialog_button_primary);
            positive.setTextColor(resolveThemeColor(android.R.attr.textColorPrimaryInverse));
        }

        Button negative = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
        if (negative != null) {
            negative.setTextSize(14);
            negative.setTypeface(negative.getTypeface(), android.graphics.Typeface.BOLD);
            negative.setMinHeight(dp(44));
            negative.setPadding(dp(18), 0, dp(18), 0);
            negative.setBackgroundResource(R.drawable.bg_dialog_button_outline);
            negative.setTextColor(resolveThemeColor(android.R.attr.colorAccent));
        }
    }

    private int resolveThemeColor(int attribute) {
        android.content.res.TypedArray values = obtainStyledAttributes(new int[]{attribute});
        try {
            return values.getColor(0, 0);
        } finally {
            values.recycle();
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
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
