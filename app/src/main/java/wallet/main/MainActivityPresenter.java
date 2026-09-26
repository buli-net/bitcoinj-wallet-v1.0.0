package wallet.main;

import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;

import wallet.Constants;
import wallet.model.TransactionItem;
import wallet.transaction.TransactionMapper;
import wallet.security.WalletSecurity;

import org.bitcoinj.base.BitcoinNetwork;
import org.bitcoinj.base.Coin;
import org.bitcoinj.base.ScriptType;
import org.bitcoinj.core.Context;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.PeerGroup;
import org.bitcoinj.core.listeners.DownloadProgressTracker;
import org.bitcoinj.kits.WalletAppKit;
import org.bitcoinj.params.MainNetParams;
import org.bitcoinj.params.TestNet3Params;
import org.bitcoinj.utils.BriefLogFormatter;
import org.bitcoinj.wallet.DeterministicSeed;
import org.bitcoinj.wallet.KeyChainGroupStructure;
import org.bitcoinj.wallet.Wallet;
import org.bitcoinj.wallet.Wallet.BalanceType;
import org.bitcoinj.crypto.MnemonicCode;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.FileOutputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Owns the persistent bitcoinj wallet and sync lifecycle. */
public class MainActivityPresenter
        implements MainActivityContract.MainActivityPresenter {

    private static volatile MainActivityPresenter activePresenter;

    private static final String TAG = "BitcoinWalletSync";

    private static final int MAX_CONNECTIONS = 8;

    private static final long STALL_TIMEOUT_MS = 90_000L;

    private static final int MAX_AUTO_RESTARTS = 8;

    private volatile List<TransactionItem> transactionItems =
            Collections.emptyList();

    private MainActivityContract.MainActivityView view;

    private final File walletDir;

    private NetworkParameters parameters;

    private volatile WalletAppKit walletAppKit;

    private final File walletFile;

    private final Handler mainHandler =
            new Handler(Looper.getMainLooper());

    private final Object kitLock = new Object();

    private final AtomicBoolean restartInProgress =
            new AtomicBoolean(false);

    private volatile boolean walletReady = false;

    private volatile boolean downloadFinished = false;

    private volatile boolean startupInProgress = false;

    private volatile boolean shuttingDown = false;

    private volatile int lastPercent = -1;

    private volatile int lastChainHeight = -1;

    private volatile long lastProgressAt = 0L;

    private volatile int autoRestartCount = 0;

    private volatile DeterministicSeed pendingRestoreSeed;
    private volatile File pendingMnemonicBackupFile;

    private ScheduledExecutorService watchdog;

    public MainActivityPresenter(
            MainActivityContract.MainActivityView view,
            File walletDir) {

        this.view = view;
        this.walletDir = walletDir;
        activePresenter = this;

        this.walletFile =
                new File(
                        walletDir,
                        Constants.WALLET_NAME + ".wallet"
                );

        view.setPresenter(this);
    }

    public void attachView(MainActivityContract.MainActivityView newView) {
        view = newView;
        view.setPresenter(this);
        renderCurrentState();
    }

    private void renderCurrentState() {
        runOnUi(() -> {
            if (walletReady && walletAppKit != null) {
                view.displayDownloadContent(!downloadFinished);

                if (!downloadFinished && lastPercent >= 0) {
                    view.displayPercentage(lastPercent);
                    view.displayProgress(lastPercent);
                }

                refresh();
                return;
            }

            view.displayDownloadContent(true);

            if (lastPercent >= 0) {
                view.displayPercentage(lastPercent);
                view.displayProgress(lastPercent);
            }
        });
    }

    @Override
    public void subscribe() {

        shuttingDown = false;

        parameters =
                Constants.IS_PRODUCTION
                        ? MainNetParams.get()
                        : TestNet3Params.get();

        BriefLogFormatter.init();

        startWatchdog();

        if (walletAppKit != null
                || startupInProgress
                || restartInProgress.get()) {
            renderCurrentState();
            return;
        }

        renderCurrentState();
        startWalletKit();
    }

    private void startWalletKit() {

        synchronized (kitLock) {
            if (shuttingDown
                    || walletAppKit != null
                    || startupInProgress) {
                return;
            }

            startupInProgress = true;
        }

        new Thread(() -> {

            Context.propagate(Context.getOrCreate(parameters));

            if (shuttingDown) {
                startupInProgress = false;
                return;
            }

            WalletAppKit kit = null;

            try {

                synchronized (kitLock) {

                    if (shuttingDown) {
                        startupInProgress = false;
                        return;
                    }

                    final WalletAppKit newKit =
                            new WalletAppKit(
                                    Constants.IS_PRODUCTION
                                            ? BitcoinNetwork.MAINNET
                                            : BitcoinNetwork.TESTNET,
                                    ScriptType.P2WPKH,
                                    KeyChainGroupStructure.BIP43,
                                    walletDir,
                                    Constants.WALLET_NAME
                            ) {

                                @Override
                                protected void onSetupCompleted() {

                                    try {

                                        peerGroup()
                                                .setMaxConnections(
                                                        MAX_CONNECTIONS
                                                );

                                        peerGroup()
                                                .setMaxPeersToDiscoverCount(
                                                        100
                                                );

                                    } catch (Exception e) {

                                        Log.w(
                                                TAG,
                                                "PeerGroup tuning failed; "
                                                        + "using defaults",
                                                e
                                        );
                                    }

                                    setupWalletListeners(wallet());

                                    File restoreBackup = pendingMnemonicBackupFile;
                                    if (restoreBackup != null && restoreBackup.exists()) {
                                        restoreBackup.delete();
                                        pendingMnemonicBackupFile = null;
                                    }

                                    walletReady = true;

                                    int height =
                                            safeChainHeight(this);

                                    lastChainHeight = height;

                                    touchProgress();

                                    runOnUi(() -> {

                                        view.displayDownloadContent(true);

                                        refresh();
                                    });

                                }
                            };

                    newKit.setDownloadListener(
                            new DownloadProgressTracker() {

                                @Override
                                protected void progress(
                                        double pct,
                                        int blocksSoFar,
                                        Instant date) {

                                    super.progress(
                                            pct,
                                            blocksSoFar,
                                            date
                                    );

                                    int percentage =
                                            (int) Math.round(
                                                    pct * 1.0
                                            );

                                    if (percentage < 0) {
                                        percentage = 0;
                                    }

                                    if (percentage > 100) {
                                        percentage = 100;
                                    }

                                    int chainHeight =
                                            safeChainHeight(newKit);

                                    lastPercent = percentage;

                                    if (chainHeight >
                                            lastChainHeight) {

                                        lastChainHeight =
                                                chainHeight;
                                    }

                                    touchProgress();

                                    final int uiPercent =
                                            percentage;

                                    runOnUi(() -> {

                                        view.displayDownloadContent(
                                                true
                                        );

                                        view.displayPercentage(
                                                uiPercent
                                        );

                                        view.displayProgress(
                                                uiPercent
                                        );
                                    });
                                }

                                @Override
                                protected void doneDownload() {

                                    super.doneDownload();

                                    downloadFinished = true;

                                    lastPercent = 100;

                                    lastProgressAt =
                                            System.currentTimeMillis();

                                    runOnUi(() -> {

                                        view.displayPercentage(
                                                100
                                        );

                                        view.displayProgress(
                                                100
                                        );

                                        view.displayDownloadContent(
                                                false
                                        );

                                        refresh();
                                    });
                                }
                            }
                    );

                    newKit.setBlockingStartup(false);

                    newKit.setAutoSave(true);

                    DeterministicSeed restoreSeed = pendingRestoreSeed;
                    if (restoreSeed != null) {
                        newKit.restoreWalletFromSeed(restoreSeed);
                        pendingRestoreSeed = null;
                    }

                    walletAppKit = newKit;

                    kit = newKit;
                }

                lastPercent = -1;

                lastChainHeight =
                        safeChainHeight(kit);

                downloadFinished = false;

                touchProgress();

                kit.startAsync();

                kit.awaitRunning();

                startupInProgress = false;

            } catch (Exception e) {

                File restoreBackup = pendingMnemonicBackupFile;
                if (restoreBackup != null && restoreBackup.exists()) {
                    if (walletFile.exists()) {
                        walletFile.delete();
                    }
                    if (restoreBackup.renameTo(walletFile)) {
                        pendingMnemonicBackupFile = null;
                        pendingRestoreSeed = null;
                    }
                }

                startupInProgress = false;
                walletReady = false;

                WalletAppKit failedKit;

                synchronized (kitLock) {

                    failedKit = walletAppKit;
                }

                Throwable rootCause =
                        findRootCause(e);

                Throwable failureCause = null;

                if (failedKit != null) {

                    try {

                        failureCause =
                                failedKit.failureCause();

                    } catch (Exception ignored) {

                    }
                }

                if (failureCause != null) {

                    rootCause =
                            findRootCause(
                                    failureCause
                            );
                }

                Log.e(
                        TAG,
                        "================================"
                );

                Log.e(
                        TAG,
                        "WalletAppKit FAILED",
                        e
                );

                Log.e(
                        TAG,
                        "WalletAppKit failureCause",
                        failureCause
                );

                Log.e(
                        TAG,
                        "WalletAppKit rootCause",
                        rootCause
                );

                Log.e(
                        TAG,
                        "================================"
                );

                final String errorText =
                        buildFailureMessage(
                                e,
                                rootCause
                        );

                if (!shuttingDown) {

                    runOnUi(() ->
                            view.showToastMessage(
                                    text(R.string.sync_error, errorText)
                            )
                    );

                    scheduleRestartAfterFailure();
                }
            }

        }, "bitcoinj-start").start();
    }

    private void startWatchdog() {

        stopWatchdog();

        watchdog =
                Executors.newSingleThreadScheduledExecutor(
                        r -> {

                            Thread t =
                                    new Thread(
                                            r,
                                            "bitcoinj-watchdog"
                                    );

                            t.setDaemon(true);

                            return t;
                        }
                );

        watchdog.scheduleWithFixedDelay(

                () -> {

                    if (shuttingDown
                            || downloadFinished
                            || restartInProgress.get()) {

                        return;
                    }

                    WalletAppKit kit =
                            walletAppKit;

                    if (kit == null) {
                        return;
                    }

                    if (!kit.isRunning()) {
                        return;
                    }

                    int chainHeight =
                            safeChainHeight(kit);

                    int peerHeight =
                            safePeerHeight(kit);

                    int peers =
                            safePeerCount(kit);

                    if (chainHeight >
                            lastChainHeight) {

                        lastChainHeight =
                                chainHeight;

                        touchProgress();

                        return;
                    }

                    long stalledFor =
                            System.currentTimeMillis()
                                    - lastProgressAt;

                    if (stalledFor <
                            STALL_TIMEOUT_MS) {

                        return;
                    }

                    Log.w(
                            TAG,
                            "SYNC STALLED for "
                                    + (stalledFor / 1000)
                                    + "s: percent="
                                    + lastPercent
                                    + " chain="
                                    + chainHeight
                                    + " peerHeight="
                                    + peerHeight
                                    + " peers="
                                    + peers
                    );

                    if (autoRestartCount >=
                            MAX_AUTO_RESTARTS) {

                        Log.e(
                                TAG,
                                "Maximum automatic sync "
                                        + "restarts reached."
                        );

                        runOnUi(() ->
                                view.showToastMessage(
                                        text(R.string.sync_waiting_network)
                                )
                        );

                        touchProgress();

                        return;
                    }

                    restartWalletKit(
                            "download stalled"
                    );

                },

                15,
                15,
                TimeUnit.SECONDS
        );
    }

    private void scheduleRestartAfterFailure() {

        if (shuttingDown ||
                watchdog == null) {

            return;
        }

        if (autoRestartCount >=
                MAX_AUTO_RESTARTS) {

            Log.e(
                    TAG,
                    "Maximum automatic startup "
                            + "restarts reached."
            );

            runOnUi(() ->
                    view.showToastMessage(
                            text(R.string.wallet_start_failed_detailed, TAG)
                    )
            );

            return;
        }

        watchdog.schedule(

                () -> {

                    if (!shuttingDown &&
                            !restartInProgress.get()) {

                        restartWalletKit(
                                "WalletAppKit startup failed"
                        );
                    }

                },

                10,
                TimeUnit.SECONDS
        );
    }

    private void restartWalletKit(
            String reason) {

        if (shuttingDown) {
            return;
        }

        if (!restartInProgress.compareAndSet(
                false,
                true)) {

            return;
        }

        autoRestartCount++;

        walletReady = false;

        downloadFinished = false;

        Log.w(
                TAG,
                "Restarting WalletAppKit (#"
                        + autoRestartCount
                        + "): "
                        + reason
        );

        runOnUi(() -> {

            view.displayDownloadContent(true);

            view.showToastMessage(
                    text(R.string.blockchain_reconnecting)
            );
        });

        new Thread(() -> {

            try {

                WalletAppKit oldKit;

                synchronized (kitLock) {

                    oldKit =
                            walletAppKit;

                    walletAppKit = null;
                }

                if (oldKit != null) {

                    try {

                        oldKit
                                .stopAsync()
                                .awaitTerminated();

                    } catch (Exception stopError) {

                        Log.w(
                                TAG,
                                "Error stopping old "
                                        + "WalletAppKit",
                                stopError
                        );
                    }
                }

                if (!shuttingDown) {

                    lastPercent = -1;

                    lastChainHeight = -1;

                    touchProgress();

                    startWalletKit();
                }

            } finally {

                restartInProgress.set(false);
            }

        }, "bitcoinj-reconnect").start();
    }

    private void touchProgress() {

        lastProgressAt =
                System.currentTimeMillis();
    }

    private int safeChainHeight(
            WalletAppKit kit) {

        try {

            return kit == null ||
                    kit.chain() == null

                    ? 0

                    : kit.chain()
                            .getBestChainHeight();

        } catch (Exception e) {

            return 0;
        }
    }

    private int safePeerHeight(
            WalletAppKit kit) {

        try {

            PeerGroup peers =
                    kit == null
                            ? null
                            : kit.peerGroup();

            return peers == null
                    ? 0
                    : peers.getMostCommonChainHeight();

        } catch (Exception e) {

            return 0;
        }
    }

    private int safePeerCount(
            WalletAppKit kit) {

        try {

            PeerGroup peers =
                    kit == null
                            ? null
                            : kit.peerGroup();

            return peers == null
                    ? 0
                    : peers.getConnectedPeers()
                            .size();

        } catch (Exception e) {

            return 0;
        }
    }

    private Throwable findRootCause(
            Throwable throwable) {

        if (throwable == null) {
            return null;
        }

        Throwable current =
                throwable;

        int guard = 0;

        while (
                current.getCause() != null
                        && current.getCause() != current
                        && guard++ < 32
        ) {

            current =
                    current.getCause();
        }

        return current;
    }

    private String buildFailureMessage(
            Exception startException,
            Throwable rootCause) {

        Throwable cause =
                rootCause != null
                        ? rootCause
                        : startException;

        String message =
                cause.getMessage();

        if (TextUtils.isEmpty(message)) {

            message =
                    cause.getClass()
                            .getSimpleName();
        }

        return message;
    }

    private String text(int resId, Object... formatArgs) {
        android.content.Context context = view.getActivityContext();
        return formatArgs == null || formatArgs.length == 0
                ? context.getString(resId)
                : context.getString(resId, formatArgs);
    }

    private String safeMessage(
            Exception e) {

        if (e == null) {
            return text(R.string.unknown_error);
        }

        String message =
                e.getMessage();

        return TextUtils.isEmpty(message)

                ? e.getClass()
                        .getSimpleName()

                : message;
    }

    @Override
    public void unsubscribe() {

        if (activePresenter == this) {
            activePresenter = null;
        }

        shuttingDown = true;
        WalletSecurity.clearSessionKey();

        startupInProgress = false;
        walletReady = false;

        stopWatchdog();

        new Thread(() -> {

            WalletAppKit kit;

            synchronized (kitLock) {

                kit =
                        walletAppKit;

                walletAppKit = null;
            }

            try {

                if (kit != null) {

                    kit.stopAsync()
                            .awaitTerminated();
                }

            } catch (Exception e) {

                Log.w(
                        TAG,
                        "Error stopping WalletAppKit",
                        e
                );
            }

        }, "bitcoinj-stop").start();
    }

    private void stopWatchdog() {

        if (watchdog != null) {

            watchdog.shutdownNow();

            watchdog = null;
        }
    }

    @Override
    public void refresh() {

        WalletAppKit kit =
                walletAppKit;

        if (!walletReady ||
                kit == null) {

            return;
        }

        new Thread(() -> {

            Context.propagate(Context.getOrCreate(parameters));

            try {

                Wallet w =
                        kit.wallet();

                String myAddress =
                        w.currentReceiveAddress()
                                .toString();

                final Coin estimatedBalance =
                        w.getBalance(BalanceType.ESTIMATED);
                final Coin availableBalance =
                        w.getBalance(BalanceType.AVAILABLE);
                final Coin pendingBalance =
                        estimatedBalance.subtract(availableBalance);

                final String balance =
                        estimatedBalance.toFriendlyString();

                final String available =
                        text(R.string.available_balance,
                                availableBalance.toFriendlyString());
                final String pending =
                        text(R.string.pending_balance,
                                pendingBalance.toFriendlyString());

                rebuildTransactions(w);

                final List<TransactionItem> transactions =
                        transactionItems;

                runOnUi(() -> {

                    view.displayMyBalance(
                            balance
                    );

                    view.displayBalanceState(available, pending);

                    view.displayMyAddress(
                            myAddress
                    );

                    view.displayTransactions(transactions);
                });

            } catch (Exception e) {

                Log.w(
                        TAG,
                        "Refresh failed",
                        e
                );
            }

        }, "bitcoinj-refresh").start();
    }

    @Override
    public void restoreWallet(final android.net.Uri backupUri) {

        if (backupUri == null) {
            runOnUi(() ->
                    view.showToastMessage(text(R.string.file_restore_invalid))
            );
            return;
        }

        new Thread(() -> {

            Context.propagate(Context.getOrCreate(parameters));

            File tempFile =
                    new File(
                            walletDir,
                            Constants.WALLET_NAME + ".restore.tmp"
                    );

            WalletAppKit oldKit;

            try {

                if (shuttingDown) {
                    throw new IOException(text(R.string.wallet_closing));
                }

                WalletSecurity.clearSessionKey();

                try (InputStream input =
                             ((android.content.Context) view)
                                     .getContentResolver()
                                     .openInputStream(backupUri);
                     FileOutputStream output =
                             new FileOutputStream(tempFile)) {

                    if (input == null) {
                        throw new IOException(text(R.string.restore_read_failed));
                    }

                    byte[] buffer = new byte[8192];
                    int count;
                    long total = 0L;

                    while ((count = input.read(buffer)) != -1) {
                        output.write(buffer, 0, count);
                        total += count;

                        if (total > 64L * 1024L * 1024L) {
                            throw new IOException(text(R.string.restore_too_large));
                        }
                    }

                    output.flush();

                    if (total == 0L) {
                        throw new IOException(text(R.string.restore_empty));
                    }
                }

                Wallet restoredWallet = Wallet.loadFromFile(tempFile);

                if (!restoredWallet.getParams().equals(parameters)) {
                    throw new IOException(
                            text(R.string.restore_network_mismatch)
                    );
                }

                restoredWallet.isConsistentOrThrow();

                stopWatchdog();

                synchronized (kitLock) {
                    oldKit = walletAppKit;
                    walletAppKit = null;
                    walletReady = false;
                }

                if (oldKit != null) {
                    try {
                        oldKit.stopAsync().awaitTerminated();
                    } catch (Exception stopError) {
                        synchronized (kitLock) {
                            walletAppKit = oldKit;
                        }
                        walletReady = false;
                        startWatchdog();
                        throw new IOException(
                                text(R.string.restore_stop_failed),
                                stopError
                        );
                    }
                }

                File backupOfCurrent =
                        new File(
                                walletDir,
                                Constants.WALLET_NAME + ".before-restore.wallet"
                        );

                if (walletFile.exists()) {
                    if (backupOfCurrent.exists() && !backupOfCurrent.delete()) {
                        throw new IOException(text(R.string.restore_before_delete_failed));
                    }

                    if (!walletFile.renameTo(backupOfCurrent)) {
                        throw new IOException(text(R.string.restore_current_wallet_preserve_failed));
                    }
                }

                if (!tempFile.renameTo(walletFile)) {
                    if (!walletFile.exists() && backupOfCurrent.exists()) {
                        backupOfCurrent.renameTo(walletFile);
                    }
                    throw new IOException(text(R.string.restore_install_failed));
                }

                if (backupOfCurrent.exists()) {
                    backupOfCurrent.delete();
                }

                autoRestartCount = 0;
                lastPercent = -1;
                lastChainHeight = -1;
                downloadFinished = false;
                shuttingDown = false;

                runOnUi(() ->
                        view.showToastMessage(
                                text(R.string.restore_success)
                        )
                );

                startWalletKit();
                startWatchdog();

            } catch (Exception e) {

                if (tempFile.exists()) {
                    tempFile.delete();
                }

                Log.e(
                        TAG,
                        "Wallet restore failed",
                        e
                );

                runOnUi(() ->
                        view.showToastMessage(
                                text(R.string.restore_failed, safeMessage(e))
                        )
                );
            }

        }, "bitcoinj-wallet-restore").start();
    }

    @Override
    public void restoreWalletFromMnemonic(
            final String mnemonic,
            final String birthday) {

        new Thread(() -> {
            Context.propagate(Context.getOrCreate(parameters));
            File backupOfCurrent =
                    new File(
                            walletDir,
                            Constants.WALLET_NAME + ".before-mnemonic-restore.wallet");
            File chainFile =
                    new File(
                            walletDir,
                            Constants.WALLET_NAME + ".spvchain");

            try {
                if (shuttingDown) {
                    throw new IOException(text(R.string.wallet_closing));
                }

                List<String> words = new ArrayList<>();
                for (String word : mnemonic.trim().split("\\s+")) {
                    if (!word.isEmpty()) {
                        words.add(word.toLowerCase(java.util.Locale.US));
                    }
                }

                new MnemonicCode().check(words);

                DeterministicSeed seed;
                if (birthday == null || birthday.trim().isEmpty()) {
                    seed = DeterministicSeed.ofMnemonic(words, "");
                } else {
                    Instant creationTime = parseBirthday(birthday);
                    seed = DeterministicSeed.ofMnemonic(words, "", creationTime);
                }

                stopWatchdog();
                WalletSecurity.clearSessionKey();

                WalletAppKit oldKit;
                synchronized (kitLock) {
                    oldKit = walletAppKit;
                    walletAppKit = null;
                    walletReady = false;
                }

                if (oldKit != null) {
                    oldKit.stopAsync().awaitTerminated();
                }

                if (walletFile.exists()) {
                    if (backupOfCurrent.exists() && !backupOfCurrent.delete()) {
                        throw new IOException(
                                text(R.string.restore_before_delete_failed));
                    }
                    if (!walletFile.renameTo(backupOfCurrent)) {
                        throw new IOException(
                                text(R.string.restore_current_wallet_preserve_failed));
                    }
                }

                if (chainFile.exists() && !chainFile.delete()) {
                    if (backupOfCurrent.exists()) {
                        backupOfCurrent.renameTo(walletFile);
                    }
                    throw new IOException(text(R.string.restore_chain_delete_failed));
                }

                pendingRestoreSeed = seed;
                pendingMnemonicBackupFile = backupOfCurrent;
                autoRestartCount = 0;
                lastPercent = -1;
                lastChainHeight = -1;
                downloadFinished = false;
                shuttingDown = false;

                runOnUi(() ->
                        view.showToastMessage(text(R.string.mnemonic_restore_in_progress)));

                startWalletKit();
                startWatchdog();
            } catch (Exception error) {
                pendingRestoreSeed = null;
                pendingMnemonicBackupFile = null;
                if (backupOfCurrent.exists() && !walletFile.exists()) {
                    backupOfCurrent.renameTo(walletFile);
                }
                runOnUi(() ->
                        view.showToastMessage(
                                text(R.string.mnemonic_restore_failed, safeMessage(error))));
            }
        }, "bitcoinj-mnemonic-restore").start();
    }

    private Instant parseBirthday(String birthday) {
        try {
            return java.time.LocalDate.parse(birthday.trim())
                    .atStartOfDay(java.time.ZoneOffset.UTC)
                    .toInstant();
        } catch (java.time.format.DateTimeParseException e) {
            throw new IllegalArgumentException(
                    text(R.string.invalid_wallet_birthday),
                    e
            );
        }
    }

    public void rescanWatchedAddresses(
            final Instant scanFrom,
            final Runnable finished) {

        new Thread(() -> {
            WalletAppKit oldKit = null;
            try {
                Context.propagate(Context.getOrCreate(parameters));

                synchronized (kitLock) {
                    oldKit = walletAppKit;
                    walletAppKit = null;
                    walletReady = false;
                    downloadFinished = false;
                    lastPercent = -1;
                    lastChainHeight = -1;
                }

                if (oldKit != null) {
                    Wallet wallet = oldKit.wallet();
                    List<org.bitcoinj.script.Script> oldScripts = wallet.getWatchedScripts();
                    List<org.bitcoinj.script.Script> rescannedScripts = new ArrayList<>();
                    for (org.bitcoinj.script.Script script : oldScripts) {
                        org.bitcoinj.base.Address address = script.getToAddress(parameters);
                        rescannedScripts.add(
                                org.bitcoinj.script.ScriptBuilder
                                        .createOutputScript(address, scanFrom));
                    }

                    wallet.removeWatchedScripts(oldScripts);
                    wallet.addWatchedScripts(rescannedScripts);
                    wallet.reset();
                    wallet.saveToFile(walletFile);

                    oldKit.stopAsync().awaitTerminated();
                }

                File chainFile =
                        new File(
                                walletDir,
                                Constants.WALLET_NAME + ".spvchain");
                if (chainFile.exists() && !chainFile.delete()) {
                    throw new IOException("Unable to reset SPV chain file");
                }

                shuttingDown = false;
                autoRestartCount = 0;
                startWalletKit();
                startWatchdog();
            } catch (Exception error) {
                Log.e(TAG, "Watch-only rescan failed", error);
                synchronized (kitLock) {
                    walletAppKit = oldKit;
                }
            } finally {
                if (finished != null) {
                    runOnUi(finished);
                }
            }
        }, "bitcoinj-watch-rescan").start();
    }

    public void saveWalletNow() throws IOException {
        WalletAppKit kit = walletAppKit;
        if (kit == null || kit.wallet() == null) {
            throw new IOException(text(R.string.wallet_not_ready));
        }
        kit.wallet().saveToFile(walletFile);
    }

    private void setupWalletListeners(
            Wallet wallet) {

        wallet.addCoinsReceivedEventListener(
                (wallet1,
                 tx,
                 prevBalance,
                 newBalance) -> {

                    try {

                        Coin received =
                                newBalance.minus(prevBalance);

                        Coin estimatedBalance =
                                wallet1.getBalance(BalanceType.ESTIMATED);
                        Coin availableBalance =
                                wallet1.getBalance(BalanceType.AVAILABLE);
                        Coin pendingBalance =
                                estimatedBalance.subtract(availableBalance);
                        String balance =
                                estimatedBalance.toFriendlyString();
                        String available =
                                text(R.string.available_balance,
                                        availableBalance.toFriendlyString());
                        String pending =
                                text(R.string.pending_balance,
                                        pendingBalance.toFriendlyString());

                        String currentAddress =
                                wallet1.currentReceiveAddress().toString();

                        rebuildTransactions(wallet1);

                        List<TransactionItem> transactions =
                                transactionItems;

                        runOnUi(() -> {

                            view.displayMyBalance(balance);
                            view.displayBalanceState(available, pending);
                            view.displayMyAddress(currentAddress);
                            view.displayTransactions(transactions);

                            if (tx.getPurpose() ==
                                    Transaction.Purpose.UNKNOWN) {

                                view.showToastMessage(
                                        text(R.string.receive_message,
                                                received.toFriendlyString())
                                );
                            }
                        });

                    } catch (Exception e) {

                        Log.e(
                                TAG,
                                "Failed to process coins received event",
                                e
                        );
                    }
                }
        );

        wallet.addCoinsSentEventListener(
                (wallet1,
                 tx,
                 prevBalance,
                 newBalance) -> {

                    try {

                        Coin estimatedBalance =
                                wallet1.getBalance(BalanceType.ESTIMATED);
                        Coin availableBalance =
                                wallet1.getBalance(BalanceType.AVAILABLE);
                        Coin pendingBalance =
                                estimatedBalance.subtract(availableBalance);
                        String balance =
                                estimatedBalance.toFriendlyString();
                        String available =
                                text(R.string.available_balance,
                                        availableBalance.toFriendlyString());
                        String pending =
                                text(R.string.pending_balance,
                                        pendingBalance.toFriendlyString());

                        rebuildTransactions(wallet1);

                        List<TransactionItem> transactions =
                                transactionItems;

                        runOnUi(() -> {

                            view.displayMyBalance(balance);
                            view.displayBalanceState(available, pending);
                            view.displayTransactions(transactions);
                        });

                    } catch (Exception e) {

                        Log.e(
                                TAG,
                                "Failed to process coins sent event",
                                e
                        );
                    }
                }
        );

        wallet.addTransactionConfidenceEventListener(
                (wallet1, tx) -> {
                    try {
                        rebuildTransactions(wallet1);

                        final Coin estimatedBalance =
                                wallet1.getBalance(BalanceType.ESTIMATED);
                        final Coin availableBalance =
                                wallet1.getBalance(BalanceType.AVAILABLE);
                        final Coin pendingBalance =
                                estimatedBalance.subtract(availableBalance);
                        final String balance =
                                estimatedBalance.toFriendlyString();
                        final String available =
                                text(R.string.available_balance,
                                        availableBalance.toFriendlyString());
                        final String pending =
                                text(R.string.pending_balance,
                                        pendingBalance.toFriendlyString());
                        final List<TransactionItem> transactions =
                                transactionItems;

                        runOnUi(() -> {
                            view.displayMyBalance(balance);
                            view.displayBalanceState(available, pending);
                            view.displayTransactions(transactions);
                        });
                    } catch (Exception e) {
                        Log.w(TAG, "Transaction confidence update failed", e);
                    }
                }
        );
    }

    private void rebuildTransactions(Wallet wallet) {
        try {
            transactionItems = TransactionMapper.map(view.getActivityContext(), wallet);
        } catch (Exception e) {
            Log.w(TAG, "Build transaction list failed", e);
            transactionItems = Collections.emptyList();
        }
    }

    public static MainActivityPresenter getActivePresenter() {
        return activePresenter;
    }

    public static WalletAppKit getActiveWalletAppKit() {
        MainActivityPresenter presenter = activePresenter;
        return presenter == null ? null : presenter.walletAppKit;
    }

    public static NetworkParameters getActiveParameters() {
        MainActivityPresenter presenter = activePresenter;
        return presenter == null ? null : presenter.parameters;
    }

    private void runOnUi(
            Runnable r) {

        if (Looper.myLooper()
                == Looper.getMainLooper()) {

            r.run();

        } else {

            mainHandler.post(r);
        }
    }

}
