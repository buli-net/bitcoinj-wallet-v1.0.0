/**
 * Release documentation:
 * Release documentation for the main wallet presenter.
 * Owns the persistent WalletAppKit lifecycle, blockchain synchronization state, wallet balance and address presentation, transaction history, send preparation, backup preparation, and restore operations.
 * The presenter survives normal Activity recreation inside the process and reattaches to the newly created view.
 * All user-visible release copy is resolved from Android string resources.
 */

package wallet.main;

import android.os.Handler;
import android.os.Looper;
import android.support.v7.app.AlertDialog;
import android.text.TextUtils;
import android.util.Log;

import wallet.Constants;

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

    private static volatile MainActivityPresenter activePresenter;

    private static final String TAG = "BitcoinWalletSync";

    
    private static final int MAX_CONNECTIONS = 8;

    
    private static final long STALL_TIMEOUT_MS = 90_000L;

    
    private static final int MAX_AUTO_RESTARTS = 8;

    
    private static final int MIN_FEE_RATE_SAT_PER_VKB = 1000;
    private static final int MAX_FEE_RATE_SAT_PER_VKB = 10000;

    
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

    private volatile boolean startupInProgress = false;

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
                                    parameters,
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

                
                kit.startAsync();

                
                kit.awaitRunning();

                startupInProgress = false;

                Log.d(
                        TAG,
                        "WalletAppKit is RUNNING. "
                                + "peers="
                                + safePeerCount(kit)
                                + ", chainHeight="
                                + safeChainHeight(kit)
                );

            } catch (Exception e) {

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
                                failureCause,
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
                || recipientAddress.equals(text(R.string.scan_recipient_qr))) {
            view.showToastMessage(text(R.string.select_recipient));
            return;
        }

        if (TextUtils.isEmpty(amount)) {
            view.showToastMessage(text(R.string.select_valid_amount));
            return;
        }

        if (feeRateSatPerVkb < MIN_FEE_RATE_SAT_PER_VKB
                || feeRateSatPerVkb > MAX_FEE_RATE_SAT_PER_VKB) {
            view.showToastMessage(text(R.string.select_valid_fee_rate));
            return;
        }

        final Coin coinAmount;

        try {
            coinAmount = Coin.parseCoin(amount);

            if (coinAmount.isZero() || coinAmount.isNegative()) {
                view.showToastMessage(text(R.string.select_valid_amount));
                return;
            }
        } catch (Exception e) {
            view.showToastMessage(text(R.string.select_valid_amount));
            return;
        }

        new Thread(() -> {

            Context.propagate(Context.getOrCreate(parameters));

            try {

                Wallet w = kit.wallet();

                if (w.getBalance().isLessThan(coinAmount)) {
                    runOnUi(() -> view.showToastMessage(
                            text(R.string.insufficient_balance,
                                    w.getBalance().toFriendlyString())));
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

                
                request.setFeePerVkb(
                        Coin.valueOf(feeRateSatPerVkb)
                );

                
                request.ensureMinRequiredFee = true;

                w.completeTx(request);

                final Coin actualFee = request.tx.getFee();
                final Coin total = coinAmount.add(actualFee);
                final Coin balanceAfter = w.getBalance().minus(total);
                final int txSize = request.tx.getVsize();

                
                final Coin targetFee = Coin.valueOf(
                        ((long) feeRateSatPerVkb * txSize + 999L) / 1000L
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
                        actualFee.isGreaterThan(
                                targetFee.add(Coin.SATOSHI)
                        );

                final StringBuilder detailsBuilder =
                        new StringBuilder();

                detailsBuilder
                        .append(text(R.string.details_recipient, recipientAddress))
                        .append(text(R.string.details_amount, coinAmount.toFriendlyString()))
                        .append(text(R.string.details_requested_fee,
                                feeRateSatPerVkb,
                                feeRateSatPerVkb / 1000.0))
                        .append(text(R.string.details_tx_size, txSize))
                        .append(text(R.string.details_fee_details))
                        .append(text(R.string.details_target_fee, targetFee.toFriendlyString()))
                        .append(text(R.string.details_actual_fee, actualFee.toFriendlyString()))
                        .append(text(R.string.details_actual_rate,
                                actualSatPerVb,
                                actualSatPerVb * 1000.0));

                if (feeAboveTarget) {
                    detailsBuilder
                            .append(text(R.string.details_extra_fee, extraFee.toFriendlyString()))
                            .append(text(R.string.details_fee_note));
                }

                if (balanceAfter.isZero()) {
                    detailsBuilder.append(text(R.string.details_full_balance));
                }

                detailsBuilder
                        .append(text(R.string.details_total, total.toFriendlyString()))
                        .append(text(R.string.details_balance_after, balanceAfter.toFriendlyString()));

                final String details = detailsBuilder.toString();

                runOnUi(() -> {
                    view.displaySendDetails(
                            text(R.string.fee_rate_sat_vkb, feeRateSatPerVkb),
                            text(R.string.fee_actual_display, actualFee.toFriendlyString(), actualSatPerVb),
                            total.toFriendlyString(),
                            balanceAfter.toFriendlyString()
                    );

                    showSendConfirmation(
                            details,
                            feeAboveTarget,
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
                                                text(R.string.transaction_broadcast,
                                                        actualFee.toFriendlyString())
                                        );
                                    });
                                } catch (Exception e) {
                                    Log.e(TAG, "Broadcast failed", e);
                                    runOnUi(() -> view.showToastMessage(
                                            text(R.string.broadcast_failed, safeMessage(e))
                                    ));
                                }
                            }, "bitcoinj-broadcast").start();
                        }
                    );
                });

            } catch (InsufficientMoneyException e) {

                Log.e(TAG, "Send failed", e);

                runOnUi(() -> view.showToastMessage(
                        text(R.string.insufficient_money,
                                e.missing.toFriendlyString(),
                                feeRateSatPerVkb)
                ));

            } catch (Wallet.DustySendRequested e) {

                Log.e(TAG, "Dusty send rejected", e);

                runOnUi(() -> view.showToastMessage(
                        text(R.string.dust_amount)
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
            boolean feeWarning,
            Runnable confirmAction) {

        final int title = feeWarning
                ? R.string.confirmation_warning_title
                : R.string.confirmation_title;

        final int positiveButton = feeWarning
                ? R.string.send_anyway
                : R.string.send_action;

        new AlertDialog.Builder(
                view.getActivityContext()
        )
                .setTitle(title)
                .setMessage(details)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(positiveButton, (dialog, which) ->
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
                            text(R.string.wallet_not_ready)
                    )
            );

            return;
        }

        new Thread(() -> {

            Context.propagate(Context.getOrCreate(parameters));

            try {

                
                kit.wallet().saveToFile(walletFile);

                if (!walletFile.exists() ||
                        walletFile.length() == 0) {

                    throw new IOException(
                            text(R.string.backup_source_empty)
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
                                text(R.string.backup_title_file, Constants.WALLET_NAME)
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
                                text(R.string.backup_preparation_failed, safeMessage(e))
                        )
                );
            }

        }, "bitcoinj-wallet-backup").start();
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
                        type = text(R.string.transaction_received);
                        amount = "+" + net.toFriendlyString();
                    } else if (net.isNegative()) {
                        type = text(R.string.transaction_sent);
                        amount = net.toFriendlyString();
                    } else {
                        type = text(R.string.transaction_generic);
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

                    String date = text(R.string.unknown_time);

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
                                    ? (depth == 1
                                            ? text(R.string.confirmed_one, depth)
                                            : text(R.string.confirmed_many, depth))
                                    : text(R.string.unconfirmed);

                    items.add(text(
                            R.string.history_item,
                            type,
                            amount,
                            date,
                            status,
                            shortTxId
                    ));
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
            return text(R.string.no_transactions_yet);
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

            result.append(text(
                    R.string.history_page_item,
                    i + 1,
                    items.get(i)
            ));
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
                view.displayTransactionHistory(text(R.string.no_transactions_yet));
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
