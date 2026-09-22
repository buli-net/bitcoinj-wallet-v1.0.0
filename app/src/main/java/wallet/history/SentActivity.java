package wallet.history;

import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.support.v7.widget.Toolbar;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import org.bitcoinj.base.Coin;
import org.bitcoinj.core.Context;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.kits.WalletAppKit;
import org.bitcoinj.wallet.Wallet;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import wallet.main.MainActivityPresenter;
import wallet.main.R;

public class SentActivity extends AppCompatActivity {

    private final List<TransactionItem> all = new ArrayList<>();
    private final List<TransactionItem> filtered = new ArrayList<>();

    private TransactionAdapter adapter;
    private int filter;
    private Button tabAll;
    private Button tabSent;
    private Button tabReceived;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_sent);
        setupToolbar();
        setupList();
        setupFilters();
        loadTransactions();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (adapter != null) {
            loadTransactions();
        }
    }

    private void setupToolbar() {
        Toolbar toolbar = findViewById(R.id.toolbar_sent);
        setSupportActionBar(toolbar);

        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle("Transactions");
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        toolbar.setNavigationOnClickListener(v -> finish());
    }

    private void setupList() {
        RecyclerView list = findViewById(R.id.recyclerTransactions);
        list.setLayoutManager(new LinearLayoutManager(this));
        adapter = new TransactionAdapter(filtered);
        list.setAdapter(adapter);
    }

    private void setupFilters() {
        tabAll = findViewById(R.id.tabAll);
        tabSent = findViewById(R.id.tabSent);
        tabReceived = findViewById(R.id.tabReceived);

        tabAll.setOnClickListener(v -> selectFilter(0));
        tabSent.setOnClickListener(v -> selectFilter(1));
        tabReceived.setOnClickListener(v -> selectFilter(2));

        updateFilterAppearance();
    }

    private void selectFilter(int selectedFilter) {
        filter = selectedFilter;
        updateFilterAppearance();
    }

    private void updateFilterAppearance() {
        tabAll.setBackgroundResource(
                filter == 0 ? R.drawable.bg_tab_selected : R.drawable.bg_tab_normal);
        tabSent.setBackgroundResource(
                filter == 1 ? R.drawable.bg_tab_selected : R.drawable.bg_tab_normal);
        tabReceived.setBackgroundResource(
                filter == 2 ? R.drawable.bg_tab_selected : R.drawable.bg_tab_normal);

        tabAll.setTextColor(resolveTabTextColor(filter == 0));
        tabSent.setTextColor(resolveTabTextColor(filter == 1));
        tabReceived.setTextColor(resolveTabTextColor(filter == 2));
    }

    private int resolveTabTextColor(boolean selected) {
        if (selected) {
            return getThemeColor(android.R.attr.textColorPrimaryInverse, 0xffffffff);
        }
        return getThemeColor(android.R.attr.textColorPrimary, 0xff000000);
    }

    private int getThemeColor(int attribute, int fallback) {
        android.content.res.TypedArray values = getTheme().obtainStyledAttributes(
                new int[]{attribute});
        try {
            return values.getColor(0, fallback);
        } finally {
            values.recycle();
        }
    }

    private void loadTransactions() {
        WalletAppKit kit = MainActivityPresenter.getActiveWalletAppKit();
        NetworkParameters params = MainActivityPresenter.getActiveParameters();
        if (kit == null || params == null) {
            return;
        }

        new Thread(() -> {
            Context.propagate(Context.getOrCreate(params));
            try {
                Wallet wallet = kit.wallet();
                List<Transaction> transactions = wallet.getTransactionsByTime();
                List<TransactionItem> loaded = new ArrayList<>();
                SimpleDateFormat format = new SimpleDateFormat(
                        "yyyy-MM-dd HH:mm:ss", Locale.US);

                for (Transaction transaction : transactions) {
                    Coin received = transaction.getValueSentToMe(wallet);
                    Coin sent = transaction.getValueSentFromMe(wallet);
                    Coin net = received.subtract(sent);

                    if (net.isZero()) {
                        continue;
                    }

                    String type = net.isNegative() ? "SENT" : "RECEIVED";
                    Coin absolute = net.isNegative() ? net.negate() : net;
                    String amount = (net.isNegative() ? "-" : "+")
                            + absolute.toFriendlyString();
                    String time = transaction.updateTime().isPresent()
                            ? format.format(Date.from(transaction.updateTime().get()))
                            : "Unknown time";
                    int depth = transaction.getConfidence() == null
                            ? 0
                            : transaction.getConfidence().getDepthInBlocks();
                    String confirmationText = depth + " conf";
                    String id = transaction.getTxId().toString();
                    String shortId = id.length() > 18
                            ? id.substring(0, 8) + "..." + id.substring(id.length() - 8)
                            : id;

                    loaded.add(new TransactionItem(
                            type,
                            amount,
                            time,
                            confirmationText,
                            shortId));
                }

                runOnUiThread(() -> {
                    all.clear();
                    all.addAll(loaded);
                    applyFilter();
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    TextView empty = findViewById(R.id.tvEmptyTransactions);
                    empty.setText("Unable to load transactions: " + error.getMessage());
                    empty.setVisibility(View.VISIBLE);
                });
            }
        }, "bitcoinj-history").start();
    }

    private void applyFilter() {
        filtered.clear();

        for (TransactionItem item : all) {
            boolean include = filter == 0
                    || (filter == 1 && "SENT".equals(item.type))
                    || (filter == 2 && "RECEIVED".equals(item.type));

            if (include) {
                filtered.add(item);
            }
        }

        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }

        TextView empty = findViewById(R.id.tvEmptyTransactions);
        empty.setVisibility(filtered.isEmpty() ? View.VISIBLE : View.GONE);
    }
}
