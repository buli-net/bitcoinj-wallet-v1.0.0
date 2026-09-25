package wallet.send;

import android.content.Intent;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.SwitchCompat;
import android.support.v7.widget.Toolbar;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;

import org.bitcoinj.base.Coin;

import wallet.main.R;

/** Bitcoin send screen. */
public class SendActivity extends AppCompatActivity implements SendPresenter.View {

    public static final String EXTRA_RECIPIENT = "recipient";

    private static final long SAFETY_DELAY_MS = 60_000L;
    private static final long COUNTDOWN_INTERVAL_MS = 1_000L;

    private EditText recipient;
    private EditText amount;
    private SeekBar fee;
    private SwitchCompat rbfSwitch;
    private TextView feeLabel;
    private TextView feeValue;
    private TextView balanceValue;
    private TextView totalValue;
    private TextView remainingValue;
    private View processPanel;
    private TextView processStatus;
    private TextView processCountdown;
    private ProgressBar processProgress;
    private TextView processRecipient;
    private TextView processBalance;
    private TextView processAmount;
    private TextView processFee;
    private TextView processTotal;
    private TextView processRemaining;
    private TextView processRbf;
    private Button send;
    private Button cancelSend;
    private Button sendNow;

    private CountDownTimer countdownTimer;
    private SendPresenter presenter;
    private boolean updatingAmount;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_send);

        bindViews();
        setupToolbar();
        setupFeeControls();
        setupActions();

        presenter = new SendPresenter(this);

        String passed = getIntent().getStringExtra(EXTRA_RECIPIENT);
        if (!TextUtils.isEmpty(passed)) {
            recipient.setText(passed);
            recipient.selectAll();
        }

        if (state == null) {
            resetProcessPanel();
            presenter.refreshWalletSummary();
        }
    }

    private void bindViews() {
        recipient = findViewById(R.id.recipientInput);
        amount = findViewById(R.id.amountInput);
        fee = findViewById(R.id.feeSeekBar);
        rbfSwitch = findViewById(R.id.rbfSwitch);
        feeLabel = findViewById(R.id.feeRateText);
        feeValue = findViewById(R.id.estimatedFeeText);
        balanceValue = findViewById(R.id.balanceValue);
        totalValue = findViewById(R.id.totalValue);
        remainingValue = findViewById(R.id.remainingValue);
        processPanel = findViewById(R.id.sendProcessPanel);
        processStatus = findViewById(R.id.processStatus);
        processCountdown = findViewById(R.id.processCountdown);
        processProgress = findViewById(R.id.processProgress);
        processRecipient = findViewById(R.id.processRecipient);
        processBalance = findViewById(R.id.processBalance);
        processAmount = findViewById(R.id.processAmount);
        processFee = findViewById(R.id.processFee);
        processTotal = findViewById(R.id.processTotal);
        processRemaining = findViewById(R.id.processRemaining);
        processRbf = findViewById(R.id.processRbf);
        send = findViewById(R.id.sendButton);
        cancelSend = findViewById(R.id.cancelSendButton);
        sendNow = findViewById(R.id.sendNowButton);
    }

    private void setupToolbar() {
        Toolbar toolbar = findViewById(R.id.toolbar_send);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(R.string.send_title);
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        toolbar.setNavigationOnClickListener(v -> finish());
    }

    private void setupFeeControls() {
        fee.setMax(SendPresenter.MAX_FEE_SAT_VB - SendPresenter.MIN_FEE_SAT_VB);
        fee.setProgress(4);
        fee.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                updateFeeLabel();
                presenter.refreshWalletSummary();
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
            }
        });
        updateFeeLabel();
    }

    private void setupActions() {
        Button scan = findViewById(R.id.scanButton);
        Button max = findViewById(R.id.maxButton);

        amount.setOnFocusChangeListener((view, hasFocus) -> {
            if (hasFocus && getString(R.string.amount_default).equals(amount.getText().toString())) {
                amount.selectAll();
            }
        });

        scan.setOnClickListener(v -> new IntentIntegrator(this)
                .setPrompt(getString(R.string.scan_recipient_address))
                .initiateScan());

        max.setOnClickListener(v -> presenter.fillMax());
        send.setOnClickListener(v -> presenter.prepareSend());
        cancelSend.setOnClickListener(v -> cancelPendingSend());
        sendNow.setOnClickListener(v -> {
            stopCountdown();
            presenter.confirmSend();
        });

        TextWatcher formWatcher = new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence text, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence text, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable editable) {
                if (!updatingAmount) {
                    presenter.refreshWalletSummary();
                }
            }
        };
        recipient.addTextChangedListener(formWatcher);
        amount.addTextChangedListener(formWatcher);

        rbfSwitch.setOnCheckedChangeListener((button, checked) -> presenter.refreshWalletSummary());
    }

    private void updateFeeLabel() {
        int satVb = selectedFeeSatVb();
        feeLabel.setText(getString(R.string.fee_rate_label, satVb));
        feeValue.setText(R.string.estimated_fee_pending);
    }

    private int selectedFeeSatVb() {
        return fee.getProgress() + SendPresenter.MIN_FEE_SAT_VB;
    }

    private void cancelPendingSend() {
        stopCountdown();
        presenter.cancelPendingSend();
        resetProcessPanel();
        presenter.refreshWalletSummary();
    }

    private void startCountdown(long remainingMs) {
        stopCountdown();
        long duration = Math.max(0L, Math.min(SAFETY_DELAY_MS, remainingMs));
        sendNow.setEnabled(true);
        processCountdown.setText(getString(
                R.string.send_countdown,
                Math.max(0L, (duration + 999L) / 1000L)));
        processProgress.setIndeterminate(false);
        processProgress.setMax((int) SAFETY_DELAY_MS);
        processProgress.setProgress((int) duration);

        if (duration == 0L) {
            processCountdown.setText(R.string.send_ready_to_send);
            presenter.confirmSend();
            return;
        }

        countdownTimer = new CountDownTimer(duration, COUNTDOWN_INTERVAL_MS) {
            @Override
            public void onTick(long remaining) {
                processCountdown.setText(getString(
                        R.string.send_countdown,
                        Math.max(1L, (remaining + 999L) / 1000L)));
                processProgress.setProgress((int) remaining);
            }

            @Override
            public void onFinish() {
                processProgress.setProgress(0);
                processCountdown.setText(R.string.send_ready_to_send);
                presenter.confirmSend();
            }
        }.start();
    }

    private void stopCountdown() {
        if (countdownTimer != null) {
            countdownTimer.cancel();
            countdownTimer = null;
        }
    }

    private void resetProcessPanel() {
        stopCountdown();
        setFormEnabled(true);
        processPanel.setVisibility(View.GONE);
        send.setVisibility(View.VISIBLE);
        send.setEnabled(true);
        send.setText(R.string.send_button);
        cancelSend.setVisibility(View.GONE);
        cancelSend.setEnabled(false);
        sendNow.setVisibility(View.GONE);
        sendNow.setEnabled(false);
        processStatus.setText(R.string.send_process_preparing);
        processCountdown.setText("");
        processProgress.setIndeterminate(false);
        processProgress.setProgress(0);
    }

    private void setFormEnabled(boolean enabled) {
        recipient.setEnabled(enabled);
        amount.setEnabled(enabled);
        fee.setEnabled(enabled);
        rbfSwitch.setEnabled(enabled);
        findViewById(R.id.scanButton).setEnabled(enabled);
        findViewById(R.id.maxButton).setEnabled(enabled);
    }

    private void showProcessPanel() {
        processPanel.setVisibility(View.VISIBLE);
        send.setVisibility(View.GONE);
        cancelSend.setVisibility(View.VISIBLE);
        sendNow.setVisibility(View.VISIBLE);
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
        return selectedFeeSatVb();
    }

    @Override
    public boolean replaceByFee() {
        return rbfSwitch.isChecked();
    }

    @Override
    public String getStringResource(int resId, Object... formatArgs) {
        return formatArgs == null || formatArgs.length == 0
                ? getString(resId)
                : getString(resId, formatArgs);
    }

    @Override
    public void showPreparing(boolean preparing) {
        runOnUiThread(() -> {
            if (preparing) {
                showProcessPanel();
                setFormEnabled(false);
                processStatus.setText(R.string.send_process_preparing);
                processCountdown.setText(R.string.send_process_calculating);
                processProgress.setIndeterminate(true);
                sendNow.setVisibility(View.VISIBLE);
                cancelSend.setVisibility(View.VISIBLE);
                sendNow.setEnabled(false);
                cancelSend.setEnabled(true);
                return;
            }

            sendNow.setEnabled(false);
        });
    }

    @Override
    public void showReview(SendTransactionPreview preview, long remainingMs) {
        runOnUiThread(() -> {
            showProcessPanel();
            setFormEnabled(false);
            processStatus.setText(R.string.send_process_waiting);
            processProgress.setIndeterminate(false);
            processRecipient.setText(preview.recipient());
            processBalance.setText(preview.balance().toFriendlyString());
            processAmount.setText(preview.amount().toFriendlyString());
            processFee.setText(preview.fee().toFriendlyString());
            processTotal.setText(preview.totalDebit().toFriendlyString());
            processRemaining.setText(preview.remainingBalance().toFriendlyString());
            processRbf.setText(preview.replaceByFee()
                    ? R.string.rbf_enabled
                    : R.string.rbf_disabled);

            feeValue.setText(getString(
                    R.string.actual_fee_value,
                    preview.fee().toFriendlyString()));
            balanceValue.setText(preview.balance().toFriendlyString());
            totalValue.setText(preview.totalDebit().toFriendlyString());
            remainingValue.setText(preview.remainingBalance().toFriendlyString());
            startCountdown(remainingMs);
        });
    }

    @Override
    public void showSending(boolean sending) {
        runOnUiThread(() -> {
            stopCountdown();
            if (sending) {
                processStatus.setText(R.string.broadcasting_transaction);
                processCountdown.setText(R.string.send_process_broadcasting);
                processProgress.setIndeterminate(true);
                cancelSend.setVisibility(View.GONE);
                sendNow.setVisibility(View.GONE);
                send.setVisibility(View.GONE);
                processPanel.setVisibility(View.VISIBLE);
                return;
            }

            resetProcessPanel();
            amount.setText(R.string.amount_default);
            recipient.setText("");
            rbfSwitch.setChecked(true);
            feeValue.setText(R.string.estimated_fee_pending);
        });
    }

    @Override
    public void showWalletBalance(Coin balance) {
        runOnUiThread(() -> {
            balanceValue.setText(balance.toFriendlyString());
            totalValue.setText(R.string.send_calculated_on_send);
            remainingValue.setText(R.string.send_calculated_on_send);
        });
    }

    @Override
    public void showSummaryPending(Coin balance) {
        runOnUiThread(() -> {
            if (balance == null) {
                balanceValue.setText(R.string.loading);
            } else {
                balanceValue.setText(balance.toFriendlyString());
            }
            totalValue.setText(R.string.send_calculated_on_send);
            remainingValue.setText(R.string.send_calculated_on_send);
        });
    }

    @Override
    public void showMaxAmount(Coin maxAmount) {
        runOnUiThread(() -> {
            updatingAmount = true;
            amount.setText(maxAmount.toPlainString());
            amount.selectAll();
            updatingAmount = false;
            presenter.refreshWalletSummary();
        });
    }

    @Override
    public void showMessage(String message) {
        runOnUiThread(() -> Toast.makeText(this, message, Toast.LENGTH_LONG).show());
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

    @Override
    protected void onDestroy() {
        stopCountdown();
        if (presenter != null) {
            presenter.onViewDestroyed(this, isChangingConfigurations());
        }
        super.onDestroy();
    }
}
