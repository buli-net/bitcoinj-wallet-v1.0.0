/**
 * Release documentation:
 *
 * MainActivity is the primary user interface for the wallet.
 *
 * Responsibilities:
 * - Hosts the main wallet screen.
 * - Displays the current wallet balance.
 * - Displays the current receiving Bitcoin address.
 * - Provides address copy functionality.
 * - Displays the compact receiving QR code on the main screen.
 * - Opens a larger receiving QR dialog when the compact QR is tapped.
 * - Starts the QR scanner used by the send flow.
 * - Provides access to backup, restore, information and other wallet actions.
 * - Displays recent transaction history.
 * - Handles wallet/cache migration required by older application versions.
 * - Keeps the existing presenter lifecycle intact.
 *
 * QR behavior:
 * - The compact QR code is generated from the actual wallet receiving address.
 * - QR generation is triggered whenever the presenter supplies a new address.
 * - The large QR dialog uses the same current wallet address.
 * - The QR image is generated dynamically and is not stored as a static resource.
 * - The large QR image is centered inside its dialog container.
 *
 * Lifecycle:
 * - AndroidAnnotations generates MainActivity_ from this Activity.
 * - The presenter may survive Activity recreation and is re-attached here.
 * - The presenter is unsubscribed only when the Activity is actually finishing.
 *
 * Release safety:
 * - No wallet synchronization logic is implemented in this Activity.
 * - No transaction processing logic is modified here.
 * - No wallet key material is generated or changed by the QR UI.
 * - Existing scanner, backup, restore and cache migration behavior is preserved.
 */

package wallet.main;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
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

