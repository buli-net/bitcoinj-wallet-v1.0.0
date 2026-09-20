package com.example.thinkmobiles.bitcoinwalletsample.main;

import android.os.Handler;
import android.os.Looper;
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


        if (!walletReady ||
                kit == null) {

            return;
        }


        final String recipientAddress =
                view.getRecipient();


        final String amount =
                view.getAmount();


        if (TextUtils.isEmpty(recipientAddress)) {

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

            Context.propagate(Context.getOrCreate(parameters));

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
                                Address.fromString(
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


            } catch (
                    InsufficientMoneyException e) {

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

        /*
         * RECEIVE
         *
         * IMPORTANT: do all bitcoinj Wallet/Transaction reads before
         * switching to the Android main thread.  The main thread does
         * not necessarily have a bitcoinj Context.
         */
        wallet.addCoinsReceivedEventListener(
                (wallet1,
                 tx,
                 prevBalance,
                 newBalance) -> {

                    try {

                        Coin received =
                                newBalance.minus(
                                        prevBalance
                                );

                        String balance =
                                newBalance.toFriendlyString();

                        String currentAddress =
                                wallet1
                                        .currentReceiveAddress()
                                        .toString();

                        Transaction.Purpose purpose =
                                tx.getPurpose();

                        Log.d(
                                TAG,
                                "COINS RECEIVED: "
                                        + received.toFriendlyString()
                        );

                        Log.d(
                                TAG,
                                "Balance after receive = "
                                        + balance
                        );

                        Log.d(
                                TAG,
                                "Current receive address after transaction = "
                                        + currentAddress
                        );

                        /*
                         * Only plain values are passed to the UI thread.
                         */
                        runOnUi(() -> {

                            view.displayMyBalance(
                                    balance
                            );

                            /*
                             * Do NOT call freshReceiveAddress().
                             * bitcoinj has already advanced the current
                             * receive key when appropriate.
                             */
                            view.displayMyAddress(
                                    currentAddress
                            );

                            if (purpose ==
                                    Transaction.Purpose.UNKNOWN) {

                                view.showToastMessage(
                                        "Receive "
                                                + received
                                                .toFriendlyString()
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


        /*
         * SEND
         */
        wallet.addCoinsSentEventListener(
                (wallet1,
                 tx,
                 prevBalance,
                 newBalance) -> {

                    try {

                        String balance =
                                newBalance.toFriendlyString();

                        Coin fee = tx.getFee();

                        Coin sent =
                                prevBalance
                                        .minus(newBalance)
                                        .minus(fee == null
                                                ? Coin.ZERO
                                                : fee);

                        String sentAmount =
                                sent.toFriendlyString();

                        runOnUi(() -> {

                            view.displayMyBalance(
                                    balance
                            );

                            view.clearAmount();

                            view.displayRecipientAddress(
                                    null
                            );

                            view.showToastMessage(
                                    "Sent "
                                            + sentAmount
                            );
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
