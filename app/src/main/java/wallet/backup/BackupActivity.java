/** Creates a wallet backup through the Android document provider. */

package wallet.backup;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import org.bitcoinj.core.Context;
import org.bitcoinj.kits.WalletAppKit;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import wallet.Constants;
import wallet.main.MainActivityPresenter;
import wallet.main.R;

public class BackupActivity extends AppCompatActivity {

    private static final int REQUEST_CREATE_BACKUP = 8101;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_backup);

        Toolbar toolbar = findViewById(R.id.toolbar_backup);
        setSupportActionBar(toolbar);

        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(R.string.backup_title);
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        toolbar.setNavigationOnClickListener(v -> finish());

        Button createButton = findViewById(R.id.btnCreateBackup);
        createButton.setOnClickListener(v -> prepareBackup());

        TextView pathView = findViewById(R.id.tvBackupPath);
        pathView.setText(getWalletFile().getAbsolutePath());
    }

    private void prepareBackup() {
        WalletAppKit walletAppKit = MainActivityPresenter.getActiveWalletAppKit();
        org.bitcoinj.core.NetworkParameters parameters =
                MainActivityPresenter.getActiveParameters();

        if (walletAppKit == null || parameters == null) {
            showToast(getString(R.string.wallet_not_ready));
            return;
        }

        new Thread(() -> {
            Context.propagate(Context.getOrCreate(parameters));

            try {
                File walletFile = getWalletFile();
                walletAppKit.wallet().saveToFile(walletFile);
                createSafetyCopy(walletFile);

                if (!walletFile.exists() || walletFile.length() == 0) {
                    throw new java.io.IOException(getString(R.string.wallet_file_empty));
                }

                runOnUiThread(() -> openCreateDocument());
            } catch (Exception error) {
                showToast(getString(R.string.backup_failed, error.getMessage()));
            }
        }, "wallet-backup").start();
    }

    private void openCreateDocument() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/octet-stream");
        intent.putExtra(
                Intent.EXTRA_TITLE,
                getString(R.string.backup_title_file, Constants.WALLET_NAME));
        startActivityForResult(intent, REQUEST_CREATE_BACKUP);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQUEST_CREATE_BACKUP
                && resultCode == RESULT_OK
                && data != null
                && data.getData() != null) {
            copyBackup(data.getData());
        }
    }

    private void copyBackup(Uri destination) {
        new Thread(() -> {
            try (InputStream input = new FileInputStream(getWalletFile());
                 OutputStream output = getContentResolver().openOutputStream(destination)) {

                if (output == null) {
                    throw new java.io.IOException(
                            getString(R.string.backup_destination_open_failed));
                }

                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    output.write(buffer, 0, count);
                }
                output.flush();

                showToast(getString(R.string.backup_success));
            } catch (Exception error) {
                showToast(getString(R.string.backup_failed, error.getMessage()));
            }
        }, "wallet-backup-copy").start();
    }

    private void createSafetyCopy(File walletFile) throws java.io.IOException {
        File dir = new File(getFilesDir(), "backup-safety");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new java.io.IOException("Unable to create backup safety directory");
        }
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
                .format(new Date());
        File target = new File(dir, Constants.WALLET_NAME + "-" + stamp + ".wallet");
        try (InputStream input = new FileInputStream(walletFile);
             OutputStream output = new java.io.FileOutputStream(target)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
        }
        File[] files = dir.listFiles();
        if (files != null && files.length > 5) {
            java.util.Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
            for (int i = 0; i < files.length - 5; i++) {
                files[i].delete();
            }
        }
    }

    private File getWalletFile() {
        return new File(
                getFilesDir(),
                Constants.WALLET_NAME + ".wallet");
    }

    private void showToast(String message) {
        runOnUiThread(() ->
                Toast.makeText(this, message, Toast.LENGTH_LONG).show());
    }
}