import net.glxn.qrgen.android.QRCode;

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

    /*
     * Presenter responsible for wallet state, synchronization and
     * transaction-related operations.
     */
    private MainActivityContract.MainActivityPresenter presenter;

    /*
     * Download/synchronization progress views.
     */
    @ViewById
    protected FrameLayout flDownloadContent_LDP;

    @ViewById
    protected ProgressBar pbProgress_LDP;

    @ViewById
    protected TextView tvPercentage_LDP;

    /*
     * Main toolbar and wallet content views.
     */
    @ViewById
    protected Toolbar toolbar_AT;

    @ViewById
    protected SwipeRefreshLayout srlContent_AM;

    @ViewById
    protected TextView tvMyBalance_AM;

    @ViewById
    protected TextView tvMyAddress_AM;

    /*
     * Address actions.
     */
    @ViewById
    protected ImageView ivCopy_AM;

    /*
     * Compact receiving QR displayed directly on the main wallet screen.
     */
    @ViewById
    protected ImageView ivReceiveQr_AM;

    /*
     * Container used to render the latest transaction entries.
     */
    @ViewById
    protected LinearLayout llRecentTransactions_AM;

    @StringRes(R.string.about_text)
    protected String strAbout;

    /**
     * Initializes the presenter.
     *
     * The existing presenter is reused when available so that Activity
     * recreation does not create a second presenter or WalletAppKit instance.
     */
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

    /**
     * Initializes the main wallet UI after AndroidAnnotations has completed
     * view binding.
     */
    @AfterViews
    protected void initUI() {
        setSupportActionBar(toolbar_AT);

        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(R.string.wallet_title);
        }

        /*
         * Opens the full transaction history screen.
         */
        findViewById(R.id.tvViewAllTransactions).setOnClickListener(v ->
                startActivity(
                        new Intent(
                                this,
                                wallet.history.SentActivity.class)));

        /*
         * Pull-to-refresh delegates directly to the existing presenter.
         */
        srlContent_AM.setOnRefreshListener(() -> {
            if (presenter != null) {
                presenter.refresh();
            }
        });

        /*
         * Copy the currently displayed receiving address.
         */
        ivCopy_AM.setOnClickListener(v -> copyAddress());

        /*
         * Open the large QR dialog when the compact main-screen QR
         * is tapped.
         */
        ivReceiveQr_AM.setOnClickListener(v -> showReceiveQr());

        /*
         * Start presenter subscriptions after the UI is ready.
         */
        if (presenter != null) {
            presenter.subscribe();
        }
    }

    /**
     * Creates the toolbar menu.
     */
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_main, menu);

        if (menu instanceof MenuBuilder) {
            ((MenuBuilder) menu).setOptionalIconsVisible(true);
        }

        tintMenuIcons(menu);
        return true;
    }

    /**
     * Applies the current theme text color to toolbar and popup menu icons.
     */
    private void tintMenuIcons(Menu menu) {
        if (!(menu instanceof MenuBuilder)) {
            return;
        }

        MenuBuilder menuBuilder = (MenuBuilder) menu;

        ColorStateList toolbarIconColor =
                resolveTextColor(toolbar_AT.getContext());

        Context popupContext =
                new ContextThemeWrapper(
                        toolbar_AT.getContext(),
                        toolbar_AT.getPopupTheme());

        ColorStateList popupIconColor =
                resolveTextColor(popupContext);

        for (MenuItem item : menuBuilder.getActionItems()) {
            tintMenuItemIcon(item, toolbarIconColor);
        }

        for (MenuItem item : menuBuilder.getNonActionItems()) {
            tintMenuItemIcon(item, popupIconColor);
        }
    }

    /**
     * Resolves the theme's primary text color.
     */
    private ColorStateList resolveTextColor(Context context) {
        android.util.TypedValue value =
                new android.util.TypedValue();

        if (!context.getTheme().resolveAttribute(
                android.R.attr.textColorPrimary,
                value,
                true)) {
            return null;
        }

        if (value.resourceId != 0) {
            return context.getResources().getColorStateList(
                    value.resourceId,
                    context.getTheme());
        }

        return ColorStateList.valueOf(value.data);
    }

    /**
     * Tints a menu item icon using the supplied theme color.
     */
    private void tintMenuItemIcon(
            MenuItem item,
            ColorStateList color) {

        if (item == null ||
                item.getIcon() == null ||
                color == null) {
            return;
        }

        Drawable icon =
                DrawableCompat
                        .wrap(item.getIcon())
                        .mutate();

        DrawableCompat.setTintList(icon, color);
        item.setIcon(icon);
    }

    /**
     * Handles toolbar menu actions.
     */
    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {

            case R.id.menuScanQR_MM:
                openScanner();
                return true;

            case R.id.menuSend_MM:
                startActivity(
                        new Intent(
                                this,
                                wallet.send.SendActivity.class));
                return true;

            case R.id.menuInfo_MM:
                startActivity(
                        new Intent(
                                this,
                                wallet.about.AboutActivity.class));
                return true;

            case R.id.menuBackupWallet_MM:
                startActivity(
                        new Intent(
                                this,
                                wallet.backup.BackupActivity.class));
                return true;

            case R.id.menuRestoreWallet_MM:
                startActivity(
                        new Intent(
                                this,
                                wallet.backup.RestoreActivity.class));
                return true;

            case R.id.menuUtilities_MM:
                showToastMessage(
                        getString(
                                R.string.utilities_unavailable));
                return true;

            case R.id.menuSettings_MM:
                showToastMessage(
                        getString(
                                R.string.settings_unavailable));
                return true;

            case R.id.menuReportIssue_MM:
                showToastMessage(
                        getString(
                                R.string.report_issue_unavailable));
                return true;

            case R.id.menuDonate_MM:
                showToastMessage(
                        getString(
                                R.string.donate_unavailable));
                return true;

            case R.id.menuLegacyAddress_MM:
                showToastMessage(
                        getString(
                                R.string.legacy_address_unavailable));
                return true;

            case R.id.menuHelp_MM:
                showAbout();
                return true;

            default:
                return super.onOptionsItemSelected(item);
        }
    }

    /**
     * Opens the existing ZXing scanner.
     */
    private void openScanner() {
        new IntentIntegrator(this)
                .setPrompt(
                        getString(
                                R.string.scan_bitcoin_address))
                .initiateScan();
    }

    /**
     * Copies the currently displayed wallet receiving address.
     */
    private void copyAddress() {
        String address =
                tvMyAddress_AM
                        .getText()
                        .toString()
                        .trim();

        if (TextUtils.isEmpty(address)) {
            showToastMessage(
                    getString(
                            R.string.wallet_address_missing));
            return;
        }

        ClipboardManager cm =
                (ClipboardManager)
                        getSystemService(
                                CLIPBOARD_SERVICE);

        cm.setPrimaryClip(
                ClipData.newPlainText(
                        getString(
                                R.string.bitcoin_address_clip_label),
                        address));

        showToastMessage(
                getString(
                        R.string.address_copied));
    }

    /**
     * Displays the large receiving QR code.
     *
     * The QR is generated from the wallet's currently displayed receiving
     * address. It is intentionally generated at runtime instead of relying
     * on a static drawable.
     *
     * The ImageView is centered using FrameLayout.LayoutParams.gravity.
     * This is important because FrameLayout itself does not provide a
     * setGravity(int) method.
     */
    private void showReceiveQr() {
        final String address =
                tvMyAddress_AM
                        .getText()
                        .toString()
                        .trim();

        if (TextUtils.isEmpty(address) ||
                address.equals(
                        getString(
                                R.string.loading))) {

            showToastMessage(
                    getString(
                            R.string.wallet_address_missing));
            return;
        }

        try {
            /*
             * Generate a high-resolution QR bitmap for the dialog.
             */
            Bitmap qr =
                    QRCode.from(address)
                            .withSize(800, 800)
                            .bitmap();

            /*
             * Display the generated QR bitmap.
             */
            ImageView image =
                    new ImageView(this);

            image.setImageBitmap(qr);
            image.setScaleType(
                    ImageView.ScaleType.FIT_CENTER);

            /*
             * Keep the QR area white so scanners can reliably detect
             * the QR code regardless of the surrounding dialog theme.
             */
            image.setBackgroundColor(
                    android.graphics.Color.WHITE);

            int padding =
                    (int) (
                            8 *
                            getResources()
                                    .getDisplayMetrics()
                                    .density);

            image.setPadding(
                    padding,
                    padding,
                    padding,
                    padding);

            int size =
                    (int) (
                            300 *
                            getResources()
                                    .getDisplayMetrics()
                                    .density);

            /*
             * FrameLayout is used as the dialog content container.
             *
             * The QR ImageView is centered through its LayoutParams.
             * Do not call container.setGravity(), because FrameLayout
             * does not expose that method.
             */
            FrameLayout container =
                    new FrameLayout(this);

            container.setPadding(
                    padding,
                    padding,
                    padding,
                    padding);

            FrameLayout.LayoutParams imageParams =
                    new FrameLayout.LayoutParams(
                            size,
                            size);

            imageParams.gravity =
                    android.view.Gravity.CENTER;

            container.addView(
                    image,
                    imageParams);

            /*
             * Display the QR in the existing AlertDialog-based UI.
             */
            new AlertDialog.Builder(this)
                    .setTitle(
                            R.string.receive_qr_title)
                    .setView(container)
                    .setPositiveButton(
                            R.string.got_it,
                            null)
                    .show();

        } catch (Exception error) {
            /*
             * Do not expose internal QR generation errors to the user.
             * The existing wallet-address error message is retained.
             */
            showToastMessage(
                    getString(
                            R.string.wallet_address_missing));
        }
    }

    /**
     * Displays the About/Help dialog.
     */
    private void showAbout() {
        AlertDialog dialog =
                new AlertDialog.Builder(this)
                        .setTitle(
                                R.string.about_title)
                        .setMessage(
                                Html.fromHtml(strAbout))
                        .setPositiveButton(
                                R.string.got_it,
                                null)
                        .create();

        dialog.show();

        TextView msg =
                dialog.findViewById(
                        android.R.id.message);

        if (msg != null) {
            msg.setMovementMethod(
                    LinkMovementMethod.getInstance());
        }
    }

    /**
     * Attaches the presenter to this Activity view.
     */
    @Override
    public void setPresenter(
            MainActivityContract.MainActivityPresenter presenter) {

        this.presenter = presenter;
    }

    /**
     * Controls synchronization/download content visibility.
     */
    @Override
    @UiThread
    public void displayDownloadContent(boolean shown) {
        if (flDownloadContent_LDP != null) {
            flDownloadContent_LDP.setVisibility(
                    shown
                            ? View.VISIBLE
                            : View.GONE);
        }
    }

    /**
     * Updates synchronization progress.
     */
    @Override
    @UiThread
    public void displayProgress(int percent) {
        if (pbProgress_LDP != null) {
            pbProgress_LDP.setIndeterminate(false);
            pbProgress_LDP.setProgress(percent);
        }
    }

    /**
     * Updates the synchronization percentage label.
     */
    @Override
    @UiThread
    public void displayPercentage(int percent) {
        if (tvPercentage_LDP != null) {
            tvPercentage_LDP.setText(
                    getString(
                            R.string.percentage_display,
                            percent));
        }
    }

    /**
     * Displays the wallet balance supplied by the presenter.
     */
    @Override
    @UiThread
    public void displayMyBalance(String balance) {
        tvMyBalance_AM.setText(balance);
    }

    /**
     * Displays the current wallet receiving address.
     *
     * Every time the presenter provides an address, the compact QR is
     * regenerated from that exact address.
     */
    @Override
    @UiThread
    public void displayMyAddress(String address) {
        if (TextUtils.isEmpty(address)) {
            return;
        }

        tvMyAddress_AM.setText(address);

        /*
         * Keep the compact QR synchronized with the actual wallet address.
         */
        updateReceiveQr(address);

        if (srlContent_AM != null &&
                srlContent_AM.isRefreshing()) {

            srlContent_AM.setRefreshing(false);
        }
    }

    /**
     * Generates the compact receiving QR asynchronously.
     *
     * QR generation is performed outside the UI thread because bitmap
     * generation can be computationally expensive.
     */
    private void updateReceiveQr(final String address) {
        new Thread(() -> {
            try {
                final Bitmap qr =
                        QRCode.from(address)
                                .withSize(300, 300)
                                .bitmap();

                runOnUiThread(() ->
                        ivReceiveQr_AM.setImageBitmap(qr));

            } catch (Exception ignored) {
                /*
                 * Keep the wallet UI operational if QR generation fails.
                 */
            }
        }, "receive-qr-generator").start();
    }

    /**
     * Displays the most recent transaction entries.
     *
     * Only the first three entries are shown on the main screen.
     * The complete transaction history remains available through the
     * existing history screen.
     */
    @Override
    @UiThread
    public void displayTransactionHistory(
            java.util.List<TransactionDisplayItem> historyItems) {

        if (llRecentTransactions_AM == null) {
            return;
        }

        llRecentTransactions_AM.removeAllViews();

        if (historyItems == null ||
                historyItems.isEmpty()) {

            TextView empty =
                    new TextView(this);

            empty.setText(
                    R.string.no_transactions_yet);

            empty.setTextSize(13);

            empty.setTextColor(
                    ThemeTextColors.primary(this));

            empty.setPadding(
                    0,
                    10,
                    0,
                    10);

            llRecentTransactions_AM.addView(empty);
            return;
        }

        int count =
                Math.min(
                        3,
                        historyItems.size());

        int contentColor =
                ThemeTextColors.primary(this);

        int dividerColor =
                ThemeTextColors.divider(this);

        for (int i = 0; i < count; i++) {

            TransactionDisplayItem item =
                    historyItems.get(i);

            LinearLayout transaction =
                    new LinearLayout(this);

            transaction.setOrientation(
                    LinearLayout.VERTICAL);

            transaction.setPadding(
                    0,
                    10,
                    0,
                    10);

            /*
             * Transaction index.
             */
            TextView index =
                    new TextView(this);

            index.setText(
                    getString(
                            R.string.transaction_index,
                            item.getIndex()));

            index.setTextSize(12);

            index.setTextColor(
                    contentColor);

            index.setTypeface(
                    null,
                    android.graphics.Typeface.NORMAL);

            transaction.addView(index);

            /*
             * Transaction type and amount.
             */
            TextView summary =
                    new TextView(this);

            android.text.SpannableString summaryText =
                    new android.text.SpannableString(
                            getString(
                                    R.string.transaction_summary,
                                    item.getTypeLabel(),
                                    item.getAmount()));

            int typeStart = 0;

            int typeEnd =
                    item.getTypeLabel().length();

            int amountStart =
                    summaryText.length()
                            - item.getAmount().length();

            int amountEnd =
                    summaryText.length();

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

            summary.setTypeface(
                    null,
                    android.graphics.Typeface.NORMAL);

            summary.setPadding(
                    0,
                    2,
                    0,
                    0);

            transaction.addView(summary);

            /*
             * Transaction date and status.
             */
            TextView dateStatus =
                    new TextView(this);

            dateStatus.setText(
                    getString(
                            R.string.transaction_date_status,
                            item.getDate(),
                            item.getStatus()));

            dateStatus.setTextSize(12);

            dateStatus.setTextColor(
                    contentColor);

            dateStatus.setTypeface(
                    null,
                    android.graphics.Typeface.NORMAL);

            dateStatus.setPadding(
                    0,
                    4,
                    0,
                    0);

            transaction.addView(dateStatus);

            /*
             * Transaction ID.
             */
            TextView txId =
                    new TextView(this);

            txId.setText(
                    getString(
                            R.string.transaction_tx_id,
                            item.getTransactionId()));

            txId.setTextSize(12);

            txId.setTextColor(
                    contentColor);

            txId.setTypeface(
                    null,
                    android.graphics.Typeface.NORMAL);

            txId.setPadding(
                    0,
                    4,
                    0,
                    0);

            transaction.addView(txId);

            llRecentTransactions_AM.addView(
                    transaction);

            /*
             * Add a divider between transaction entries.
             */
            if (i + 1 < count) {

                View line =
                        new View(this);

                line.setBackgroundColor(
                        dividerColor);

                LinearLayout.LayoutParams params =
                        new LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT,
                                1);

                llRecentTransactions_AM.addView(
                        line,
                        params);
            }
        }
    }

    /**
     * Displays a short user-facing message.
     */
    @Override
    public void showToastMessage(String message) {
        showToast(message);
    }

    /**
     * Provides the Activity context to the presenter contract.
     */
    @Override
    public android.content.Context getActivityContext() {
        return this;
    }

    /**
     * Handles results returned by the ZXing scanner.
     *
     * A successfully scanned value is passed to the existing send screen
     * as the recipient address.
     */
    @Override
    protected void onActivityResult(
            int requestCode,
            int resultCode,
            Intent data) {

        super.onActivityResult(
                requestCode,
                resultCode,
                data);

        IntentResult result =
                IntentIntegrator.parseActivityResult(
                        requestCode,
                        resultCode,
                        data);

        if (result != null &&
                !TextUtils.isEmpty(
                        result.getContents())) {

            Intent send =
                    new Intent(
                            this,
                            wallet.send.SendActivity.class);

            send.putExtra(
                    wallet.send.SendActivity.EXTRA_RECIPIENT,
                    result.getContents());

            startActivity(send);
        }
    }

    /**
     * Migrates the wallet and SPV chain from the application's cache
     * directory to the persistent files directory when required.
     *
     * This preserves wallet data created by older releases.
     */
    private void migrateWalletFromCache() {
        File oldWallet =
                new File(
                        getCacheDir(),
                        Constants.WALLET_NAME
                                + ".wallet");

        File newWallet =
                new File(
                        getFilesDir(),
                        Constants.WALLET_NAME
                                + ".wallet");

        if (newWallet.exists() ||
                !oldWallet.exists()) {
            return;
        }

        try {
            copyFile(
                    oldWallet,
                    newWallet);

            File oldChain =
                    new File(
                            getCacheDir(),
                            Constants.WALLET_NAME
                                    + ".spvchain");

            File newChain =
                    new File(
                            getFilesDir(),
                            Constants.WALLET_NAME
                                    + ".spvchain");

            if (oldChain.exists() &&
                    !newChain.exists()) {

                copyFile(
                        oldChain,
                        newChain);
            }

        } catch (Exception ignored) {
            /*
             * Preserve the existing wallet if cache migration cannot
             * complete successfully.
             */
        }
    }

    /**
     * Copies a file using buffered byte-array I/O.
     */
    private static void copyFile(
            File source,
            File destination)
            throws Exception {

        try (java.io.InputStream input =
                     new java.io.FileInputStream(source);

             java.io.OutputStream output =
                     new java.io.FileOutputStream(destination)) {

            byte[] buffer =
                    new byte[8192];

            int count;

            while ((count =
                    input.read(buffer)) != -1) {

                output.write(
                        buffer,
                        0,
                        count);
            }
        }
    }

    /**
     * Displays a short Toast message on the UI thread.
     */
    private void showToast(String message) {
        runOnUiThread(() ->
                Toast.makeText(
                        this,
                        message,
                        Toast.LENGTH_SHORT)
                        .show());
    }

    /**
     * Releases presenter subscriptions only when this Activity is actually
     * finishing.
     *
     * This preserves the existing presenter lifecycle behavior across
     * configuration changes and Activity recreation.
     */
    @Override
    protected void onDestroy() {
        super.onDestroy();

        if (isFinishing() &&
                presenter != null) {

            presenter.unsubscribe();
        }
    }
}
