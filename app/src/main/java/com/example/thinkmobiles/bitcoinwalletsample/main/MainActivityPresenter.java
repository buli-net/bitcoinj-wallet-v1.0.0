package com.example.thinkmobiles.bitcoinwalletsample.main;

import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;

import com.example.thinkmobiles.bitcoinwalletsample.Constants;

import org.bitcoinj.base.Coin;
import org.bitcoinj.base.LegacyAddress;
import org.bitcoinj.core.InsufficientMoneyException;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.listeners.DownloadProgressTracker;
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

public class MainActivityPresenter
        implements MainActivityContract.MainActivityPresenter {

    private static final String TAG = "BitcoinWallet";

    /*
     * P2P settings.
     */
    private static final int MAX_CONNECTIONS = 18;

    /*
     * Nếu sync không có tiến triển trong khoảng thời gian này,
     * watchdog sẽ kiểm tra lại.
     */
    private static final long STALL_TIMEOUT_MS = 90_000L;

    /*
     * Không restart vô hạn.
     */
    private static final int MAX_AUTO_RESTARTS = 18;

    private MainActivityContract.MainActivityView view;

    private final File walletDir;
    private final File walletFile;

    private NetworkParameters parameters;

    private volatile WalletAppKit walletAppKit;
    private volatile boolean walletReady = false;
    private volatile boolean stopping = false;

    private final Handler mainHandler =
            new Handler(Looper.getMainLooper());

    private ScheduledExecutorService watchdogExecutor;

    private volatile long lastProgressTime =
            System.currentTimeMillis();

    private volatile int lastProgressPercent = -1;

    private volatile int autoRestartCount = 0;

    private final AtomicBoolean restarting =
            new AtomicBoolean(false);

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

        stopping = false;
        walletReady = false;

        setBtcSDKThread();

        parameters =
                Constants.IS_PRODUCTION
                        ? MainNetParams.get()
                        : TestNet3Params.get();

        BriefLogFormatter.init();

        startWallet();

        startWatchdog();
    }

    private void startWallet() {

        new Thread(
                this::startWalletInternal,
                "BitcoinWallet-Startup"
        ).start();
    }

    private void startWalletInternal() {

        if (stopping) {
            return;
        }

        try {

            Log.d(
                    TAG,
                    "Starting WalletAppKit"
            );

            Log.d(
                    TAG,
                    "Wallet directory = "
                            + walletDir.getAbsolutePath()
            );

            Log.d(
                    TAG,
                    "Wallet file = "
                            + walletFile.getAbsolutePath()
            );

            walletAppKit =
                    new WalletAppKit(
                            parameters,
                            walletDir,
                            Constants.WALLET_NAME
                    ) {

                        @Override
                        protected void onSetupCompleted() {

                            Log.d(
                                    TAG,
                                    "WalletAppKit setup completed"
                            );

                            Wallet wallet = wallet();

                            /*
                             * KHÔNG import ECKey ngẫu nhiên ở đây.
                             *
                             * Wallet deterministic đã có keychain riêng.
                             * Import ECKey mới có thể tạo thêm một key
                             * không cần thiết.
                             */

                            wallet.setAutoSave(
                                    true,
                                    10,
                                    TimeUnit.SECONDS
                            );

                            setupWalletListeners(wallet);

                            walletReady = true;

                            runOnUi(
                                    () -> view.displayWalletPath(
                                            walletFile.getAbsolutePath()
                                    )
                            );

                            /*
                             * QUAN TRỌNG:
                             *
                             * Không dùng freshReceiveAddress().
                             *
                             * currentReceiveAddress() giữ nguyên địa chỉ
                             * hiện tại nếu địa chỉ đó chưa được sử dụng.
                             */
                            Log.d(
                                    TAG,
                                    "Current receive address = "
                                            + wallet.currentReceiveAddress()
                            );

                            runOnUi(
                                    MainActivityPresenter.this::refresh
                            );
                        }
                    };

            walletAppKit.setDownloadListener(
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
                                            pct * 100
                                    );

                            lastProgressTime =
                                    System.currentTimeMillis();

                            lastProgressPercent =
                                    percentage;

                            Log.d(
                                    TAG,
                                    "Sync progress = "
                                            + percentage
                                            + "%, blocks="
                                            + blocksSoFar
                            );

                            runOnUi(() -> {

                                view.displayPercentage(
                                        percentage
                                );

                                view.displayProgress(
                                        percentage
                                );
                            });
                        }

                        @Override
                        protected void doneDownload() {

                            super.doneDownload();

                            lastProgressTime =
                                    System.currentTimeMillis();

                            Log.d(
                                    TAG,
                                    "Blockchain download completed"
                            );

                            runOnUi(() -> {

                                view.displayDownloadContent(
                                        false
                                );

                                refresh();
                            });
                        }
                    }
            );

            walletAppKit.setBlockingStartup(false);

            /*
             * WalletAppKit sẽ tự quản lý PeerGroup.
             */
            walletAppKit.startAsync().awaitRunning();

            Log.d(
                    TAG,
                    "WalletAppKit is RUNNING"
            );

        } catch (Exception e) {

            walletReady = false;

            Log.e(
                    TAG,
                    "WalletAppKit failed to start",
                    e
            );

            if (walletAppKit != null) {

                try {

                    Throwable cause =
                            walletAppKit.failureCause();

                    if (cause != null) {

                        Log.e(
                                TAG,
                                "WalletAppKit failure cause",
                                cause
                        );
                    }

                } catch (Exception ignored) {
                }
            }

            runOnUi(
                    () -> view.showToastMessage(
                            "Bitcoin sync error: "
                                    + e.getMessage()
                    )
            );

            scheduleRestart();
        }
    }

    @Override
    public void unsubscribe() {

        stopping = true;
        walletReady = false;

        stopWatchdog();

        new Thread(
                () -> {

                    WalletAppKit kit =
                            walletAppKit;

                    walletAppKit = null;

                    if (kit != null) {

                        try {

                            Log.d(
                                    TAG,
                                    "Stopping WalletAppKit"
                            );

                            kit.stopAsync()
                                    .awaitTerminated();

                        } catch (Exception e) {

                            Log.e(
                                    TAG,
                                    "WalletAppKit stop error",
                                    e
                            );
                        }
                    }
                },
                "BitcoinWallet-Stop"
        ).start();
    }

    @Override
    public void refresh() {

        if (!walletReady
                || walletAppKit == null
                || stopping) {

            return;
        }

        new Thread(
                () -> {

                    try {

                        Wallet wallet =
                                walletAppKit.wallet();

                        /*
                         * KHÔNG BAO GIỜ dùng freshReceiveAddress()
                         * trong refresh().
                         *
                         * freshReceiveAddress() yêu cầu wallet tạo
                         * receive address mới.
                         *
                         * currentReceiveAddress() lấy địa chỉ hiện tại.
                         */
                        String myAddress =
                                wallet.currentReceiveAddress()
                                        .toString();

                        String balance =
                                wallet.getBalance()
                                        .toFriendlyString();

                        Log.d(
                                TAG,
                                "Refresh:"
                                        + " address="
                                        + myAddress
                                        + " balance="
                                        + balance
                        );

                        runOnUi(
                                () -> {

                                    view.displayMyBalance(
                                            balance
                                    );

                                    view.displayMyAddress(
                                            myAddress
                                    );
                                }
                        );

                    } catch (Exception e) {

                        Log.e(
                                TAG,
                                "Refresh failed",
                                e
                        );
                    }

                },
                "BitcoinWallet-Refresh"
        ).start();
    }

    @Override
    public void pickRecipient() {

        view.displayRecipientAddress(null);
        view.startScanQR();
    }

    @Override
    public void send() {

        if (!walletReady
                || walletAppKit == null
                || stopping) {

            view.showToastMessage(
                    "Wallet is not ready"
            );

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

        } catch (Exception e) {

            view.showToastMessage(
                    "Select valid amount"
            );

            return;
        }

        if (coinAmount.isZero()
                || coinAmount.isNegative()) {

            view.showToastMessage(
                    "Select valid amount"
            );

            return;
        }

        new Thread(
                () -> {

                    try {

                        Wallet wallet =
                                walletAppKit.wallet();

                        if (wallet.getBalance()
                                .isLessThan(coinAmount)) {

                            runOnUi(
                                    () -> view.showToastMessage(
                                            "You got not enough coins"
                                    )
                            );

                            return;
                        }

                        LegacyAddress address =
                                LegacyAddress.fromBase58(
                                        parameters,
                                        recipientAddress
                                );

                        SendRequest request =
                                SendRequest.to(
                                        address,
                                        coinAmount
                                );

                        wallet.completeTx(request);

                        wallet.commitTx(request.tx);

                        walletAppKit
                                .peerGroup()
                                .broadcastTransaction(
                                        request.tx
                                )
                                .broadcast();

                        runOnUi(
                                () -> {

                                    view.clearAmount();

                                    view.displayRecipientAddress(
                                            null
                                    );
                                }
                        );

                    } catch (InsufficientMoneyException e) {

                        Log.e(
                                TAG,
                                "Insufficient money",
                                e
                        );

                        runOnUi(
                                () -> view.showToastMessage(
                                        e.getMessage()
                                )
                        );

                    } catch (Exception e) {

                        Log.e(
                                TAG,
                                "Send failed",
                                e
                        );

                        runOnUi(
                                () -> view.showToastMessage(
                                        "Send failed: "
                                                + e.getMessage()
                                )
                        );
                    }

                },
                "BitcoinWallet-Send"
        ).start();
    }

    @Override
    public void getInfoDialog() {

        if (!walletReady
                || walletAppKit == null
                || stopping) {

            return;
        }

        new Thread(
                () -> {

                    try {

                        String address =
                                walletAppKit
                                        .wallet()
                                        .currentReceiveAddress()
                                        .toString();

                        runOnUi(
                                () -> view.displayInfoDialog(
                                        address
                                )
                        );

                    } catch (Exception e) {

                        Log.e(
                                TAG,
                                "Cannot get wallet address",
                                e
                        );
                    }

                },
                "BitcoinWallet-Info"
        ).start();
    }

    /*
     * ============================================================
     * WALLET EVENTS
     * ============================================================
     */

    private void setupWalletListeners(
            Wallet wallet) {

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

                        /*
                         * Sau khi transaction nhận coin được wallet
                         * xử lý, hỏi lại currentReceiveAddress().
                         *
                         * Không gọi freshReceiveAddress().
                         *
                         * Nếu địa chỉ hiện tại chưa được sử dụng,
                         * nó vẫn giữ nguyên.
                         *
                         * Nếu wallet đã advance keychain sau khi
                         * nhận coin, currentReceiveAddress() sẽ trả
                         * về địa chỉ kế tiếp.
                         */
                        String currentAddress =
                                wallet1
                                        .currentReceiveAddress()
                                        .toString();

                        wallet1.saveNow();

                        Log.d(
                                TAG,
                                "COINS RECEIVED: "
                                        + received
                                                .toFriendlyString()
                        );

                        Log.d(
                                TAG,
                                "Current receive address after "
                                        + "transaction = "
                                        + currentAddress
                        );

                        runOnUi(
                                () -> {

                                    view.displayMyBalance(
                                            wallet1.getBalance()
                                                    .toFriendlyString()
                                    );

                                    view.displayMyAddress(
                                            currentAddress
                                    );

                                    if (tx.getPurpose()
                                            == Transaction.Purpose.UNKNOWN) {

                                        view.showToastMessage(
                                                "Receive "
                                                        + received
                                                        .toFriendlyString()
                                        );
                                    }
                                }
                        );

                    } catch (Exception e) {

                        Log.e(
                                TAG,
                                "Failed to process coins received",
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

                    runOnUi(
                            () -> {

                                view.displayMyBalance(
                                        wallet1.getBalance()
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
                            }
                    );
                }
        );
    }

    /*
     * ============================================================
     * WATCHDOG
     * ============================================================
     */

    private void startWatchdog() {

        stopWatchdog();

        watchdogExecutor =
                Executors.newSingleThreadScheduledExecutor(
                        r -> {

                            Thread thread =
                                    new Thread(
                                            r,
                                            "BitcoinWallet-Watchdog"
                                    );

                            thread.setDaemon(true);

                            return thread;
                        }
                );

        watchdogExecutor.scheduleWithFixedDelay(
                this::checkWalletHealth,
                30,
                30,
                TimeUnit.SECONDS
        );
    }

    private void stopWatchdog() {

        if (watchdogExecutor != null) {

            try {
                watchdogExecutor.shutdownNow();
            } catch (Exception ignored) {
            }

            watchdogExecutor = null;
        }
    }

    private void checkWalletHealth() {

        if (stopping
                || walletAppKit == null) {

            return;
        }

        try {

            if (!walletAppKit.isRunning()) {

                Log.w(
                        TAG,
                        "Watchdog: WalletAppKit is not running"
                );

                scheduleRestart();

                return;
            }

            long now =
                    System.currentTimeMillis();

            long stalledFor =
                    now - lastProgressTime;

            int percent =
                    lastProgressPercent;

            /*
             * Nếu đã hoàn tất sync thì không coi là stall.
             */
            if (percent >= 100) {
                return;
            }

            if (stalledFor >= STALL_TIMEOUT_MS) {

                Log.w(
                        TAG,
                        "Watchdog: sync stalled at "
                                + percent
                                + "% for "
                                + stalledFor
                                + " ms"
                );

                scheduleRestart();
            }

        } catch (Exception e) {

            Log.e(
                    TAG,
                    "Watchdog check failed",
                    e
            );
        }
    }

    private void scheduleRestart() {

        if (stopping) {
            return;
        }

        if (autoRestartCount >= MAX_AUTO_RESTARTS) {

            Log.e(
                    TAG,
                    "Maximum automatic restarts reached: "
                            + MAX_AUTO_RESTARTS
            );

            runOnUi(
                    () -> view.showToastMessage(
                            "Bitcoin sync stopped. "
                                    + "Please restart the app."
                    )
            );

            return;
        }

        if (!restarting.compareAndSet(false, true)) {
            return;
        }

        autoRestartCount++;

        Log.w(
                TAG,
                "Scheduling wallet restart #"
                        + autoRestartCount
        );

        new Thread(
                () -> {

                    try {

                        Thread.sleep(10_000L);

                        if (stopping) {
                            return;
                        }

                        restartWallet();

                    } catch (InterruptedException e) {

                        Thread.currentThread().interrupt();

                    } finally {

                        restarting.set(false);
                    }

                },
                "BitcoinWallet-Restart"
        ).start();
    }

    private void restartWallet() {

        WalletAppKit oldKit =
                walletAppKit;

        walletReady = false;

        if (oldKit != null) {

            try {

                Log.d(
                        TAG,
                        "Stopping old WalletAppKit before restart"
                );

                oldKit.stopAsync()
                        .awaitTerminated();

            } catch (Exception e) {

                Log.e(
                        TAG,
                        "Error stopping old WalletAppKit",
                        e
                );
            }
        }

        walletAppKit = null;

        /*
         * TUYỆT ĐỐI không xoá:
         *
         * wallet.wallet
         * wallet.spvchain
         *
         * Blockchain state được giữ lại để lần chạy sau
         * tiếp tục sync thay vì tải lại từ đầu.
         */
        lastProgressTime =
                System.currentTimeMillis();

        lastProgressPercent = -1;

        startWallet();
    }

    private void setBtcSDKThread() {

        Threading.USER_THREAD =
                mainHandler::post;
    }

    private void runOnUi(Runnable runnable) {

        if (Looper.myLooper()
                == Looper.getMainLooper()) {

            runnable.run();

        } else {

            mainHandler.post(runnable);
        }
    }
}
