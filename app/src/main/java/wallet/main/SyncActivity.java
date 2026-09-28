package wallet.main;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.support.v4.content.FileProvider;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.widget.Button;
import android.widget.Toast;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

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
    private TextView bestChain;
    private TextView blocksBehind;
    private TextView chainHash;
    private TextView lastBlockTime;
    private TextView syncRate;
    private TextView syncTargetInline;
    private TextView syncBlockAge;
    private TextView pendingPeers;
    private TextView peers;
    private TextView network;
    private TextView engine;
    private TextView restarts;
    private TextView liveNote;
    private TextView peerList;
    private TextView networkCapabilities;
    private TextView chainTechnical;
    private TextView walletChainState;
    private Button refresh;
    private Button reconnect;
    private Button exportDebugLog;

    private final Runnable updater = new Runnable() {
        @Override public void run() {
            DebugLog.syncState(SyncActivity.this, MainActivityPresenter.getActivePresenter(), "SyncActivity.periodic");
            render();
            handler.postDelayed(this, 1000L);
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        DebugLog.installCrashHandler(getApplicationContext());
        DebugLog.activityEvent(this, "SyncActivity.onCreate");
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
        exportDebugLog.setOnClickListener(v -> exportDebugLog());
        handler.post(updater);
    }

    private void bindViews() {
        toolbar = findViewById(R.id.toolbar_sync);
        progress = findViewById(R.id.syncPageProgress);
        status = findViewById(R.id.syncPageStatus);
        percent = findViewById(R.id.syncPagePercent);
        walletBlock = findViewById(R.id.syncWalletBlock);
        networkBlock = findViewById(R.id.syncNetworkBlock);
        bestChain = findViewById(R.id.syncBestChain);
        blocksBehind = findViewById(R.id.syncBlocksBehind);
        chainHash = findViewById(R.id.syncChainHash);
        lastBlockTime = findViewById(R.id.syncLastBlockTime);
        syncRate = findViewById(R.id.syncRate);
        syncTargetInline = findViewById(R.id.syncTargetInline);
        syncBlockAge = findViewById(R.id.syncBlockAge);
        pendingPeers = findViewById(R.id.syncPendingPeers);
        peers = findViewById(R.id.syncPeers);
        network = findViewById(R.id.syncNetwork);
        engine = findViewById(R.id.syncEngine);
        restarts = findViewById(R.id.syncRestarts);
        liveNote = findViewById(R.id.syncLiveNote);
        peerList = findViewById(R.id.syncPeerList);
        networkCapabilities = findViewById(R.id.syncNetworkCapabilities);
        chainTechnical = findViewById(R.id.syncChainTechnical);
        walletChainState = findViewById(R.id.syncWalletChainState);
        refresh = findViewById(R.id.syncRefreshButton);
        reconnect = findViewById(R.id.syncReconnectButton);
        exportDebugLog = findViewById(R.id.syncExportDebugLogButton);
    }

    private void exportDebugLog() {
        try {
            DebugLog.log(this, "DEBUG_LOG_EXPORT_REQUESTED");
            File file = DebugLog.getLogFile(this);
            Uri uri = FileProvider.getUriForFile(
                    this,
                    getPackageName() + ".debuglog.provider",
                    file);
            Intent share = new Intent(Intent.ACTION_SEND);
            share.setType("text/plain");
            share.putExtra(Intent.EXTRA_STREAM, uri);
            share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(share, getString(R.string.sync_export_debug_log)));
        } catch (Exception error) {
            DebugLog.log(this, "DEBUG_LOG_EXPORT_FAILED " + error);
            Toast.makeText(this, R.string.sync_export_debug_log_failed, Toast.LENGTH_LONG).show();
        }
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
        int current = active.getCurrentBlock();
        int target = active.getNetworkBlock();

        progress.setIndeterminate(false);
        progress.setProgress(pct);
        percent.setText(getString(R.string.percentage_display, pct));
        walletBlock.setText(String.valueOf(current));
        networkBlock.setText(String.valueOf(target));
        syncTargetInline.setText(String.valueOf(target));
        bestChain.setText(String.valueOf(active.getBestChainHeight()));
        blocksBehind.setText(String.valueOf(active.getBlocksBehind()));
        peers.setText(String.valueOf(active.getConnectedPeerCount()));
        pendingPeers.setText(String.valueOf(active.getPendingPeerCount()));
        String hash = active.getBestChainHash();
        chainHash.setText(hash.isEmpty() ? "—" : hash);
        long blockTime = active.getBestChainTimeSeconds();
        lastBlockTime.setText(blockTime <= 0L
                ? "—"
                : new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                        .format(new Date(blockTime * 1000L)));
        if (blockTime <= 0L) {
            syncBlockAge.setText("—");
        } else {
            long ageSeconds = Math.max(0L, (System.currentTimeMillis() / 1000L) - blockTime);
            syncBlockAge.setText(formatAge(ageSeconds));
        }
        syncRate.setText(String.format(Locale.US, "%.2f blocks/s", active.getSyncBlocksPerSecond()));
        network.setText(active.getNetworkName());
        engine.setText(active.isWalletKitRunning()
                ? R.string.sync_engine_running : R.string.sync_engine_stopped);
        restarts.setText(String.valueOf(active.getAutoRestartCount()));
        peerList.setText(getString(R.string.sync_download_peer_value, active.getDownloadPeerDetails())
                + "\n\n" + active.getPeerGroupDetails());
        networkCapabilities.setText(active.getNetworkCapabilities());
        chainTechnical.setText(active.getChainTechnicalDetails());
        walletChainState.setText(active.getWalletChainState());
        status.setText(active.getSyncStatusResId());
        liveNote.setText(R.string.sync_live_note);
        reconnect.setEnabled(active.isWalletReady());
    }

    private String formatAge(long seconds) {
        if (seconds < 60L) return seconds + " s";
        if (seconds < 3600L) return (seconds / 60L) + " min";
        if (seconds < 86400L) return (seconds / 3600L) + " h";
        return (seconds / 86400L) + " d";
    }


    @Override
    protected void onStart() {
        super.onStart();
        DebugLog.activityEvent(this, "SyncActivity.onStart");
    }

    @Override
    protected void onResume() {
        super.onResume();
        DebugLog.activityEvent(this, "SyncActivity.onResume");
        handler.removeCallbacks(updater);
        handler.post(updater);
    }

    @Override
    protected void onPause() {
        DebugLog.activityEvent(this, "SyncActivity.onPause");
        handler.removeCallbacks(updater);
        super.onPause();
    }

    @Override
    protected void onStop() {
        DebugLog.activityEvent(this, "SyncActivity.onStop");
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        DebugLog.activityEvent(this, "SyncActivity.onDestroy");
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override
    public void onUserLeaveHint() {
        DebugLog.activityEvent(this, "SyncActivity.onUserLeaveHint");
        super.onUserLeaveHint();
    }

    @Override
    public void onTrimMemory(int level) {
        DebugLog.activityEvent(this, "SyncActivity.onTrimMemory level=" + level);
        super.onTrimMemory(level);
    }

    @Override public boolean onSupportNavigateUp() {
        finish();
        return true;
    }
}
