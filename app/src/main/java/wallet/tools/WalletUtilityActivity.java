package wallet.tools;

import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.app.AlertDialog;
import android.support.v7.widget.Toolbar;
import android.widget.TextView;
import android.widget.Toast;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import wallet.main.MainActivityPresenter;
import wallet.main.R;

public final class WalletUtilityActivity extends AppCompatActivity {
    @Override protected void onCreate(Bundle state){super.onCreate(state);setContentView(R.layout.activity_wallet_utility);Toolbar toolbar=findViewById(R.id.toolbar_wallet_utility);setSupportActionBar(toolbar);if(getSupportActionBar()!=null){getSupportActionBar().setTitle(R.string.wallet_utilities_title);getSupportActionBar().setDisplayHomeAsUpEnabled(true);}toolbar.setNavigationOnClickListener(v->finish());findViewById(R.id.walletHealthButton).setOnClickListener(v->showWalletHealth());findViewById(R.id.walletDiagnosticsButton).setOnClickListener(v->showWalletDiagnostics()); findViewById(R.id.xpubExportButton).setOnClickListener(v->exportXpub()); findViewById(R.id.xpubImportButton).setOnClickListener(v->startActivity(new android.content.Intent(this, WatchOnlyWalletActivity.class))); findViewById(R.id.peerPingButton).setOnClickListener(v->pingPeers());}
    private void showWalletHealth() {
        MainActivityPresenter presenter = MainActivityPresenter.getActivePresenter();
        if (presenter == null) {
            Toast.makeText(this, R.string.wallet_not_ready, Toast.LENGTH_LONG).show();
            return;
        }
        TextView content = (TextView) getLayoutInflater().inflate(R.layout.dialog_text, null);
        content.setText(presenter.getWalletHealthReport());
        new android.support.v7.app.AlertDialog.Builder(this)
                .setTitle(R.string.wallet_health_title)
                .setView(content)
                .setPositiveButton(R.string.close, null)
                .show();
    }
    private void exportXpub() {
        MainActivityPresenter presenter = MainActivityPresenter.getActivePresenter();
        if (presenter == null || !presenter.isWalletReady()) { Toast.makeText(this, R.string.wallet_not_ready, Toast.LENGTH_LONG).show(); return; }
        new Thread(() -> {
            try {
                String xpub = presenter.getCurrentAccountXpub();
                runOnUiThread(() -> {
                    ClipboardManager cm=(ClipboardManager)getSystemService(Context.CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(ClipData.newPlainText("xpub", xpub));
                    new AlertDialog.Builder(this).setTitle(R.string.xpub_export_title)
                            .setMessage(getString(R.string.xpub_export_message)+"\n\n"+xpub)
                            .setPositiveButton(R.string.close,null).show();
                    Toast.makeText(this,R.string.xpub_copied,Toast.LENGTH_SHORT).show();
                });
            } catch(Exception e) { runOnUiThread(() -> Toast.makeText(this,getString(R.string.xpub_export_failed,e.getMessage()==null?e.getClass().getSimpleName():e.getMessage()),Toast.LENGTH_LONG).show()); }
        }, "xpub-export").start();
    }

    private void pingPeers() {
        MainActivityPresenter presenter=MainActivityPresenter.getActivePresenter();
        if(presenter==null){Toast.makeText(this,R.string.wallet_not_ready,Toast.LENGTH_LONG).show();return;}
        Toast.makeText(this,R.string.peer_ping_running,Toast.LENGTH_SHORT).show();
        presenter.pingConnectedPeers(result -> runOnUiThread(() -> new AlertDialog.Builder(this).setTitle(R.string.peer_ping).setMessage(result).setPositiveButton(R.string.close,null).show()));
    }

    private void showWalletDiagnostics() {
        MainActivityPresenter presenter = MainActivityPresenter.getActivePresenter();
        if (presenter == null) {
            Toast.makeText(this, R.string.wallet_not_ready, Toast.LENGTH_LONG).show();
            return;
        }
        TextView content = (TextView) getLayoutInflater().inflate(R.layout.dialog_text, null);
        content.setText(presenter.getWalletDiagnostics());
        new android.support.v7.app.AlertDialog.Builder(this)
                .setTitle(R.string.wallet_diagnostics_title)
                .setView(content)
                .setPositiveButton(R.string.close, null)
                .show();
    }
}
