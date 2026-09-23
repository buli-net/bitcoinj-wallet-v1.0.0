/**
 * Release documentation:
 * Release documentation for the QR scanning screen.
 * Starts the ZXing scanner for Bitcoin addresses and forwards a successful scan
 * to the send screen. The Activity contains no wallet ownership logic.
 */
package wallet.scan;

import android.content.Intent;
import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.text.TextUtils;
import android.widget.Toast;

import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;

import wallet.main.R;
import wallet.send.SendActivity;

public class ScanActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_scan);

        Toolbar toolbar = findViewById(R.id.toolbar_scan);
        setSupportActionBar(toolbar);

        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(R.string.scan_title);
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        toolbar.setNavigationOnClickListener(v -> finish());
        startScanner();
    }

    private void startScanner() {
        new IntentIntegrator(this)
                .setPrompt(getString(R.string.scan_bitcoin_address))
                .initiateScan();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        IntentResult result =
                IntentIntegrator.parseActivityResult(requestCode, resultCode, data);

        if (result != null && !TextUtils.isEmpty(result.getContents())) {
            Intent intent = new Intent(this, SendActivity.class);
            intent.putExtra(SendActivity.EXTRA_RECIPIENT, result.getContents());
            startActivity(intent);
            finish();
        } else if (resultCode == RESULT_CANCELED) {
            Toast.makeText(
                    this,
                    R.string.scan_cancelled,
                    Toast.LENGTH_SHORT).show();
            finish();
        }
    }
}
