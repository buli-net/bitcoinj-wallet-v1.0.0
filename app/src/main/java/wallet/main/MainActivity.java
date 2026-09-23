/**
 * Release documentation:
 * Release documentation for the main wallet Activity.
 * Owns the primary wallet screen, toolbar actions, system-theme presentation, QR entry, and view binding.
 * The Activity can be recreated by Android without creating a second wallet presenter or WalletAppKit instance.
 */

package wallet.main;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.support.v4.graphics.drawable.DrawableCompat;
import android.support.v4.widget.SwipeRefreshLayout;
import android.support.v7.app.AlertDialog;
import android.support.v7.view.menu.MenuBuilder;
import android.support.v7.app.AppCompatActivity;
import android.view.ContextThemeWrapper;
import android.support.v7.widget.Toolbar;
import android.text.Html;
import android.text.TextUtils;
import android.text.method.LinkMovementMethod;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;
import org.androidannotations.annotations.AfterInject;
import org.androidannotations.annotations.AfterViews;
import org.androidannotations.annotations.EActivity;
import org.androidannotations.annotations.UiThread;
import org.androidannotations.annotations.ViewById;
import org.androidannotations.annotations.res.StringRes;

import java.io.File;

import wallet.Constants;
import wallet.ui.ThemeTextColors;

