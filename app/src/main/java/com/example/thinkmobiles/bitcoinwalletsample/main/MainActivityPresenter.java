package com.example.thinkmobiles.bitcoinwalletsample.main;

import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;

import com.example.thinkmobiles.bitcoinwalletsample.Constants;

import org.bitcoinj.base.LegacyAddress;
import org.bitcoinj.base.Coin;
import org.bitcoinj.core.InsufficientMoneyException;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.PeerGroup;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.listeners.DownloadProgressTracker;
import org.bitcoinj.crypto.ECKey;
import org.bitcoinj.kits.WalletAppKit;
import org.bitcoinj.params.MainNetParams;
import org.bitcoinj.params.TestNet3Params;
import org.bitcoinj.utils.BriefLogFormatter;
import org.bitcoinj.utils.Threading;
import org.bitcoinj.wallet.SendRequest;
import org.bitcoinj.wallet.Wallet;

import java.io.File;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Main wallet presenter.
 *
 * WalletAppKit remains responsible for the blockchain and peer lifecycle.
 * This presenter adds:
 *
 * 1. Conservative PeerGroup configuration.
 * 2. A watchdog based on real chain height, not only percentage.
 * 3. Automatic reconnect when the download is genuinely stalled.
 * 4. Full WalletAppKit failure-cause logging so startup failures are diagnosable.
 *
 * The existing .spvchain is never deleted by this class.
 */
public class MainActivityPresenter implements MainActivityContract.MainActivityPresenter {

    private static final String TAG = "BitcoinWalletSync";

    /*
     * Keep the peer configuration conservative. Do not call deprecated peer
     * discovery timeout APIs here: bitcoinj's normal PeerGroup discovery is
     * safer for startup and will replace disconnected peers automatically.
     */
    private static final int MAX_CONNECTIONS = 8;

    /*
     * Restart only after the actual chain height and download callbacks have
     * both stopped moving for a meaningful amount of time.
     */
    private static final long STALL_TIMEOUT_MS = 90_000L;

    /*
     * Prevent an endless reconnect loop if the device has no usable network or
     * the wallet/chain files are genuinely broken.
     */
    private static final int MAX_AUTO_RESTARTS = 8;

    private MainActivityContract.MainActivityView view;
    private final File walletDir;
    private NetworkParameters parameters;
    private volatile WalletAppKit walletAppKit;

    private final File walletFile;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Object kitLock = new Object();
    private final AtomicBoolean restartInProgress = new AtomicBoolean(false);

    private volatile boolean walletReady = false;
    private volatile boolean downloadFinished = false;
    private volatile boolean shuttingDown = false;

    private volatile int lastPercent = -1;
    private volatile int lastChainHeight = -1;
    private volatile long lastProgressAt = 0L;
    private volatile int autoRestartCount = 0;

    private ScheduledExecutorService watchdog;

    public MainActivityPresenter(
            MainActivityContract.MainActivityView view,
            File walletDir) {

        this.view = view;
        this.walletDir = walletDir;
        this.walletFile = new File(
                walletDir,
                Constants.WALLET_NAME + ".wallet"
        );

        view.setPresenter(this);
    }

    @Override
    public void subscribe() {
        shuttingDown = false;

        setBtcSDKThread();

        parameters = Constants.IS_PRODUCTION
                ? MainNetParams.get()
                : TestNet3Params.get();

        BriefLogFormatter.init();

        runOnUi(() -> view.displayDownloadContent(true));

        startWatchdog();
        startWalletKit();
    }

