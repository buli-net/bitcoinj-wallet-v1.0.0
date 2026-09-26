package wallet.tools;

import android.content.Intent;
import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;

import wallet.main.R;

/** Entry page for the wallet tool groups. */
public final class WalletToolsActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_wallet_tools);

        Toolbar toolbar = findViewById(R.id.toolbar_wallet_tools);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(R.string.wallet_tools_title);
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        findViewById(R.id.walletToolImportWif).setOnClickListener(v ->
                startActivity(new Intent(this, WalletImportWifActivity.class)));
        findViewById(R.id.walletToolWatchOnly).setOnClickListener(v ->
                startActivity(new Intent(this, WatchOnlyWalletActivity.class)));
        findViewById(R.id.walletToolUtility).setOnClickListener(v ->
                startActivity(new Intent(this, WalletUtilityActivity.class)));
    }
}
