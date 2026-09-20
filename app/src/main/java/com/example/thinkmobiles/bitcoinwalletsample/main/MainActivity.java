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
import android.widget.ProgressBar;
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
    protected TextView tvRecipientAddress_AM;

    @ViewById
    protected EditText etAmount_AM;

    @ViewById
    protected Button btnSend_AM;

    @ViewById
    protected Button btnBackupWallet_AM;

    @ViewById
    protected Button btnRestoreWallet_AM;

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

        btnBackupWallet_AM.setOnClickListener(v -> {
            if (presenter != null) {
                presenter.prepareWalletBackup();
            }
        });

        btnRestoreWallet_AM.setOnClickListener(v -> {
            startWalletRestore();
        });

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
}
