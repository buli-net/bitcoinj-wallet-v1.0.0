/**
 * Release documentation:
 * Release documentation for the QR scanning screen.
 * Starts the ZXing scanner for Bitcoin addresses and forwards a successful scan to the send screen.
 * The Activity contains no wallet ownership logic.
 */

package wallet.scan;

import wallet.main.R;

import android.content.Intent;
import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.text.TextUtils;
import android.widget.Toast;

import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;

import wallet.send.SendActivity;

public class ScanActivity extends AppCompatActivity {
    @Override protected void onCreate(Bundle state){super.onCreate(state);setContentView(R.layout.activity_scan);Toolbar t=findViewById(R.id.toolbar_scan);setSupportActionBar(t);if(getSupportActionBar()!=null){getSupportActionBar().setTitle(R.string.scan_title);getSupportActionBar().setDisplayHomeAsUpEnabled(true);}t.setNavigationOnClickListener(v->finish());new IntentIntegrator(this).setPrompt(getString(R.string.scan_bitcoin_address)).initiateScan();}
    @Override protected void onActivityResult(int r,int c,Intent d){super.onActivityResult(r,c,d);IntentResult result=IntentIntegrator.parseActivityResult(r,c,d);if(result!=null&& !TextUtils.isEmpty(result.getContents())){Intent i=new Intent(this,SendActivity.class);i.putExtra(SendActivity.EXTRA_RECIPIENT,result.getContents());startActivity(i);finish();}else if(c==RESULT_CANCELED){Toast.makeText(this,getString(R.string.scan_cancelled),Toast.LENGTH_SHORT).show();finish();}}
}
