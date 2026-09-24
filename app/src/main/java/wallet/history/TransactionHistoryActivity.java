/** Displays the wallet transaction history. */

package wallet.history;

import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.support.v7.widget.Toolbar;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import org.bitcoinj.core.Context;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.kits.WalletAppKit;

import java.util.ArrayList;
import java.util.List;

import wallet.main.MainActivityPresenter;
import wallet.model.TransactionItem;
import wallet.history.TransactionMapper;
import wallet.main.R;

public class TransactionHistoryActivity extends AppCompatActivity {

    private static final String STATE_FILTER = "transactions_filter";

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
        setContentView(R.layout.activity_transactions);

        if (state != null) {
            filter = state.getInt(STATE_FILTER, 0);
        }

        setupToolbar();
        setupList();
        setupFilters();
        loadTransactions();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        outState.putInt(STATE_FILTER, filter);
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (adapter != null) {
            loadTransactions();
        }
    }

    private void setupToolbar() {
        Toolbar toolbar = findViewById(R.id.toolbar_transactions);
        setSupportActionBar(toolbar);

        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(R.string.transactions_title);
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
        applyFilter();
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
                List<TransactionItem> loaded =
                        TransactionMapper.map(this, kit.wallet());

                runOnUiThread(() -> {
                    all.clear();
                    all.addAll(loaded);
                    applyFilter();
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    TextView empty = findViewById(R.id.tvEmptyTransactions);
                    empty.setText(getString(
                            R.string.unable_load_transactions,
                            error.getMessage()));
                    empty.setVisibility(View.VISIBLE);
                });
            }
        }, "bitcoinj-history").start();
    }

    private void applyFilter() {
        filtered.clear();

        for (TransactionItem item : all) {
            boolean include = filter == 0
                    || (filter == 1 && getString(R.string.transaction_sent).equals(item.type))
                    || (filter == 2 && getString(R.string.transaction_received).equals(item.type));

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
