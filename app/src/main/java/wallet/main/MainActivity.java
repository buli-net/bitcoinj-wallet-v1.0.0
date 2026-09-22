package wallet.main;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.StrictMode;
import android.support.v4.widget.SwipeRefreshLayout;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
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

    private static final int REQUEST_SCAN = 7101;
    private static final int REQUEST_BACKUP = 7102;
    private static final int REQUEST_RESTORE = 7103;

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

    @StringRes(R.string.about)
    protected String strAbout;

    @AfterInject
    protected void initData() {
        StrictMode.setThreadPolicy(
                new StrictMode.ThreadPolicy.Builder().permitAll().build());
        migrateWalletFromCache();
        presenter = new MainActivityPresenter(this, getFilesDir());
    }

    @AfterViews
    protected void initUI() {
        setSupportActionBar(toolbar_AT);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle("Wallet");
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
        return true;
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
                showToastMessage("Utilities chưa có tính năng riêng.");
                return true;
            case R.id.menuSettings_MM:
                showToastMessage("Settings được mở từ menu ⋮.");
                return true;
            case R.id.menuReportIssue_MM:
                showToastMessage("Report issue chưa được cấu hình.");
                return true;
            case R.id.menuDonate_MM:
                showToastMessage("Donate chưa được cấu hình.");
                return true;
            case R.id.menuLegacyAddress_MM:
                showToastMessage("Legacy address chưa được cấu hình.");
                return true;
            case R.id.menuHelp_MM:
                showAbout();
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    private void openScanner() {
        new IntentIntegrator(this).setPrompt("Scan Bitcoin address")
                .initiateScan();
    }

    private void copyAddress() {
        String address = tvMyAddress_AM.getText().toString().trim();
        if (TextUtils.isEmpty(address)) {
            showToastMessage("Chưa có địa chỉ ví");
            return;
        }
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("Bitcoin address", address));
        showToastMessage("Đã copy địa chỉ");
    }

    private void showAbout() {
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("About")
                .setMessage(Html.fromHtml(strAbout))
                .setPositiveButton("GOT IT", null)
                .create();
        dialog.show();
        TextView msg = dialog.findViewById(android.R.id.message);
        if (msg != null) msg.setMovementMethod(LinkMovementMethod.getInstance());
    }

    @Override public void setPresenter(MainActivityContract.MainActivityPresenter presenter) {
        this.presenter = presenter;
    }

    @Override @UiThread public void displayDownloadContent(boolean shown) {
        if (flDownloadContent_LDP != null)
            flDownloadContent_LDP.setVisibility(shown ? View.VISIBLE : View.GONE);
    }

    @Override @UiThread public void displayProgress(int percent) {
        if (pbProgress_LDP != null) {
            pbProgress_LDP.setIndeterminate(false);
            pbProgress_LDP.setProgress(percent);
        }
    }

    @Override @UiThread public void displayPercentage(int percent) {
        if (tvPercentage_LDP != null) tvPercentage_LDP.setText(percent + " %");
    }

    @Override @UiThread public void displayMyBalance(String balance) {
        tvMyBalance_AM.setText(balance);
    }

    @Override @UiThread public void displayMyAddress(String address) {
        if (TextUtils.isEmpty(address)) return;
        tvMyAddress_AM.setText(address);
        if (srlContent_AM != null && srlContent_AM.isRefreshing()) srlContent_AM.setRefreshing(false);
    }

    @Override @UiThread public void displayWalletPath(String path) { }

    @Override @UiThread public void displayTransactionHistory(String history) {
        if (llRecentTransactions_AM == null) return;
        llRecentTransactions_AM.removeAllViews();
        if (TextUtils.isEmpty(history) || history.startsWith("No transactions")) {
            TextView empty = new TextView(this);
            empty.setText("No transactions yet.");
            empty.setTextColor(ThemeTextColors.secondary(this));
            llRecentTransactions_AM.addView(empty);
            return;
        }
        String[] entries = history.split("\\n\\n");
        int count = Math.min(3, entries.length);
        for (int i = 0; i < count; i++) {
            TextView row = new TextView(this);
            row.setText(entries[i].trim());
            row.setTextSize(13);
            row.setTextColor(ThemeTextColors.primary(this));
            row.setPadding(0, 10, 0, 10);
            llRecentTransactions_AM.addView(row);
            if (i + 1 < count) {
                View line = new View(this);
                line.setBackgroundColor(ThemeTextColors.divider(this));
                llRecentTransactions_AM.addView(line, new LinearLayout.LayoutParams(-1, 1));
            }
        }
    }

    @Override @UiThread public void displayTransactionHistoryPages(int currentPage, int pageCount) { }
    @Override @UiThread public void displayRecipientAddress(String recipientAddress) { }
    @Override public void showToastMessage(String message) { showToast(message); }
    @Override public String getRecipient() { return ""; }
    @Override public String getAmount() { return ""; }
    @Override public void clearAmount() { }
    @Override public int getFeeRateSatPerVkb() { return 5000; }
    @Override public void displaySendDetails(String feeRate, String fee, String total, String remaining) { }
    @Override public android.content.Context getActivityContext() { return this; }
    @Override public void startScanQR() { openScanner(); }
    @Override public void displayInfoDialog(String myAddress) { showAbout(); }

    @Override public void startWalletBackup(String suggestedFileName) {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/octet-stream");
        intent.putExtra(Intent.EXTRA_TITLE, suggestedFileName);
        startActivityForResult(intent, REQUEST_BACKUP);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_BACKUP && resultCode == RESULT_OK && data != null && data.getData() != null) {
            if (presenter != null) {
                // The existing presenter prepares the source file; copy it through this Activity.
                copyWalletBackup(data.getData());
            }
            return;
        }
        if (requestCode == REQUEST_RESTORE && resultCode == RESULT_OK && data != null && data.getData() != null) {
            if (presenter != null) presenter.restoreWallet(data.getData());
            return;
        }
        IntentResult result = IntentIntegrator.parseActivityResult(requestCode, resultCode, data);
        if (result != null && !TextUtils.isEmpty(result.getContents())) {
            Intent send = new Intent(this, wallet.send.SendActivity.class);
            send.putExtra(wallet.send.SendActivity.EXTRA_RECIPIENT, result.getContents());
            startActivity(send);
        }
    }

    private void startWalletRestore() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/octet-stream");
        startActivityForResult(intent, REQUEST_RESTORE);
    }

    private void copyWalletBackup(android.net.Uri destination) {
        File source = new File(getFilesDir(), Constants.WALLET_NAME + ".wallet");
        if (!source.exists()) {
            showToastMessage("Wallet backup source không tồn tại");
            return;
        }
        new Thread(() -> {
            try (java.io.InputStream in = new java.io.FileInputStream(source);
                 java.io.OutputStream out = getContentResolver().openOutputStream(destination)) {
                if (out == null) throw new java.io.IOException("Không mở được file đích");
                byte[] buffer = new byte[8192];
                int n;
                while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
                out.flush();
                showToastMessage("Backup wallet thành công");
            } catch (Exception e) {
                showToastMessage("Backup wallet thất bại: " + e.getMessage());
            }
        }, "wallet-backup-copy").start();
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
