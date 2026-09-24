package wallet.main;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.support.v4.widget.SwipeRefreshLayout;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.view.menu.MenuBuilder;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.support.v7.widget.Toolbar;
import android.text.TextUtils;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;

import java.util.List;

import wallet.Constants;
import wallet.history.RecentTransactionAdapter;
import wallet.history.TransactionHistoryActivity;
import wallet.model.TransactionItem;
import wallet.qr.ReceiveQrDialog;
import wallet.qr.QrCodeGenerator;
import wallet.storage.WalletFileMigration;

/** Main wallet screen. UI structure lives in XML; this class handles wallet events. */
public class MainActivity extends AppCompatActivity
        implements MainActivityContract.MainActivityView {

    private MainActivityContract.MainActivityPresenter presenter;

    private FrameLayout syncContainer;
    private ProgressBar syncProgress;
    private TextView syncPercentage;
    private Toolbar toolbar;
    private SwipeRefreshLayout swipeRefresh;
    private TextView balanceText;
    private TextView addressText;
    private ImageView copyAddress;
    private ImageView receiveQr;
    private RecentTransactionAdapter recentAdapter;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_main);

        bindViews();
        setupToolbar();
        setupTransactionList();
        setupActions();
        initPresenter();
    }

    private void bindViews() {
        syncContainer = findViewById(R.id.syncContainer);
        syncProgress = findViewById(R.id.syncProgress);
        syncPercentage = findViewById(R.id.syncPercentage);
        toolbar = findViewById(R.id.toolbar);
        swipeRefresh = findViewById(R.id.swipeRefresh);
        balanceText = findViewById(R.id.balanceText);
        addressText = findViewById(R.id.addressText);
        copyAddress = findViewById(R.id.copyAddress);
        receiveQr = findViewById(R.id.receiveQr);
    }

    private void setupToolbar() {
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(R.string.wallet_title);
        }
    }

    private void setupTransactionList() {
        RecyclerView list = findViewById(R.id.recentTransactions);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setNestedScrollingEnabled(false);
        list.setHasFixedSize(false);
        recentAdapter = new RecentTransactionAdapter();
        list.setAdapter(recentAdapter);
    }

    private void setupActions() {
        findViewById(R.id.viewAllTransactions).setOnClickListener(v ->
                startActivity(new Intent(this, TransactionHistoryActivity.class)));

        swipeRefresh.setOnRefreshListener(() -> {
            if (presenter != null) {
                presenter.refresh();
            }
        });

        copyAddress.setOnClickListener(v -> copyAddress());
        receiveQr.setOnClickListener(v -> showReceiveQr());
    }

    private void initPresenter() {
        WalletFileMigration.migrate(this, Constants.WALLET_NAME);

        MainActivityPresenter active = MainActivityPresenter.getActivePresenter();
        if (active != null) {
            presenter = active;
            presenter.attachView(this);
        } else {
            presenter = new MainActivityPresenter(this, getFilesDir());
        }

        presenter.subscribe();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_main, menu);

        if (menu instanceof MenuBuilder) {
            // Keep icons visible in the overflow menu. The popup theme controls their colors.
            ((MenuBuilder) menu).setOptionalIconsVisible(true);
        }
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.menuScan:
                openScanner();
                return true;
            case R.id.menuSend:
                startActivity(new Intent(this, wallet.send.SendActivity.class));
                return true;
            case R.id.menuInfo:
                startActivity(new Intent(this, wallet.about.AboutActivity.class));
                return true;
            case R.id.menuBackup:
                startActivity(new Intent(this, wallet.backup.BackupActivity.class));
                return true;
            case R.id.menuRestore:
                startActivity(new Intent(this, wallet.backup.RestoreActivity.class));
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    private void openScanner() {
        new IntentIntegrator(this)
                .setPrompt(getString(R.string.scan_bitcoin_address))
                .initiateScan();
    }

    private void copyAddress() {
        String address = addressText.getText().toString().trim();
        if (TextUtils.isEmpty(address) || address.equals(getString(R.string.loading))) {
            showToastMessage(getString(R.string.wallet_address_missing));
            return;
        }

        ClipboardManager clipboard =
                (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText(
                getString(R.string.bitcoin_address_clip_label), address));
        showToastMessage(getString(R.string.address_copied));
    }

    private void showReceiveQr() {
        String address = addressText.getText().toString().trim();
        if (TextUtils.isEmpty(address) || address.equals(getString(R.string.loading))) {
            showToastMessage(getString(R.string.wallet_address_missing));
            return;
        }

        ReceiveQrDialog.show(this, address);
    }

    @Override
    public void setPresenter(MainActivityContract.MainActivityPresenter presenter) {
        this.presenter = presenter;
    }

    @Override
    public void displayDownloadContent(boolean shown) {
        syncContainer.setVisibility(shown ? View.VISIBLE : View.GONE);
    }

    @Override
    public void displayProgress(int percent) {
        syncProgress.setIndeterminate(false);
        syncProgress.setProgress(percent);
    }

    @Override
    public void displayPercentage(int percent) {
        syncPercentage.setText(getString(R.string.percentage_display, percent));
    }

    @Override
    public void displayMyBalance(String balance) {
        balanceText.setText(balance);
    }

    @Override
    public void displayMyAddress(String address) {
        if (TextUtils.isEmpty(address)) {
            return;
        }

        addressText.setText(address);
        updateReceiveQr(address);

        if (swipeRefresh.isRefreshing()) {
            swipeRefresh.setRefreshing(false);
        }
    }

    private void updateReceiveQr(String address) {
        new Thread(() -> {
            try {
                final android.graphics.Bitmap qr = QrCodeGenerator.generate(address, 300);
                runOnUiThread(() -> receiveQr.setImageBitmap(qr));
            } catch (Exception ignored) {
                // QR display failure does not affect wallet operation.
            }
        }, "receive-qr-generator").start();
    }

    @Override
    public void displayTransactionHistory(List<TransactionItem> historyItems) {
        int count = historyItems == null ? 0 : Math.min(3, historyItems.size());
        recentAdapter.submitList(count == 0
                ? null
                : new java.util.ArrayList<>(historyItems.subList(0, count)));
        findViewById(R.id.recentEmpty).setVisibility(
                count == 0 ? View.VISIBLE : View.GONE);
    }

    @Override
    public Context getActivityContext() {
        return this;
    }

    @Override
    public void showToastMessage(String message) {
        runOnUiThread(() ->
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show());
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        IntentResult result = IntentIntegrator.parseActivityResult(
                requestCode, resultCode, data);
        if (result != null && !TextUtils.isEmpty(result.getContents())) {
            Intent send = new Intent(this, wallet.send.SendActivity.class);
            send.putExtra(wallet.send.SendActivity.EXTRA_RECIPIENT, result.getContents());
            startActivity(send);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (isFinishing() && presenter != null) {
            presenter.unsubscribe();
        }
    }
}
