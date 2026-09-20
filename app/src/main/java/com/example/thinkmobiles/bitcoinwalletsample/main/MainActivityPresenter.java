package com.example.thinkmobiles.bitcoinwalletsample.main;

import android.os.Handler;
import android.os.Looper;
import android.support.v7.app.AlertDialog;
import android.text.TextUtils;
import android.util.Log;

import com.example.thinkmobiles.bitcoinwalletsample.Constants;

import org.bitcoinj.base.Coin;
import org.bitcoinj.base.Address;
import org.bitcoinj.core.Context;
import org.bitcoinj.core.InsufficientMoneyException;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.PeerGroup;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.listeners.DownloadProgressTracker;
import org.bitcoinj.kits.WalletAppKit;
import org.bitcoinj.params.MainNetParams;
import org.bitcoinj.params.TestNet3Params;
import org.bitcoinj.utils.BriefLogFormatter;
import org.bitcoinj.wallet.SendRequest;
import org.bitcoinj.wallet.Wallet;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.time.Instant;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class MainActivityPresenter
        implements MainActivityContract.MainActivityPresenter {

    private static final String TAG = "BitcoinWalletSync";

    /*
     * Maximum number of connected peers.
     */
    private static final int MAX_CONNECTIONS = 8;

    /*
     * Restart only when the blockchain really stops moving.
     */
    private static final long STALL_TIMEOUT_MS = 90_000L;

    /*
     * Prevent endless restart loops.
     */
    private static final int MAX_AUTO_RESTARTS = 8;

    /* Fee slider: 1 to 10 sat/vB, represented as sat/vkB. */
    private static final int MIN_FEE_RATE_SAT_PER_VKB = 1000;
    private static final int MAX_FEE_RATE_SAT_PER_VKB = 10000;

    /* Transaction history: 10 transactions per page, no total-history limit. */
    private static final int HISTORY_PAGE_SIZE = 10;

    private volatile List<String> historyItems =
            Collections.emptyList();

    private volatile int historyPage = 0;

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

        this.walletFile =
                new File(
                        walletDir,
                        Constants.WALLET_NAME + ".wallet"
                );

        view.setPresenter(this);
    }


    @Override
    public void subscribe() {

        shuttingDown = false;

        parameters =
                Constants.IS_PRODUCTION
                        ? MainNetParams.get()
                        : TestNet3Params.get();

        BriefLogFormatter.init();

        runOnUi(() ->
                view.displayDownloadContent(true)
        );

        startWatchdog();

        startWalletKit();
    }


    private void startWalletKit() {

        new Thread(() -> {

            Context.propagate(Context.getOrCreate(parameters));

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
                                     * Configure PeerGroup.
                                     */
                                    try {

                                        peerGroup()
                                                .setMaxConnections(
                                                        MAX_CONNECTIONS
                                                );

                                        peerGroup()
                                                .setMaxPeersToDiscoverCount(
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

                                        Log.w(
                                                TAG,
                                                "PeerGroup tuning failed; "
                                                        + "using defaults",
                                                e
                                        );
                                    }


                                    /*
                                     * Do NOT create/import a random ECKey.
                                     *
                                     * WalletAppKit already manages the
                                     * deterministic wallet.
                                     */
                                    setupWalletListeners(wallet());

                                    walletReady = true;


                                    int height =
                                            safeChainHeight(this);

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


                                    /*
                                     * IMPORTANT:
                                     *
                                     * currentReceiveAddress()
                                     * does NOT intentionally rotate
                                     * the receive address.
                                     */
                                    try {

                                        Log.d(
                                                TAG,
                                                "Current receive address = "
                                                        + wallet()
                                                        .currentReceiveAddress()
                                        );

                                    } catch (Exception e) {

                                        Log.w(
                                                TAG,
                                                "Could not read current "
                                                        + "receive address",
                                                e
                                        );
                                    }
                                }
                            };


                    /*
                     * Blockchain download progress.
                     */
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
                                     * Any real progress callback means
                                     * bitcoinj is still working.
                                     */
                                    lastPercent = percentage;


                                    if (chainHeight >
                                            lastChainHeight) {

                                        lastChainHeight =
                                                chainHeight;
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
                                                    + safeChainHeight(
                                                    newKit
                                            )
                                                    + " peerHeight="
                                                    + safePeerHeight(
                                                    newKit
                                            )
                                                    + " peers="
                                                    + safePeerCount(
                                                    newKit
                                            )
                                    );


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


                    /*
                     * Non-blocking startup.
                     */
                    newKit.setBlockingStartup(false);


                    /*
                     * WalletAppKit owns autosave.
                     */
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
                 * Start the bitcoinj service.
                 */
                kit.startAsync();


                /*
                 * Wait until WalletAppKit becomes RUNNING.
                 *
                 * If it fails, the catch block below retrieves
                 * WalletAppKit.failureCause().
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


                /*
                 * IMPORTANT:
                 *
                 * The exception:
                 *
                 * Expected the service [FAILED] to be RUNNING
                 *
                 * is only a wrapper.
                 *
                 * failureCause() contains the real reason.
                 */
                Throwable failureCause = null;


                if (failedKit != null) {

                    try {

                        failureCause =
                                failedKit.failureCause();

                    } catch (Exception ignored) {
                        /*
                         * Keep the exception from awaitRunning().
                         */
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


    /*
     * Watch blockchain height rather than relying only
     * on the displayed percentage.
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
                     * If blockchain height increases,
                     * sync is alive.
                     */
                    if (chainHeight >
                            lastChainHeight) {

                        lastChainHeight =
                                chainHeight;

                        touchProgress();


                        Log.d(
                                TAG,
                                "WATCHDOG healthy: "
                                        + "chain="
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
                                        "Sync đang chờ mạng. "
                                                + "Hãy giữ ứng dụng mở "
                                                + "để tiếp tục."
                                )
                        );


                        /*
                         * Prevent repeated warnings.
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


    /*
     * Restart only WalletAppKit/PeerGroup.
     *
     * NEVER delete wallet or SPV chain here.
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

                ? e.getClass()
                        .getSimpleName()

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


                /*
                 * IMPORTANT:
                 *
                 * Do NOT use freshReceiveAddress().
                 *
                 * That explicitly requests a new receive address.
                 *
                 * currentReceiveAddress() keeps the current
                 * receive address stable.
                 */
                String myAddress =
                        w.currentReceiveAddress()
                                .toString();


                final String balance =
                        w.getBalance().toFriendlyString();

                final int pageCount =
                        rebuildTransactionHistory(w);

                final String history =
                        getHistoryPageText();

                final int currentPage = historyPage;

                runOnUi(() -> {

                    view.displayMyBalance(
                            balance
                    );

                    view.displayMyAddress(
                            myAddress
                    );

                    view.displayTransactionHistory(
                            history
                    );

                    view.displayTransactionHistoryPages(
                            currentPage,
                            pageCount
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

        WalletAppKit kit = walletAppKit;

        if (!walletReady || kit == null) {
            return;
        }

        final String recipientAddress = view.getRecipient();
        final String amount = view.getAmount();
        final int feeRateSatPerVkb = view.getFeeRateSatPerVkb();

        if (TextUtils.isEmpty(recipientAddress)
                || recipientAddress.equals("Scan recipient QR")) {
            view.showToastMessage("Select recipient");
            return;
        }

        if (TextUtils.isEmpty(amount)) {
            view.showToastMessage("Select valid amount");
            return;
        }

        if (feeRateSatPerVkb < MIN_FEE_RATE_SAT_PER_VKB
                || feeRateSatPerVkb > MAX_FEE_RATE_SAT_PER_VKB) {
            view.showToastMessage("Select valid fee rate");
            return;
        }

        final Coin coinAmount;

        try {
            coinAmount = Coin.parseCoin(amount);

            if (coinAmount.isZero() || coinAmount.isNegative()) {
                view.showToastMessage("Select valid amount");
                return;
            }
        } catch (Exception e) {
            view.showToastMessage("Select valid amount");
            return;
        }

        new Thread(() -> {

            Context.propagate(Context.getOrCreate(parameters));

            try {

                Wallet w = kit.wallet();

                if (w.getBalance().isLessThan(coinAmount)) {
                    runOnUi(() -> view.showToastMessage(
                            "Not enough balance. Available: "
                                    + w.getBalance().toFriendlyString()));
                    return;
                }

                Address destination = Address.fromString(
                        parameters,
                        recipientAddress
                );

                SendRequest request = SendRequest.to(
                        destination,
                        coinAmount
                );

                /*
                 * bitcoinj 0.17.1 uses feePerKb as satoshis per
                 * virtual kilobyte. The UI exposes the same unit.
                 */
                request.setFeePerVkb(
                        Coin.valueOf(feeRateSatPerVkb)
                );

                /*
                 * Keep bitcoinj's minimum relay-fee protection enabled.
                 * The selected fee is therefore a target, not a promise
                 * that the final fee can be lower than network policy.
                 */
                request.ensureMinRequiredFee = true;

                w.completeTx(request);

                final Coin actualFee = request.tx.getFee();
                final Coin total = coinAmount.add(actualFee);
                final Coin balanceAfter = w.getBalance().minus(total);
                final int txSize = request.tx.getVsize();

                /*
                 * This is the fee target for the final virtual size.
                 * It is NOT necessarily the final fee: if bitcoinj would
                 * create a dust change output, that change is added to the
                 * fee instead of creating an unusable output.
                 */
                final Coin targetFee = Coin.valueOf(
                        ((long) feeRateSatPerVkb * txSize) / 1000L
                );

                final long actualFeeSat = actualFee.getValue();
                final double actualSatPerVb =
                        txSize == 0
                                ? 0.0
                                : actualFeeSat / (double) txSize;

                final Coin extraFee =
                        actualFee.isGreaterThan(targetFee)
                                ? actualFee.subtract(targetFee)
                                : Coin.ZERO;

                final boolean feeAboveTarget =
                        actualFee.isGreaterThan(targetFee);

                final StringBuilder detailsBuilder =
                        new StringBuilder();

                detailsBuilder
                        .append("Recipient:\n")
                        .append(recipientAddress)
                        .append("\n\nAmount:\n")
                        .append(coinAmount.toFriendlyString())
                        .append("\n\nFee rate (requested):\n")
                        .append(feeRateSatPerVkb)
                        .append(" sat/vkB (")
                        .append(String.format(Locale.US, "%.1f", feeRateSatPerVkb / 1000.0))
                        .append(" sat/vB)")
                        .append("\n\nTransaction size:\n")
                        .append(txSize)
                        .append(" vbytes")
                        .append("\n\nFee details\n")
                        .append("Target fee for this size: ")
                        .append(targetFee.toFriendlyString())
                        .append("\nActual fee: ")
                        .append(actualFee.toFriendlyString())
                        .append("\nActual fee rate: ")
                        .append(String.format(Locale.US, "%.2f", actualSatPerVb))
                        .append(" sat/vB ("
                                + String.format(Locale.US, "%.0f", actualSatPerVb * 1000.0)
                                + " sat/vkB)");

                if (feeAboveTarget) {
                    detailsBuilder
                            .append("\nExtra fee above target: ")
                            .append(extraFee.toFriendlyString())
                            .append("\n\nNote: The selected fee rate is a target. "
                                    + "bitcoinj can add a small change amount to the fee "
                                    + "when the change output would be dust. This is why the "
                                    + "actual fee can be higher than the selected rate.");
                }

                if (balanceAfter.isZero()) {
                    detailsBuilder
                            .append("\n\nWarning: This transaction spends the entire "
                                    + "available balance.");
                }

                detailsBuilder
                        .append("\n\nTotal (amount + fee):\n")
                        .append(total.toFriendlyString())
                        .append("\n\nEstimated balance after:\n")
                        .append(balanceAfter.toFriendlyString());

                final String details = detailsBuilder.toString();

                runOnUi(() -> {
                    view.displaySendDetails(
                            feeRateSatPerVkb + " sat/vkB",
                            actualFee.toFriendlyString()
                                    + " ("
                                    + String.format(Locale.US, "%.2f", actualSatPerVb)
                                    + " sat/vB)",
                            total.toFriendlyString(),
                            balanceAfter.toFriendlyString()
                    );

                    showSendConfirmation(
                            details,
                            () -> {
                            new Thread(() -> {
                                Context.propagate(
                                        Context.getOrCreate(parameters)
                                );

                                try {
                                    Wallet currentWallet = kit.wallet();
                                    currentWallet.commitTx(request.tx);

                                    kit.peerGroup()
                                            .broadcastTransaction(request.tx)
                                            .broadcast();

                                    runOnUi(() -> {
                                        view.clearAmount();
                                        view.displayRecipientAddress(null);
                                        view.showToastMessage(
                                                "Transaction broadcast. Fee: "
                                                        + actualFee.toFriendlyString()
                                        );
                                    });
                                } catch (Exception e) {
                                    Log.e(TAG, "Broadcast failed", e);
                                    runOnUi(() -> view.showToastMessage(
                                            "Broadcast failed: " + safeMessage(e)
                                    ));
                                }
                            }, "bitcoinj-broadcast").start();
                        }
                    );
                });

            } catch (InsufficientMoneyException e) {

                Log.e(TAG, "Send failed", e);

                runOnUi(() -> view.showToastMessage(
                        "Insufficient money. Missing: "
                                + e.missing.toFriendlyString()
                                + "\nSelected fee rate: "
                                + feeRateSatPerVkb + " sat/vkB"
                ));

            } catch (Wallet.DustySendRequested e) {

                Log.e(TAG, "Dusty send rejected", e);

                runOnUi(() -> view.showToastMessage(
                        "Amount is below the allowed dust limit for this transaction."
                ));

            } catch (Exception e) {

                Log.e(TAG, "Send failed", e);

                runOnUi(() -> view.showToastMessage(
                        safeMessage(e)
                ));
            }

        }, "bitcoinj-send").start();
    }

    private void showSendConfirmation(
            String details,
            Runnable confirmAction) {

        new AlertDialog.Builder(
                view.getActivityContext()
        )
                .setTitle("Confirm transaction")
                .setMessage(details)
                .setNegativeButton("CANCEL", null)
                .setPositiveButton("SEND", (dialog, which) ->
                        confirmAction.run())
                .show();
    }

    @Override
    public void prepareWalletBackup() {

        WalletAppKit kit =
                walletAppKit;

        if (!walletReady || kit == null) {

            runOnUi(() ->
                    view.showToastMessage(
                            "Wallet chưa sẵn sàng"
                    )
            );

            return;
        }

        new Thread(() -> {

            Context.propagate(Context.getOrCreate(parameters));

            try {

                /*
                 * Force an immediate, atomic wallet save before
                 * the user exports the file.
                 *
                 * WalletAppKit auto-save normally writes the wallet
                 * every few seconds. saveToFile() guarantees that
                 * the backup starts from a complete wallet file.
                 */
                kit.wallet().saveToFile(walletFile);

                if (!walletFile.exists() ||
                        walletFile.length() == 0) {

                    throw new IOException(
                            "Wallet backup source is empty"
                    );
                }

                Log.d(
                        TAG,
                        "Wallet saved for backup: "
                                + walletFile.getAbsolutePath()
                                + " size="
                                + walletFile.length()
                );

                runOnUi(() ->
                        view.startWalletBackup(
                                Constants.WALLET_NAME
                                        + "-backup.wallet"
                        )
                );

            } catch (Exception e) {

                Log.e(
                        TAG,
                        "Wallet backup preparation failed",
                        e
                );

                runOnUi(() ->
                        view.showToastMessage(
                                "Backup wallet thất bại: "
                                        + safeMessage(e)
                        )
                );
            }

        }, "bitcoinj-wallet-backup").start();
    }


    @Override
    public void restoreWallet(final android.net.Uri backupUri) {

        if (backupUri == null) {
            runOnUi(() ->
                    view.showToastMessage("File restore không hợp lệ")
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
                    throw new IOException("Wallet đang đóng");
                }

                // Copy selected backup into app-private temporary storage.
                try (InputStream input =
                             ((android.content.Context) view)
                                     .getContentResolver()
                                     .openInputStream(backupUri);
                     FileOutputStream output =
                             new FileOutputStream(tempFile)) {

                    if (input == null) {
                        throw new IOException("Không thể đọc file backup");
                    }

                    byte[] buffer = new byte[8192];
                    int count;
                    long total = 0L;

                    while ((count = input.read(buffer)) != -1) {
                        output.write(buffer, 0, count);
                        total += count;

                        if (total > 64L * 1024L * 1024L) {
                            throw new IOException("File backup quá lớn");
                        }
                    }

                    output.flush();

                    if (total == 0L) {
                        throw new IOException("File backup rỗng");
                    }
                }

                // Validate the protobuf wallet and network before touching the live wallet.
                Wallet restoredWallet = Wallet.loadFromFile(tempFile);

                if (!restoredWallet.getParams().equals(parameters)) {
                    throw new IOException(
                            "Wallet backup khác network với ứng dụng"
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
                                "Không thể dừng wallet hiện tại để restore",
                                stopError
                        );
                    }
                }

                // Replace only after validation and shutdown.
                File backupOfCurrent =
                        new File(
                                walletDir,
                                Constants.WALLET_NAME + ".before-restore.wallet"
                        );

                if (walletFile.exists()) {
                    if (backupOfCurrent.exists() && !backupOfCurrent.delete()) {
                        throw new IOException("Không thể xoá bản before-restore cũ");
                    }

                    if (!walletFile.renameTo(backupOfCurrent)) {
                        throw new IOException("Không thể giữ lại wallet hiện tại");
                    }
                }

                if (!tempFile.renameTo(walletFile)) {
                    // Try to recover the current wallet if replacement failed.
                    if (!walletFile.exists() && backupOfCurrent.exists()) {
                        backupOfCurrent.renameTo(walletFile);
                    }
                    throw new IOException("Không thể cài wallet từ backup");
                }

                // The old wallet is no longer needed after successful replacement.
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
                                "Restore thành công. Đang khởi động lại wallet..."
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
                                "Restore wallet thất bại: "
                                        + safeMessage(e)
                        )
                );
            }

        }, "bitcoinj-wallet-restore").start();
    }


    @Override
    public void getInfoDialog() {

        WalletAppKit kit =
                walletAppKit;


        if (!walletReady ||
                kit == null) {

            return;
        }


        new Thread(() -> {

            Context.propagate(Context.getOrCreate(parameters));

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

                        String balance =
                                newBalance.toFriendlyString();

                        String currentAddress =
                                wallet1.currentReceiveAddress().toString();

                        int pageCount =
                                rebuildTransactionHistory(wallet1);

                        String history =
                                getHistoryPageText();

                        int currentPage = historyPage;

                        Log.d(
                                TAG,
                                "COINS RECEIVED: "
                                        + received.toFriendlyString()
                        );

                        runOnUi(() -> {

                            view.displayMyBalance(balance);
                            view.displayMyAddress(currentAddress);
                            view.displayTransactionHistory(history);
                            view.displayTransactionHistoryPages(currentPage, pageCount);

                            if (tx.getPurpose() ==
                                    Transaction.Purpose.UNKNOWN) {

                                view.showToastMessage(
                                        "Receive "
                                                + received.toFriendlyString()
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

                        String balance =
                                newBalance.toFriendlyString();

                        int pageCount =
                                rebuildTransactionHistory(wallet1);

                        String history =
                                getHistoryPageText();

                        int currentPage = historyPage;

                        runOnUi(() -> {

                            view.displayMyBalance(balance);
                            view.displayTransactionHistory(history);
                            view.displayTransactionHistoryPages(currentPage, pageCount);
                            view.clearAmount();
                            view.displayRecipientAddress(null);
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
    }


    /**
     * Rebuilds the complete local transaction history from bitcoinj Wallet.
     * No API, explorer or separate history database is used.
     *
     * @return number of pages required to display the complete history.
     */
    private int rebuildTransactionHistory(Wallet wallet) {

        try {

            List<Transaction> transactions =
                    wallet.getTransactionsByTime();

            List<String> items =
                    new ArrayList<>();

            SimpleDateFormat dateFormat =
                    new SimpleDateFormat(
                            "yyyy-MM-dd HH:mm:ss",
                            Locale.US
                    );

            if (transactions != null) {

                for (Transaction tx : transactions) {

                    Coin received =
                            tx.getValueSentToMe(wallet);

                    Coin sent =
                            tx.getValueSentFromMe(wallet);

                    Coin net =
                            received.minus(sent);

                    String type;
                    String amount;

                    if (net.isPositive()) {
                        type = "RECEIVED";
                        amount = "+" + net.toFriendlyString();
                    } else if (net.isNegative()) {
                        type = "SENT";
                        amount = net.toFriendlyString();
                    } else {
                        type = "TRANSACTION";
                        amount = net.toFriendlyString();
                    }

                    String txId =
                            tx.getTxId().toString();

                    String shortTxId =
                            txId.length() > 16
                                    ? txId.substring(0, 8)
                                            + "..."
                                            + txId.substring(txId.length() - 8)
                                    : txId;

                    String date = "Unknown time";

                    if (tx.updateTime().isPresent()) {
                        date = dateFormat.format(
                                Date.from(tx.updateTime().get())
                        );
                    }

                    int depth =
                            tx.getConfidence() != null
                                    ? tx.getConfidence().getDepthInBlocks()
                                    : 0;

                    String status =
                            depth > 0
                                    ? "Confirmed: " + depth + " block"
                                            + (depth == 1 ? "" : "s")
                                    : "Unconfirmed";

                    StringBuilder item =
                            new StringBuilder();

                    item.append(type)
                            .append("  ")
                            .append(amount)
                            .append("\n")
                            .append(date)
                            .append("  |  ")
                            .append(status)
                            .append("\n")
                            .append("TX: ")
                            .append(shortTxId)
                            .append("\n");

                    items.add(item.toString());
                }
            }

            historyItems = items;

            int pageCount =
                    (items.size() + HISTORY_PAGE_SIZE - 1)
                            / HISTORY_PAGE_SIZE;

            if (pageCount == 0) {
                historyPage = 0;
            } else if (historyPage >= pageCount) {
                historyPage = pageCount - 1;
            }

            return pageCount;

        } catch (Exception e) {

            Log.w(
                    TAG,
                    "Build transaction history failed",
                    e
            );

            historyItems = Collections.emptyList();
            historyPage = 0;
            return 0;
        }
    }


    private String getHistoryPageText() {

        List<String> items = historyItems;

        if (items == null || items.isEmpty()) {
            return "No transactions yet.";
        }

        int start =
                historyPage * HISTORY_PAGE_SIZE;

        int end =
                Math.min(
                        start + HISTORY_PAGE_SIZE,
                        items.size()
                );

        StringBuilder result =
                new StringBuilder();

        for (int i = start; i < end; i++) {

            result.append("#")
                    .append(i + 1)
                    .append("  ")
                    .append(items.get(i))
                    .append("\n");
        }

        return result.toString().trim();
    }


    @Override
    public void selectTransactionHistoryPage(int page) {

        int pageCount =
                (historyItems.size() + HISTORY_PAGE_SIZE - 1)
                        / HISTORY_PAGE_SIZE;

        if (pageCount == 0) {
            historyPage = 0;
            runOnUi(() -> {
                view.displayTransactionHistory("No transactions yet.");
                view.displayTransactionHistoryPages(0, 0);
            });
            return;
        }

        if (page < 0) {
            page = 0;
        }

        if (page >= pageCount) {
            page = pageCount - 1;
        }

        historyPage = page;

        final int selectedPage = historyPage;
        final String history = getHistoryPageText();

        runOnUi(() -> {
            view.displayTransactionHistory(history);
            view.displayTransactionHistoryPages(selectedPage, pageCount);
        });
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
