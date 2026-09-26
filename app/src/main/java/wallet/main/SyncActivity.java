package wallet.main;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;

/** Live blockchain and BitcoinJ synchronization monitor. */
public class SyncActivity extends AppCompatActivity {

    private final Handler handler = new Handler(Looper.getMainLooper());
    private MainActivityPresenter presenter;
    private Toolbar toolbar;
    private ProgressBar progress;
    private TextView status;
    private TextView percent;
    private TextView walletBlock;
    private TextView networkBlock;
    private TextView peers;
    private TextView network;
    private TextView engine;
    private TextView restarts;
    private TextView liveNote;
    private Button refresh;
    private Button reconnect;

    private final Runnable updater = new Runnable() {
        @Override public void run() {
            render();
            handler.postDelayed(this, 1000L);
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_sync);
        bindViews();
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(R.string.sync_title);
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        BitcoinSyncService.start(this);
        presenter = MainActivityPresenter.getActivePresenter();
        refresh.setOnClickListener(v -> render());
        reconnect.setOnClickListener(v -> {
            MainActivityPresenter active = MainActivityPresenter.getActivePresenter();
            if (active != null) {
                active.reconnectNow();
                render();
            }
        });
        handler.post(updater);
    }

    private void bindViews() {
        toolbar = findViewById(R.id.toolbar_sync);
        progress = findViewById(R.id.syncPageProgress);
        status = findViewById(R.id.syncPageStatus);
        percent = findViewById(R.id.syncPagePercent);
        walletBlock = findViewById(R.id.syncWalletBlock);
        networkBlock = findViewById(R.id.syncNetworkBlock);
        peers = findViewById(R.id.syncPeers);
        network = findViewById(R.id.syncNetwork);
        engine = findViewById(R.id.syncEngine);
        restarts = findViewById(R.id.syncRestarts);
        liveNote = findViewById(R.id.syncLiveNote);
        refresh = findViewById(R.id.syncRefreshButton);
        reconnect = findViewById(R.id.syncReconnectButton);
    }

    private void render() {
        MainActivityPresenter active = MainActivityPresenter.getActivePresenter();
        if (active == null) {
            status.setText(R.string.sync_status_starting);
            progress.setIndeterminate(true);
            percent.setText("0 %");
            return;
        }
        presenter = active;
        int pct = active.getSyncPercent();
        boolean syncing = active.isSyncing();
        int current = active.getCurrentBlock();
        int target = active.getNetworkBlock();

        progress.setIndeterminate(false);
        progress.setProgress(pct);
        percent.setText(getString(R.string.percentage_display, pct));
        walletBlock.setText(String.valueOf(current));
        networkBlock.setText(String.valueOf(target));
        peers.setText(String.valueOf(active.getConnectedPeerCount()));
        network.setText(active.getNetworkName());
        engine.setText(active.isWalletKitRunning()
                ? R.string.sync_engine_running : R.string.sync_engine_stopped);
        restarts.setText(String.valueOf(active.getAutoRestartCount()));
        status.setText(!active.isWalletReady()
                ? R.string.sync_status_starting
                : syncing ? R.string.sync_status_syncing : R.string.sync_status_live);
        liveNote.setText(R.string.sync_live_note);
        reconnect.setEnabled(active.isWalletReady());
    }

    @Override protected void onResume() {
        super.onResume();
        handler.removeCallbacks(updater);
        handler.post(updater);
    }

    @Override protected void onPause() {
        handler.removeCallbacks(updater);
        super.onPause();
    }

    @Override public boolean onSupportNavigateUp() {
        finish();
        return true;
    }
}