    private void startWalletKit() {

        new Thread(() -> {

            if (shuttingDown) {
                return;
            }

            WalletAppKit kit = null;

            try {

                synchronized (kitLock) {

                    if (shuttingDown) {
                        return;
                    }

                    final WalletAppKit newKit =
                            new WalletAppKit(
                                    parameters,
                                    walletDir,
                                    Constants.WALLET_NAME
                            ) {

                                @Override
                                protected void onSetupCompleted() {

                                    /*
                                     * Configure only the stable PeerGroup
                                     * options here.
                                     *
                                     * Do NOT use the deprecated
                                     * setPeerDiscoveryTimeoutMillis().
                                     */
                                    try {

                                        peerGroup().setMaxConnections(
                                                MAX_CONNECTIONS
                                        );

                                        peerGroup().setMaxPeersToDiscoverCount(
                                                100
                                        );

                                        Log.d(
                                                TAG,
                                                "PeerGroup configured: "
                                                        + "maxConnections="
                                                        + MAX_CONNECTIONS
                                                        + ", maxPeersToDiscover=100"
                                        );

                                    } catch (Exception e) {

                                        /*
                                         * Peer tuning must never prevent
                                         * WalletAppKit from starting.
                                         */
                                        Log.w(
                                                TAG,
                                                "PeerGroup tuning failed; using defaults",
                                                e
                                        );
                                    }

                                    /*
                                     * Create an EC key only when the wallet
                                     * does not already contain one.
                                     */
                                    if (wallet().getImportedKeys().size() < 1) {
                                        wallet().importKey(new ECKey());
                                    }

                                    setupWalletListeners(wallet());

                                    walletReady = true;

                                    int height = safeChainHeight(this);

                                    lastChainHeight = height;

                                    touchProgress();

                                    runOnUi(() -> {

                                        view.displayWalletPath(
                                                walletFile.getAbsolutePath()
                                        );

                                        view.displayDownloadContent(true);

                                        refresh();
                                    });

                                    Log.d(
                                            TAG,
                                            "Wallet setup complete. "
                                                    + "chainHeight="
                                                    + height
                                    );

                                    try {

                                        Log.d(
                                                TAG,
                                                "My address = "
                                                        + wallet()
                                                        .freshReceiveAddress()
                                        );

                                    } catch (Exception e) {

                                        Log.w(
                                                TAG,
                                                "Could not create receive address yet",
                                                e
                                        );
                                    }
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

                                    int peerHeight =
                                            safePeerHeight(newKit);

                                    int peers =
                                            safePeerCount(newKit);

                                    /*
                                     * A progress callback itself means
                                     * bitcoinj is making progress, even when
                                     * the displayed percentage remains the same.
                                     */
                                    lastPercent = percentage;

                                    if (chainHeight > lastChainHeight) {
                                        lastChainHeight = chainHeight;
                                    }

                                    touchProgress();

                                    Log.d(
                                            TAG,
                                            "SYNC progress="
                                                    + percentage
                                                    + "% blocks="
                                                    + blocksSoFar
                                                    + " chain="
                                                    + chainHeight
                                                    + " peerHeight="
                                                    + peerHeight
                                                    + " peers="
                                                    + peers
                                    );

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

                                    Log.d(
                                            TAG,
                                            "SYNC doneDownload chain="
                                                    + safeChainHeight(newKit)
                                                    + " peerHeight="
                                                    + safePeerHeight(newKit)
                                                    + " peers="
                                                    + safePeerCount(newKit)
                                    );

                                    runOnUi(() -> {

                                        view.displayPercentage(100);

                                        view.displayProgress(100);

                                        view.displayDownloadContent(false);

                                        refresh();
                                    });
                                }
                            }
                    );

                    /*
                     * Non-blocking startup is important on Android.
                     */
                    newKit.setBlockingStartup(false);

                    newKit.setAutoSave(true);

                    walletAppKit = newKit;

                    kit = newKit;
                }

                lastPercent = -1;

                lastChainHeight =
                        safeChainHeight(kit);

                downloadFinished = false;

                touchProgress();

                Log.d(
                        TAG,
                        "Starting WalletAppKit. "
                                + "walletDir="
                                + walletDir.getAbsolutePath()
                                + ", walletFile="
                                + walletFile.getAbsolutePath()
                );

                /*
                 * Start asynchronously.
                 */
                kit.startAsync();

                /*
                 * Wait until RUNNING.
                 *
                 * If the service fails, the catch block below will retrieve
                 * WalletAppKit.failureCause() instead of showing only the
                 * generic Guava service failure message.
                 */
                kit.awaitRunning();

                Log.d(
                        TAG,
                        "WalletAppKit is RUNNING. "
                                + "peers="
                                + safePeerCount(kit)
                                + ", chainHeight="
                                + safeChainHeight(kit)
                );

            } catch (Exception e) {

                walletReady = false;

                WalletAppKit failedKit;

                synchronized (kitLock) {
                    failedKit = walletAppKit;
                }

                Throwable rootCause =
                        findRootCause(e);

                Throwable failureCause = null;

                /*
                 * WalletAppKit contains the actual failure cause.
                 */
                if (failedKit != null) {

                    try {

                        failureCause =
                                failedKit.failureCause();

                    } catch (Exception ignored) {
                        // Keep the exception from awaitRunning().
                    }
                }

                if (failureCause != null) {

                    rootCause =
                            findRootCause(failureCause);
                }

                /*
                 * These three logs are extremely important for diagnosing
                 * startup problems.
                 */
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

                final String errorText =
                        buildFailureMessage(
                                e,
                                failureCause,
                                rootCause
                        );

                if (!shuttingDown) {

                    runOnUi(() ->
                            view.showToastMessage(
                                    "Bitcoin sync error: "
                                            + errorText
                            )
                    );

                    scheduleRestartAfterFailure();
                }
            }

        }, "bitcoinj-start").start();
    }

    /**
     * Watches the actual chain height.
     *
     * The percentage is only a UI indicator.
     * Chain height is the stronger signal for real blockchain progress.
     */
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

                    /*
                     * If chain height is increasing,
                     * blockchain synchronization is healthy.
                     */
                    if (chainHeight > lastChainHeight) {

                        lastChainHeight =
                                chainHeight;

                        touchProgress();

                        Log.d(
                                TAG,
                                "WATCHDOG healthy: chain="
                                        + chainHeight
                                        + " peerHeight="
                                        + peerHeight
                                        + " peers="
                                        + peers
                        );

                        return;
                    }

                    long stalledFor =
                            System.currentTimeMillis()
                                    - lastProgressAt;

                    if (stalledFor < STALL_TIMEOUT_MS) {
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

                    if (autoRestartCount >= MAX_AUTO_RESTARTS) {

                        Log.e(
                                TAG,
                                "Maximum automatic sync restarts reached."
                        );

                        runOnUi(() ->
                                view.showToastMessage(
                                        "Sync đang chờ mạng. "
                                                + "Hãy giữ ứng dụng mở "
                                                + "để tiếp tục."
                                )
                        );

                        /*
                         * Prevent repeating the same warning every 15 seconds.
                         */
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

        if (shuttingDown
                || watchdog == null) {

            return;
        }

        if (autoRestartCount >= MAX_AUTO_RESTARTS) {

            Log.e(
                    TAG,
                    "Maximum automatic startup restarts reached."
            );

            runOnUi(() ->
                    view.showToastMessage(
                            "Bitcoin không thể khởi động. "
                                    + "Xem Logcat với tag "
                                    + TAG
                                    + " để biết nguyên nhân."
                    )
            );

            return;
        }

        watchdog.schedule(
                () -> {

                    if (!shuttingDown
                            && !restartInProgress.get()) {

                        restartWalletKit(
                                "WalletAppKit startup failed"
                        );
                    }

                },
                10,
                TimeUnit.SECONDS
        );
    }

    /**
     * Restart only WalletAppKit / PeerGroup.
     *
     * The existing .spvchain is preserved.
     */
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
                    "Kết nối blockchain bị gián đoạn, "
                            + "đang kết nối lại..."
            );
        });

        new Thread(() -> {

            try {

                WalletAppKit oldKit;

                synchronized (kitLock) {

                    oldKit =
                            walletAppKit;

                    walletAppKit =
                            null;
                }

                if (oldKit != null) {

                    try {

                        oldKit.stopAsync()
                                .awaitTerminated();

                    } catch (Exception stopError) {

                        Log.w(
                                TAG,
                                "Error stopping old WalletAppKit",
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

            return kit == null
                    || kit.chain() == null
                    ? 0
                    : kit.chain().getBestChainHeight();

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
                    : peers.getConnectedPeers().size();

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

        while (current.getCause() != null
                && current.getCause() != current
                && guard++ < 32) {

            current =
                    current.getCause();
        }

        return current;
    }

    private String buildFailureMessage(
            Exception startException,
            Throwable failureCause,
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

    private String safeMessage(
            Exception e) {

        if (e == null) {
            return "Unknown error";
        }

        String message =
                e.getMessage();

        return TextUtils.isEmpty(message)
                ? e.getClass().getSimpleName()
                : message;
    }

    @Override
    public void unsubscribe() {

        shuttingDown = true;

        walletReady = false;

        stopWatchdog();

        new Thread(() -> {

            WalletAppKit kit;

            synchronized (kitLock) {

                kit =
                        walletAppKit;

                walletAppKit =
                        null;
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

        if (!walletReady
                || kit == null) {

            return;
        }

        new Thread(() -> {

            try {

                Wallet w =
                        kit.wallet();

                String myAddress =
                        w.freshReceiveAddress()
                                .toString();

                runOnUi(() -> {

                    view.displayMyBalance(
                            w.getBalance()
                                    .toFriendlyString()
                    );

                    view.displayMyAddress(
                            myAddress
                    );
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
    public void pickRecipient() {

        view.displayRecipientAddress(null);

        view.startScanQR();
    }

    @Override
    public void send() {

        WalletAppKit kit =
                walletAppKit;

        if (!walletReady
                || kit == null) {

            return;
        }

        final String recipientAddress =
                view.getRecipient();

        final String amount =
                view.getAmount();

        if (TextUtils.isEmpty(recipientAddress)
                || recipientAddress.equals(
                        "Scan recipient QR")) {

            view.showToastMessage(
                    "Select recipient"
            );

            return;
        }

        if (TextUtils.isEmpty(amount)) {

            view.showToastMessage(
                    "Select valid amount"
            );

            return;
        }

        final Coin coinAmount;

        try {

            coinAmount =
                    Coin.parseCoin(amount);

            if (coinAmount.isZero()
                    || coinAmount.isNegative()) {

                view.showToastMessage(
                        "Select valid amount"
                );

                return;
            }

        } catch (Exception e) {

            view.showToastMessage(
                    "Select valid amount"
            );

            return;
        }

        new Thread(() -> {

            try {

                Wallet w =
                        kit.wallet();

                if (w.getBalance()
                        .isLessThan(
                                coinAmount)) {

                    runOnUi(() ->
                            view.showToastMessage(
                                    "You got not enough coins"
                            )
                    );

                    return;
                }

                SendRequest request =
                        SendRequest.to(
                                LegacyAddress.fromBase58(
                                        parameters,
                                        recipientAddress
                                ),
                                coinAmount
                        );

                w.completeTx(request);

                w.commitTx(request.tx);

                kit.peerGroup()
                        .broadcastTransaction(
                                request.tx
                        )
                        .broadcast();

                runOnUi(() -> {

                    view.clearAmount();

                    view.displayRecipientAddress(
                            null
                    );
                });

            } catch (InsufficientMoneyException e) {

                Log.e(
                        TAG,
                        "Send failed",
                        e
                );

                runOnUi(() ->
                        view.showToastMessage(
                                safeMessage(e)
                        )
                );

            } catch (Exception e) {

                Log.e(
                        TAG,
                        "Send failed",
                        e
                );

                runOnUi(() ->
                        view.showToastMessage(
                                safeMessage(e)
                        )
                );
            }

        }, "bitcoinj-send").start();
    }

    @Override
    public void getInfoDialog() {

        WalletAppKit kit =
                walletAppKit;

        if (!walletReady
                || kit == null) {

            return;
        }

        new Thread(() -> {

            try {

                String addr =
                        kit.wallet()
                                .currentReceiveAddress()
                                .toString();

                runOnUi(() ->
                        view.displayInfoDialog(
                                addr
                        )
                );

            } catch (Exception e) {

                Log.w(
                        TAG,
                        "Info failed",
                        e
                );
            }

        }, "bitcoinj-info").start();
    }

    private void setBtcSDKThread() {

        Threading.USER_THREAD =
                mainHandler::post;
    }

    private void setupWalletListeners(
            Wallet wallet) {

        wallet.addCoinsReceivedEventListener(
                (wallet1,
                 tx,
                 prevBalance,
                 newBalance) -> {

                    runOnUi(() -> {

                        view.displayMyBalance(
                                wallet.getBalance()
                                        .toFriendlyString()
                        );

                        if (tx.getPurpose()
                                == Transaction.Purpose.UNKNOWN) {

                            view.showToastMessage(
                                    "Receive "
                                            + newBalance
                                            .minus(prevBalance)
                                            .toFriendlyString()
                            );
                        }
                    });
                }
        );

        wallet.addCoinsSentEventListener(
                (wallet12,
                 tx,
                 prevBalance,
                 newBalance) -> {

                    runOnUi(() -> {

                        view.displayMyBalance(
                                wallet.getBalance()
                                        .toFriendlyString()
                        );

                        view.clearAmount();

                        view.displayRecipientAddress(
                                null
                        );

                        view.showToastMessage(
                                "Sent "
                                        + prevBalance
                                        .minus(newBalance)
                                        .minus(tx.getFee())
                                        .toFriendlyString()
                        );
                    });
                }
        );
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