@EActivity(R.layout.activity_main)
public class MainActivity extends AppCompatActivity
        implements MainActivityContract.MainActivityView {

    private MainActivityContract.MainActivityPresenter presenter;

    @ViewById protected FrameLayout flDownloadContent_LDP;
    @ViewById protected ProgressBar pbProgress_LDP;
    @ViewById protected TextView tvPercentage_LDP;
    @ViewById protected Toolbar toolbar_AT;
    @ViewById protected SwipeRefreshLayout srlContent_AM;
    @ViewById protected TextView tvMyBalance_AM;
    @ViewById protected TextView tvMyAddress_AM;
    @ViewById protected ImageView ivCopy_AM;
    @ViewById protected LinearLayout llRecentTransactions_AM;

    @StringRes(R.string.about_text)
    protected String strAbout;

    @AfterInject
    protected void initData() {
        migrateWalletFromCache();

        MainActivityPresenter existingPresenter =
                MainActivityPresenter.getActivePresenter();

        if (existingPresenter != null) {
            presenter = existingPresenter;
            presenter.attachView(this);
        } else {
            presenter = new MainActivityPresenter(this, getFilesDir());
        }
    }

    @AfterViews
    protected void initUI() {
        setSupportActionBar(toolbar_AT);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(R.string.wallet_title);
        }

        findViewById(R.id.tvViewAllTransactions).setOnClickListener(v ->
                startActivity(new Intent(this, wallet.history.SentActivity.class)));

        srlContent_AM.setOnRefreshListener(() -> {
            if (presenter != null) presenter.refresh();
        });

        ivCopy_AM.setOnClickListener(v -> copyAddress());

        if (presenter != null) presenter.subscribe();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_main, menu);

        if (menu instanceof MenuBuilder) {
            ((MenuBuilder) menu).setOptionalIconsVisible(true);
        }

        tintMenuIcons(menu);
        return true;
    }

    private void tintMenuIcons(Menu menu) {
        if (!(menu instanceof MenuBuilder)) {
            return;
        }

        MenuBuilder menuBuilder = (MenuBuilder) menu;
        ColorStateList toolbarIconColor = resolveTextColor(toolbar_AT.getContext());
        Context popupContext = new ContextThemeWrapper(
                toolbar_AT.getContext(),
                toolbar_AT.getPopupTheme());
        ColorStateList popupIconColor = resolveTextColor(popupContext);

        for (MenuItem item : menuBuilder.getActionItems()) {
            tintMenuItemIcon(item, toolbarIconColor);
        }

        for (MenuItem item : menuBuilder.getNonActionItems()) {
            tintMenuItemIcon(item, popupIconColor);
        }
    }

    private ColorStateList resolveTextColor(Context context) {
        android.util.TypedValue value = new android.util.TypedValue();
        if (!context.getTheme().resolveAttribute(
                android.R.attr.textColorPrimary, value, true)) {
            return null;
        }

        if (value.resourceId != 0) {
            return context.getResources().getColorStateList(
                    value.resourceId, context.getTheme());
        }

        return ColorStateList.valueOf(value.data);
    }

    private void tintMenuItemIcon(MenuItem item, ColorStateList color) {
        if (item == null || item.getIcon() == null || color == null) {
            return;
        }

        Drawable icon = DrawableCompat.wrap(item.getIcon()).mutate();
        DrawableCompat.setTintList(icon, color);
        item.setIcon(icon);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.menuScanQR_MM:
                openScanner();
                return true;
            case R.id.menuSend_MM:
                startActivity(new Intent(this, wallet.send.SendActivity.class));
                return true;
            case R.id.menuInfo_MM:
                startActivity(new Intent(this, wallet.about.AboutActivity.class));
                return true;
            case R.id.menuBackupWallet_MM:
                startActivity(new Intent(this, wallet.backup.BackupActivity.class));
                return true;
            case R.id.menuRestoreWallet_MM:
                startActivity(new Intent(this, wallet.backup.RestoreActivity.class));
                return true;
            case R.id.menuUtilities_MM:
                showToastMessage(getString(R.string.utilities_unavailable));
                return true;
            case R.id.menuSettings_MM:
                showToastMessage(getString(R.string.settings_unavailable));
                return true;
            case R.id.menuReportIssue_MM:
                showToastMessage(getString(R.string.report_issue_unavailable));
                return true;
            case R.id.menuDonate_MM:
                showToastMessage(getString(R.string.donate_unavailable));
                return true;
            case R.id.menuLegacyAddress_MM:
                showToastMessage(getString(R.string.legacy_address_unavailable));
                return true;
            case R.id.menuHelp_MM:
                showAbout();
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    private void openScanner() {
        new IntentIntegrator(this).setPrompt(getString(R.string.scan_bitcoin_address))
                .initiateScan();
    }

    private void copyAddress() {
        String address = tvMyAddress_AM.getText().toString().trim();
        if (TextUtils.isEmpty(address)) {
            showToastMessage(getString(R.string.wallet_address_missing));
            return;
        }
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText(getString(R.string.bitcoin_address_clip_label), address));
        showToastMessage(getString(R.string.address_copied));
    }

    private void showAbout() {
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.about_title)
                .setMessage(Html.fromHtml(strAbout))
                .setPositiveButton(R.string.got_it, null)
                .create();
        dialog.show();
        TextView msg = dialog.findViewById(android.R.id.message);
        if (msg != null) msg.setMovementMethod(LinkMovementMethod.getInstance());
    }

    @Override
    public void setPresenter(MainActivityContract.MainActivityPresenter presenter) {
        this.presenter = presenter;
    }

    @Override
    @UiThread
    public void displayDownloadContent(boolean shown) {
        if (flDownloadContent_LDP != null)
            flDownloadContent_LDP.setVisibility(shown ? View.VISIBLE : View.GONE);
    }

    @Override
    @UiThread
    public void displayProgress(int percent) {
        if (pbProgress_LDP != null) {
            pbProgress_LDP.setIndeterminate(false);
            pbProgress_LDP.setProgress(percent);
        }
    }

    @Override
    @UiThread
    public void displayPercentage(int percent) {
        if (tvPercentage_LDP != null) tvPercentage_LDP.setText(getString(R.string.percentage_display, percent));
    }

    @Override
    @UiThread
    public void displayMyBalance(String balance) {
        tvMyBalance_AM.setText(balance);
    }

    @Override
    @UiThread
    public void displayMyAddress(String address) {
        if (TextUtils.isEmpty(address)) return;
        tvMyAddress_AM.setText(address);
        if (srlContent_AM != null && srlContent_AM.isRefreshing()) srlContent_AM.setRefreshing(false);
    }

    @Override
    @UiThread
    public void displayTransactionHistory(
            java.util.List<TransactionDisplayItem> historyItems) {
        if (llRecentTransactions_AM == null) return;

        llRecentTransactions_AM.removeAllViews();

        if (historyItems == null || historyItems.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText(R.string.no_transactions_yet);
            empty.setTextSize(13);
            empty.setTextColor(ThemeTextColors.primary(this));
            empty.setPadding(0, 10, 0, 10);
            llRecentTransactions_AM.addView(empty);
            return;
        }

        int count = Math.min(3, historyItems.size());
        int contentColor = ThemeTextColors.primary(this);
        int dividerColor = ThemeTextColors.divider(this);

        for (int i = 0; i < count; i++) {
            TransactionDisplayItem item = historyItems.get(i);

            LinearLayout transaction = new LinearLayout(this);
            transaction.setOrientation(LinearLayout.VERTICAL);
            transaction.setPadding(0, 10, 0, 10);

            TextView index = new TextView(this);
            index.setText(getString(R.string.transaction_index, item.getIndex()));
            index.setTextSize(12);
            index.setTextColor(contentColor);
            index.setTypeface(null, android.graphics.Typeface.NORMAL);
            transaction.addView(index);

            TextView summary = new TextView(this);
            android.text.SpannableString summaryText =
                    new android.text.SpannableString(
                            getString(
                                    R.string.transaction_summary,
                                    item.getTypeLabel(),
                                    item.getAmount()));

            int typeStart = 0;
            int typeEnd = item.getTypeLabel().length();
            int amountStart = summaryText.length() - item.getAmount().length();
            int amountEnd = summaryText.length();

            summaryText.setSpan(
                    new android.text.style.StyleSpan(
                            android.graphics.Typeface.BOLD),
                    typeStart,
                    typeEnd,
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            summaryText.setSpan(
                    new android.text.style.StyleSpan(
                            android.graphics.Typeface.BOLD),
                    amountStart,
                    amountEnd,
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

            summary.setText(summaryText);
            summary.setTextSize(13);
            summary.setTextColor(contentColor);
            summary.setTypeface(null, android.graphics.Typeface.NORMAL);
            summary.setPadding(0, 2, 0, 0);
            transaction.addView(summary);

            TextView dateStatus = new TextView(this);
            dateStatus.setText(
                    getString(
                            R.string.transaction_date_status,
                            item.getDate(),
                            item.getStatus()));
            dateStatus.setTextSize(12);
            dateStatus.setTextColor(contentColor);
            dateStatus.setTypeface(null, android.graphics.Typeface.NORMAL);
            dateStatus.setPadding(0, 4, 0, 0);
            transaction.addView(dateStatus);

            TextView txId = new TextView(this);
            txId.setText(getString(R.string.transaction_tx_id, item.getTransactionId()));
            txId.setTextSize(12);
            txId.setTextColor(contentColor);
            txId.setTypeface(null, android.graphics.Typeface.NORMAL);
            txId.setPadding(0, 4, 0, 0);
            transaction.addView(txId);

            llRecentTransactions_AM.addView(transaction);

            if (i + 1 < count) {
                View line = new View(this);
                line.setBackgroundColor(dividerColor);
                LinearLayout.LayoutParams params =
                        new LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT,
                                1);
                llRecentTransactions_AM.addView(line, params);
            }
        }
    }

    @Override
    @UiThread
    @Override
    public void showToastMessage(String message) {
        showToast(message);
    }

    @Override
    public android.content.Context getActivityContext() {
        return this;
    }
    @Override
    @Override
    @Override
    @Override
    @Override
    @Override
    @Override
    @Override
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        IntentResult result =
                IntentIntegrator.parseActivityResult(requestCode, resultCode, data);
        if (result != null && !TextUtils.isEmpty(result.getContents())) {
            Intent send = new Intent(this, wallet.send.SendActivity.class);
            send.putExtra(wallet.send.SendActivity.EXTRA_RECIPIENT, result.getContents());
            startActivity(send);
        }
    }
    private void migrateWalletFromCache() {
        File oldWallet = new File(getCacheDir(), Constants.WALLET_NAME + ".wallet");
        File newWallet = new File(getFilesDir(), Constants.WALLET_NAME + ".wallet");
        if (!newWallet.exists() && oldWallet.exists()) {
            try {
                copyFile(oldWallet, newWallet);
                File oldChain = new File(getCacheDir(), Constants.WALLET_NAME + ".spvchain");
                File newChain = new File(getFilesDir(), Constants.WALLET_NAME + ".spvchain");
                if (oldChain.exists() && !newChain.exists()) copyFile(oldChain, newChain);
            } catch (Exception ignored) { }
        }
    }

    private static void copyFile(File src, File dst) throws Exception {
        try (java.io.InputStream in = new java.io.FileInputStream(src);
             java.io.OutputStream out = new java.io.FileOutputStream(dst)) {
            byte[] b = new byte[8192]; int n;
            while ((n = in.read(b)) != -1) out.write(b, 0, n);
        }
    }

    private void showToast(String message) {
        runOnUiThread(() -> Toast.makeText(this, message, Toast.LENGTH_SHORT).show());
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        if (isFinishing() && presenter != null) presenter.unsubscribe();
    }
}
