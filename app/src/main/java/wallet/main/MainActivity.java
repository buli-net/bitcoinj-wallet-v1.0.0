package wallet.main;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.support.v4.widget.SwipeRefreshLayout;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.support.v7.widget.Toolbar;
import android.support.v7.view.menu.MenuBuilder;
import android.text.TextUtils;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;

import java.util.ArrayList;
import java.util.List;

import wallet.Constants;
import wallet.model.TransactionItem;
import wallet.qr.QrCodeGenerator;
import wallet.qr.ReceiveQrDialog;
import wallet.transaction.TransactionAdapter;
import wallet.storage.WalletFileMigration;

/** Main wallet screen. UI structure lives in XML; this class handles wallet events. */
public class MainActivity extends AppCompatActivity
        implements MainActivityContract.MainActivityView {

    private static final String STATE_TRANSACTION_FILTER = "transaction_filter";
    private static final int FILTER_ALL = 0;
    private static final int FILTER_SENT = 1;
    private static final int FILTER_RECEIVED = 2;

    private MainActivityContract.MainActivityPresenter presenter;

    private FrameLayout syncContainer;
    private ProgressBar syncProgress;
    private TextView syncPercentage;
    private Toolbar toolbar;
    private SwipeRefreshLayout swipeRefresh;
    private TextView balanceText;
    private TextView availableBalanceText;
    private TextView pendingBalanceText;
    private TextView addressText;
    private ImageView copyAddress;
    private ImageView receiveQr;

    private final List<TransactionItem> allTransactions = new ArrayList<>();
    private final List<TransactionItem> filteredTransactions = new ArrayList<>();

    private TransactionAdapter transactionAdapter;
    private TextView transactionsEmpty;
    private RadioGroup transactionFilters;
    private int transactionFilter = FILTER_ALL;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_main);

        if (state != null) {
            transactionFilter = state.getInt(STATE_TRANSACTION_FILTER, FILTER_ALL);
        }

        bindViews();
        setupToolbar();
        setupTransactionList();
        setupTransactionFilters();
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
        availableBalanceText = findViewById(R.id.availableBalanceText);
        pendingBalanceText = findViewById(R.id.pendingBalanceText);
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
        RecyclerView list = findViewById(R.id.transactionsList);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setNestedScrollingEnabled(false);
        transactionAdapter = new TransactionAdapter(filteredTransactions);
        list.setAdapter(transactionAdapter);
        transactionsEmpty = findViewById(R.id.transactionsEmpty);
    }

    private void setupTransactionFilters() {
        transactionFilters = findViewById(R.id.transactionFilters);
        transactionFilters.setOnCheckedChangeListener((group, checkedId) -> {
            if (checkedId == R.id.filterSent) {
                selectTransactionFilter(FILTER_SENT);
            } else if (checkedId == R.id.filterReceived) {
                selectTransactionFilter(FILTER_RECEIVED);
            } else {
                selectTransactionFilter(FILTER_ALL);
            }
        });
        updateTransactionFilter();
    }

    private void selectTransactionFilter(int selectedFilter) {
        transactionFilter = selectedFilter;
        applyTransactionFilter();
    }

    private void updateTransactionFilter() {
        int checkedId;
        if (transactionFilter == FILTER_SENT) {
            checkedId = R.id.filterSent;
        } else if (transactionFilter == FILTER_RECEIVED) {
            checkedId = R.id.filterReceived;
        } else {
            checkedId = R.id.filterAll;
        }
        transactionFilters.check(checkedId);
    }

    private void applyTransactionFilter() {
        filteredTransactions.clear();
        String sent = getString(R.string.transaction_sent);
        String received = getString(R.string.transaction_received);

        for (TransactionItem item : allTransactions) {
            boolean include = transactionFilter == FILTER_ALL
                    || (transactionFilter == FILTER_SENT && sent.equals(item.type))
                    || (transactionFilter == FILTER_RECEIVED && received.equals(item.type));
            if (include) {
                filteredTransactions.add(item);
            }
        }

        transactionAdapter.notifyDataSetChanged();
        transactionsEmpty.setVisibility(
                filteredTransactions.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void setupActions() {

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
            case R.id.menuSecurity:
                startActivity(new Intent(this, wallet.security.SecurityActivity.class));
                return true;
            case R.id.menuWalletTools:
                startActivity(new Intent(this, wallet.tools.WalletToolsActivity.class));
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
    protected void onSaveInstanceState(Bundle outState) {
        outState.putInt(STATE_TRANSACTION_FILTER, transactionFilter);
        super.onSaveInstanceState(outState);
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
    public void displayBalanceState(String available, String pending) {
        availableBalanceText.setText(available);
        pendingBalanceText.setText(pending);
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
    public void displayTransactions(List<TransactionItem> transactions) {
        allTransactions.clear();
        if (transactions != null) {
            allTransactions.addAll(transactions);
        }
        applyTransactionFilter();
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
