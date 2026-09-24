/** Bitcoin send screen. */

package wallet.send;

import android.content.Intent;
import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.text.TextUtils;
import android.widget.Button;
import android.widget.EditText;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;

import org.bitcoinj.kits.WalletAppKit;

import wallet.main.MainActivityPresenter;
import wallet.main.R;

public class SendActivity extends AppCompatActivity implements SendPresenter.View {
    public static final String EXTRA_RECIPIENT = "recipient";
    private EditText recipient, amount;
    private SeekBar fee;
    private TextView feeLabel, estimatedFee;
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

        recipient = findViewById(R.id.recipientInput);
        amount = findViewById(R.id.amountInput);
        fee = findViewById(R.id.feeSeekBar);
        feeLabel = findViewById(R.id.feeRateText);
        estimatedFee = findViewById(R.id.estimatedFeeText);
        send = findViewById(R.id.sendButton);
        Button scan = findViewById(R.id.scanButton);
        Button max = findViewById(R.id.maxButton);

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
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        IntentResult result = IntentIntegrator.parseActivityResult(requestCode, resultCode, data);
        if (result != null && !TextUtils.isEmpty(result.getContents())) {
            recipient.setText(result.getContents());
            recipient.selectAll();
        }
    }
}
