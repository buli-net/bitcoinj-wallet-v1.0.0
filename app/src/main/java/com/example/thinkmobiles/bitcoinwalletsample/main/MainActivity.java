package com.example.thinkmobiles.bitcoinwalletsample.main;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.net.Uri;
import android.graphics.Bitmap;
import android.os.StrictMode;
import android.support.v4.widget.SwipeRefreshLayout;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.text.Editable;
import android.text.Html;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.method.LinkMovementMethod;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import com.example.thinkmobiles.bitcoinwalletsample.Constants;
import com.example.thinkmobiles.bitcoinwalletsample.R;
import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;

import net.glxn.qrgen.android.QRCode;

import org.androidannotations.annotations.AfterInject;
import org.androidannotations.annotations.AfterViews;
import org.androidannotations.annotations.EActivity;
import org.androidannotations.annotations.OptionsItem;
import org.androidannotations.annotations.OptionsMenu;
import org.androidannotations.annotations.SystemService;
import org.androidannotations.annotations.UiThread;
import org.androidannotations.annotations.ViewById;
import org.androidannotations.annotations.res.ColorRes;
import org.androidannotations.annotations.res.StringRes;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

@EActivity(R.layout.activity_main)
@OptionsMenu(R.menu.menu_main)
public class MainActivity extends AppCompatActivity
        implements MainActivityContract.MainActivityView {

    private static final String TAG = "BitcoinWalletStorage";
    private static final int REQUEST_CREATE_WALLET_BACKUP = 9001;
    private static final int REQUEST_OPEN_WALLET_BACKUP = 9002;

    private static final int HISTORY_PAGE_WINDOW = 7;

    private MainActivityContract.MainActivityPresenter presenter;
    private boolean isUpdatingAmount = false;

    @ViewById
    protected FrameLayout flDownloadContent_LDP;

    @ViewById
    protected ProgressBar pbProgress_LDP;

    @ViewById
    protected TextView tvPercentage_LDP;

    @ViewById
    protected Toolbar toolbar_AT;

    @ViewById
    protected SwipeRefreshLayout srlContent_AM;

    @ViewById
    protected TextView tvMyBalance_AM;

    @ViewById
    protected TextView tvMyAddress_AM;

    @ViewById
    protected ImageView ivMyQRAddress_AM;

    @ViewById
    protected TextView tvWalletFilePath_AM;

    @ViewById
    protected TextView tvTransactionHistory_AM;

    @ViewById
    protected Button btnHistoryPrev_AM;

    @ViewById
    protected Button btnHistoryNext_AM;

    @ViewById
    protected LinearLayout llHistoryPages_AM;

    /*
     * XML dùng EditText nên Java cũng phải dùng EditText.
     * Cho phép nhập tay và paste địa chỉ.
     */
    @ViewById
    protected EditText tvRecipientAddress_AM;

    @ViewById
    protected EditText etAmount_AM;

    @ViewById
    protected Button btnSend_AM;

    @ViewById
    protected SeekBar sbFee_AM;

    @ViewById
    protected TextView tvFeeRate_AM;

    @ViewById
    protected TextView tvFeeDetails_AM;

    @ViewById
    protected ImageView ivCopy_AM;

    @SystemService
    protected ClipboardManager clipboardManager;

    @StringRes(R.string.scan_recipient_qr)
    protected String strScanRecipientQRCode;

    @StringRes(R.string.about)
    protected String strAbout;

    @ColorRes(android.R.color.holo_green_dark)
    protected int colorGreenDark;

    @ColorRes(android.R.color.darker_gray)
    protected int colorGreyDark;

    @AfterInject
    protected void initData() {

        StrictMode.ThreadPolicy policy =
                new StrictMode.ThreadPolicy.Builder()
                        .permitAll()
                        .build();

        StrictMode.setThreadPolicy(policy);

        /*
         * Wallet KHÔNG được lưu trong cache.
         *
         * getCacheDir() có thể bị Android xoá khi hệ thống cần giải phóng
         * dung lượng. Wallet và blockchain state phải nằm trong storage
         * persistent của app.
         */
        File walletDir = getFilesDir();

        /*
         * Nếu bản cũ từng lưu wallet trong cache, chuyển nó sang storage
         * persistent trước khi WalletAppKit được khởi động.
         */
        migrateWalletFromCache();

        presenter = new MainActivityPresenter(this, walletDir);
    }

    @AfterViews
    protected void initUI() {
        initToolbar();
        setListeners();

        if (presenter != null) {
            presenter.subscribe();
        }
    }

    @OptionsItem(R.id.menuScanQR_MM)
    protected void clickMenuGetRecipientQR() {
        if (presenter != null) {
            presenter.pickRecipient();
        }
    }

    @OptionsItem(R.id.menuBackupWallet_MM)
    protected void clickMenuBackupWallet() {
        if (presenter != null) {
            presenter.prepareWalletBackup();
        }
    }

    @OptionsItem(R.id.menuRestoreWallet_MM)
    protected void clickMenuRestoreWallet() {
        startWalletRestore();
    }

    @OptionsItem(R.id.menuInfo_MM)
    protected void clickMenuInfo() {
        if (presenter != null) {
            presenter.getInfoDialog();
        }
    }

    private void initToolbar() {
        setSupportActionBar(toolbar_AT);

        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle("Wallet");
        }
    }

    @Override
    public void setPresenter(
            MainActivityContract.MainActivityPresenter presenter) {
        this.presenter = presenter;
    }

    @Override
    @UiThread
    public void displayDownloadContent(boolean isShown) {
        flDownloadContent_LDP.setVisibility(
                isShown ? View.VISIBLE : View.GONE
        );
    }

    @Override
    @UiThread
    public void displayProgress(int percent) {
        if (pbProgress_LDP.isIndeterminate()) {
            pbProgress_LDP.setIndeterminate(false);
        }

        pbProgress_LDP.setProgress(percent);
    }

    @Override
    @UiThread
    public void displayPercentage(int percent) {
        tvPercentage_LDP.setText(percent + " %");
    }

    @Override
    @UiThread
    public void displayMyBalance(String myBalance) {
        tvMyBalance_AM.setText(myBalance);
    }

    @Override
    @UiThread
    public void displayWalletPath(String walletPath) {
        tvWalletFilePath_AM.setText(walletPath);
    }

    @Override
    @UiThread
    public void displayTransactionHistory(String history) {
        if (tvTransactionHistory_AM != null) {
            tvTransactionHistory_AM.setText(history);
        }
    }

    @Override
    @UiThread
    public void displayTransactionHistoryPages(
            int currentPage,
            int pageCount) {

        if (btnHistoryPrev_AM == null
                || btnHistoryNext_AM == null
                || llHistoryPages_AM == null) {
            return;
        }

        llHistoryPages_AM.removeAllViews();

        if (pageCount <= 0) {
            btnHistoryPrev_AM.setEnabled(false);
            btnHistoryNext_AM.setEnabled(false);
            return;
        }

        btnHistoryPrev_AM.setEnabled(currentPage > 0);
        btnHistoryNext_AM.setEnabled(currentPage < pageCount - 1);

        btnHistoryPrev_AM.setOnClickListener(v -> {
            if (presenter != null) {
                presenter.selectTransactionHistoryPage(currentPage - 1);
            }
        });

        btnHistoryNext_AM.setOnClickListener(v -> {
            if (presenter != null) {
                presenter.selectTransactionHistoryPage(currentPage + 1);
            }
        });

        int[] pageIndexes = buildHistoryPageIndexes(
                currentPage,
                pageCount
        );

        for (int i = 0; i < pageIndexes.length; i++) {

            int page = pageIndexes[i];

            if (page == -1) {
                TextView dots = new TextView(this);
                dots.setText("...");
                dots.setTextSize(16);
                dots.setGravity(android.view.Gravity.CENTER);
                dots.setPadding(10, 0, 10, 0);
                llHistoryPages_AM.addView(dots);
                continue;
            }

            Button pageButton = new Button(this);
            pageButton.setText(String.valueOf(page + 1));
            pageButton.setMinWidth(48);
            pageButton.setMinHeight(48);
            pageButton.setPadding(4, 0, 4, 0);
            pageButton.setAllCaps(false);

            if (page == currentPage) {
                pageButton.setEnabled(false);
            }

            pageButton.setOnClickListener(v -> {
                if (presenter != null) {
                    presenter.selectTransactionHistoryPage(page);
                }
            });

            llHistoryPages_AM.addView(pageButton);
        }
    }

    private int[] buildHistoryPageIndexes(
            int currentPage,
            int pageCount) {

        if (pageCount <= HISTORY_PAGE_WINDOW) {
            int[] result = new int[pageCount];
            for (int i = 0; i < pageCount; i++) {
                result[i] = i;
            }
            return result;
        }

        java.util.ArrayList<Integer> pages =
                new java.util.ArrayList<>();

        pages.add(0);

        int start = Math.max(1, currentPage - 1);
        int end = Math.min(pageCount - 2, currentPage + 1);

        if (start > 1) {
            pages.add(-1);
        }

        for (int page = start; page <= end; page++) {
            pages.add(page);
        }

        if (end < pageCount - 2) {
            pages.add(-1);
        }

        pages.add(pageCount - 1);

        int[] result = new int[pages.size()];
        for (int i = 0; i < pages.size(); i++) {
            result[i] = pages.get(i);
        }

        return result;
    }

    @Override
    @UiThread
    public void displayMyAddress(String myAddress) {

        if (TextUtils.isEmpty(myAddress)) {
            return;
        }

        tvMyAddress_AM.setText(myAddress);

        /*
         * QR generation chạy background để không block UI.
         */
        new Thread(() -> {
            try {
                final Bitmap bitmapMyQR =
                        QRCode.from(myAddress).bitmap();

                runOnUiThread(() ->
                        ivMyQRAddress_AM.setImageBitmap(bitmapMyQR)
                );

            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();

        if (srlContent_AM.isRefreshing()) {
            srlContent_AM.setRefreshing(false);
        }
    }

    @Override
    @UiThread
    public void displayRecipientAddress(String recipientAddress) {

        tvRecipientAddress_AM.setText(
                TextUtils.isEmpty(recipientAddress)
                        ? strScanRecipientQRCode
                        : recipientAddress
        );

        tvRecipientAddress_AM.setTextColor(
                TextUtils.isEmpty(recipientAddress)
                        ? colorGreyDark
                        : colorGreenDark
        );
    }

    @Override
    public void showToastMessage(String message) {
        Toast.makeText(
                this,
                message,
                Toast.LENGTH_SHORT
        ).show();
    }

    @Override
    public void startWalletBackup(String suggestedFileName) {

        Intent intent =
                new Intent(Intent.ACTION_CREATE_DOCUMENT);

        intent.addCategory(
                Intent.CATEGORY_OPENABLE
        );

        intent.setType(
                "application/octet-stream"
        );

        intent.putExtra(
                Intent.EXTRA_TITLE,
                suggestedFileName
        );

        startActivityForResult(
                intent,
                REQUEST_CREATE_WALLET_BACKUP
        );
    }

    @Override
    public String getRecipient() {
        return tvRecipientAddress_AM
                .getText()
                .toString()
                .trim();
    }

    @Override
    public String getAmount() {
        return etAmount_AM
                .getText()
                .toString()
                .trim();
    }

    @Override
    public void clearAmount() {
        etAmount_AM.setText(null);
    }

    @Override
    public void startScanQR() {
        new IntentIntegrator(this).initiateScan();
    }

    @Override
    public void displayInfoDialog(String myAddress) {

        AlertDialog.Builder builder =
                new AlertDialog.Builder(this);

        builder.setTitle("About");
        builder.setMessage(Html.fromHtml(strAbout));
        builder.setCancelable(true);

        builder.setPositiveButton(
                "GOT IT",
                (dialog, which) -> dialog.dismiss()
        );

        AlertDialog alertDialog = builder.create();
        alertDialog.show();

        TextView msgTxt =
                alertDialog.findViewById(android.R.id.message);

        if (msgTxt != null) {
            msgTxt.setMovementMethod(
                    LinkMovementMethod.getInstance()
            );
        }
    }

    @Override
    protected void onActivityResult(
            int requestCode,
            int resultCode,
            Intent data) {

        super.onActivityResult(
                requestCode,
                resultCode,
                data
        );

        if (requestCode == REQUEST_CREATE_WALLET_BACKUP) {

            if (resultCode == RESULT_OK
                    && data != null
                    && data.getData() != null) {

                copyWalletBackup(
                        data.getData()
                );

            } else if (resultCode == RESULT_CANCELED) {

                Toast.makeText(
                        this,
                        "Backup đã huỷ",
                        Toast.LENGTH_SHORT
                ).show();
            }

            return;
        }

        if (requestCode == REQUEST_OPEN_WALLET_BACKUP) {

            if (resultCode == RESULT_OK
                    && data != null
                    && data.getData() != null) {

                confirmWalletRestore(data.getData());

            } else if (resultCode == RESULT_CANCELED) {

                Toast.makeText(
                        this,
                        "Restore đã huỷ",
                        Toast.LENGTH_SHORT
                ).show();
            }

            return;
        }

        IntentResult scanResult =
                IntentIntegrator.parseActivityResult(
                        requestCode,
                        resultCode,
                        data
                );

        if (scanResult != null
                && !TextUtils.isEmpty(scanResult.getContents())) {

            displayRecipientAddress(
                    scanResult.getContents()
            );
        }
    }

    private void setListeners() {

        srlContent_AM.setOnRefreshListener(() -> {
            if (presenter != null) {
                presenter.refresh();
            }
        });

        tvRecipientAddress_AM.setOnClickListener(v -> {
            if (presenter != null) {
                presenter.pickRecipient();
            }
        });

        btnSend_AM.setOnClickListener(v -> {
            if (presenter != null) {
                presenter.send();
            }
        });

        sbFee_AM.setMax(90);
        sbFee_AM.setProgress(40);
        updateFeeRateLabel();

        sbFee_AM.setOnSeekBarChangeListener(
                new SeekBar.OnSeekBarChangeListener() {
                    @Override
                    public void onProgressChanged(
                            SeekBar seekBar,
                            int progress,
                            boolean fromUser) {
                        updateFeeRateLabel();
                    }

                    @Override
                    public void onStartTrackingTouch(
                            SeekBar seekBar) {
                    }

                    @Override
                    public void onStopTrackingTouch(
                            SeekBar seekBar) {
                    }
                }
        );

        etAmount_AM.addTextChangedListener(
                new TextWatcher() {

                    @Override
                    public void beforeTextChanged(
                            CharSequence s,
                            int start,
                            int count,
                            int after) {
                    }

                    @Override
                    public void onTextChanged(
                            CharSequence s,
                            int start,
                            int before,
                            int count) {
                    }

                    @Override
                    public void afterTextChanged(
                            Editable s) {

                        if (isUpdatingAmount) {
                            return;
                        }

                        isUpdatingAmount = true;

                        if (s.toString().trim().length() == 0) {

                            etAmount_AM.setText("0.00");

                            etAmount_AM.setSelection(
                                    etAmount_AM
                                            .getText()
                                            .length()
                            );
                        }

                        isUpdatingAmount = false;
                    }
                }
        );

        ivCopy_AM.setOnClickListener(v -> {

            String address =
                    tvMyAddress_AM
                            .getText()
                            .toString()
                            .trim();

            if (TextUtils.isEmpty(address)) {

                Toast.makeText(
                        MainActivity.this,
                        "Chưa có địa chỉ ví",
                        Toast.LENGTH_SHORT
                ).show();

                return;
            }

            ClipData clip =
                    ClipData.newPlainText(
                            "My wallet address",
                            address
                    );

            clipboardManager.setPrimaryClip(clip);

            Toast.makeText(
                    MainActivity.this,
                    "Copied",
                    Toast.LENGTH_SHORT
            ).show();
        });
    }

    private void startWalletRestore() {

        Intent intent =
                new Intent(Intent.ACTION_OPEN_DOCUMENT);

        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/octet-stream");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false);

        startActivityForResult(
                intent,
                REQUEST_OPEN_WALLET_BACKUP
        );
    }

    private void confirmWalletRestore(Uri backupUri) {

        new AlertDialog.Builder(this)
                .setTitle("RESTORE WALLET")
                .setMessage(
                        "Restore sẽ thay thế wallet hiện tại bằng wallet trong file backup.\n\n"
                                + "Hãy chắc chắn bạn đã chọn đúng file backup."
                )
                .setNegativeButton(
                        "CANCEL",
                        null
                )
                .setPositiveButton(
                        "RESTORE",
                        (dialog, which) -> {
                            if (presenter != null) {
                                presenter.restoreWallet(backupUri);
                            }
                        }
                )
                .show();
    }

    private void copyWalletBackup(Uri destinationUri) {

        File source =
                new File(
                        getFilesDir(),
                        Constants.WALLET_NAME + ".wallet"
                );

        if (!source.exists() || source.length() == 0) {

            Toast.makeText(
                    this,
                    "Không tìm thấy wallet để backup",
                    Toast.LENGTH_LONG
            ).show();

            return;
        }

        new Thread(() -> {

            try (
                    FileInputStream input =
                            new FileInputStream(source);

                    OutputStream output =
                            getContentResolver()
                                    .openOutputStream(destinationUri)
            ) {

                if (output == null) {
                    throw new IOException(
                            "Cannot open backup destination"
                    );
                }

                byte[] buffer = new byte[8192];
                int count;

                while ((count = input.read(buffer)) != -1) {
                    output.write(buffer, 0, count);
                }

                output.flush();

                final long size = source.length();

                runOnUiThread(() ->
                        Toast.makeText(
                                MainActivity.this,
                                "Backup wallet thành công ("
                                        + size
                                        + " bytes)",
                                Toast.LENGTH_LONG
                        ).show()
                );

            } catch (Exception e) {

                android.util.Log.e(
                        TAG,
                        "Wallet backup copy failed",
                        e
                );

                runOnUiThread(() ->
                        Toast.makeText(
                                MainActivity.this,
                                "Backup wallet thất bại: "
                                        + e.getMessage(),
                                Toast.LENGTH_LONG
                        ).show()
                );
            }

        }, "bitcoinj-wallet-backup-copy").start();
    }

    /*
     * Chuyển wallet cũ từ cache sang storage persistent.
     *
     * Không xoá bản cache ngay sau khi copy.
     * Mục đích là tránh mất wallet nếu migration có vấn đề.
     */
    private void migrateWalletFromCache() {

        File oldDir = getCacheDir();
        File newDir = getFilesDir();

        migrateFile(
                new File(
                        oldDir,
                        Constants.WALLET_NAME + ".wallet"
                ),
                new File(
                        newDir,
                        Constants.WALLET_NAME + ".wallet"
                )
        );

        migrateFile(
                new File(
                        oldDir,
                        Constants.WALLET_NAME + ".spvchain"
                ),
                new File(
                        newDir,
                        Constants.WALLET_NAME + ".spvchain"
                )
        );
    }

    private void migrateFile(
            File source,
            File destination) {

        if (!source.exists()) {
            return;
        }

        /*
         * Nếu destination đã tồn tại thì không ghi đè.
         * Persistent wallet được ưu tiên.
         */
        if (destination.exists()) {
            return;
        }

        File parent = destination.getParentFile();

        if (parent != null && !parent.exists()) {

            if (!parent.mkdirs() && !parent.exists()) {

                android.util.Log.e(
                        TAG,
                        "Cannot create wallet directory: "
                                + parent.getAbsolutePath()
                );

                return;
            }
        }

        try (
                FileInputStream input =
                        new FileInputStream(source);

                FileOutputStream output =
                        new FileOutputStream(destination)
        ) {

            byte[] buffer = new byte[8192];

            int count;

            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }

            output.flush();

            android.util.Log.d(
                    TAG,
                    "Migrated: "
                            + source.getAbsolutePath()
                            + " -> "
                            + destination.getAbsolutePath()
            );

        } catch (IOException e) {

            android.util.Log.e(
                    TAG,
                    "Wallet migration failed: "
                            + source.getAbsolutePath(),
                    e
            );

            if (destination.exists()) {
                //noinspection ResultOfMethodCallIgnored
                destination.delete();
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        if (presenter != null) {
            presenter.unsubscribe();
        }
    }

    private int getSelectedFeeRateSatPerVkb() {
        return 1000 + (sbFee_AM.getProgress() * 100);
    }

    private void updateFeeRateLabel() {
        if (tvFeeRate_AM == null || sbFee_AM == null) {
            return;
        }

        int satPerVkb = getSelectedFeeRateSatPerVkb();
        double satPerVb = satPerVkb / 1000.0;

        tvFeeRate_AM.setText(
                "Fee rate: " + satPerVkb + " sat/vkB ("
                        + satPerVb + " sat/vB)"
        );
    }

    @Override
    public int getFeeRateSatPerVkb() {
        return getSelectedFeeRateSatPerVkb();
    }

    @Override
    public void displaySendDetails(
            String feeRate,
            String fee,
            String total,
            String remaining) {

        if (tvFeeDetails_AM == null) {
            return;
        }

        tvFeeDetails_AM.setText(
                "Selected fee rate: " + feeRate
                        + "\nActual fee: " + fee
                        + "\nTotal: " + total
                        + "\nRemaining: " + remaining
                        + "\n\nThe selected fee rate is a target. "
                        + "The final fee may be higher if bitcoinj adds a dust change amount to the fee."
        );
    }

    @Override
    public android.content.Context getActivityContext() {
        return this;
    }
}
