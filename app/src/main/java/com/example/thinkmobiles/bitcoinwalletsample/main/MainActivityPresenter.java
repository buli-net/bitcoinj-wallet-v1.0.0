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
 * Sync is deliberately kept inside WalletAppKit.  The important addition is a
 * watchdog that watches both the reported download percentage and the actual
 * chain height.  If neither changes for a while, the kit is restarted so that
 * PeerGroup can select fresh peers and continue from the existing .spvchain.
 */
public class MainActivityPresenter implements MainActivityContract.MainActivityPresenter {

    private static final String TAG = "BitcoinWalletSync";

    // Eight peers gives PeerGroup enough alternatives when one download peer stalls.
    private static final int MAX_CONNECTIONS = 8;

    // Do not restart for a short slow period.  The chain height is also checked,
    // so a healthy but slow download is not restarted while it is still moving.
    private static final long STALL_TIMEOUT_MS = 90_000L;

    // Prevent an endless restart loop if the network is genuinely unavailable.
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

    public MainActivityPresenter(MainActivityContract.MainActivityView view, File walletDir) {
        this.view = view;
        this.walletDir = walletDir;
        this.walletFile = new File(walletDir, Constants.WALLET_NAME + ".wallet");
        view.setPresenter(this);
    }

    @Override
    public void subscribe() {
        shuttingDown = false;
        setBtcSDKThread();
        parameters = Constants.IS_PRODUCTION ? MainNetParams.get() : TestNet3Params.get();
        BriefLogFormatter.init();

        runOnUi(() -> view.displayDownloadContent(true));
        startWatchdog();
        startWalletKit();
    }

    private void startWalletKit() {
        new Thread(() -> {
            if (shuttingDown) return;

            try {
                final WalletAppKit kit;

                synchronized (kitLock) {
                    if (shuttingDown) return;

                    kit = new WalletAppKit(parameters, walletDir, Constants.WALLET_NAME) {
                        @Override
                        protected void onSetupCompleted() {
                            // This hook is called after the chain, store, wallet and
                            // peer group have been created, but before peer startup.
                            try {
                                peerGroup().setMaxConnections(MAX_CONNECTIONS);
                                peerGroup().setMaxPeersToDiscoverCount(100);
                                peerGroup().setPeerDiscoveryTimeoutMillis(15_000);
                            } catch (Exception e) {
                                Log.w(TAG, "PeerGroup configuration failed", e);
                            }

                            if (wallet().getImportedKeys().size() < 1)
                                wallet().importKey(new ECKey());

                            setupWalletListeners(wallet());
                            walletReady = true;

                            int height = safeChainHeight(this);
                            lastChainHeight = height;
                            touchProgress();

                            runOnUi(() -> {
                                view.displayWalletPath(walletFile.getAbsolutePath());
                                view.displayDownloadContent(true);
                                refresh();
                            });

                            Log.d(TAG, "Wallet setup complete. chainHeight=" + height);
                            Log.d(TAG, "My address = " + wallet().freshReceiveAddress());
                        }
                    };

                    kit.setDownloadListener(new DownloadProgressTracker() {
                        @Override
                        protected void progress(double pct, int blocksSoFar, Instant date) {
                            super.progress(pct, blocksSoFar, date);

                            int percentage = (int) Math.round(pct * 100.0);
                            if (percentage < 0) percentage = 0;
                            if (percentage > 100) percentage = 100;

                            int chainHeight = safeChainHeight(kit);
                            int peerHeight = safePeerHeight(kit);
                            int peers = safePeerCount(kit);

                            lastPercent = percentage;
                            lastChainHeight = chainHeight;
                            touchProgress();

                            Log.d(TAG, "SYNC progress=" + percentage
                                    + "% blocks=" + blocksSoFar
                                    + " chain=" + chainHeight
                                    + " peerHeight=" + peerHeight
                                    + " peers=" + peers);

                            final int uiPercent = percentage;
                            runOnUi(() -> {
                                view.displayDownloadContent(true);
                                view.displayPercentage(uiPercent);
                                view.displayProgress(uiPercent);
                            });
                        }

                        @Override
                        protected void doneDownload() {
                            super.doneDownload();
                            downloadFinished = true;
                            lastPercent = 100;
                            lastProgressAt = System.currentTimeMillis();

                            Log.d(TAG, "SYNC doneDownload chain=" + safeChainHeight(kit)
                                    + " peers=" + safePeerCount(kit));

                            runOnUi(() -> {
                                view.displayPercentage(100);
                                view.displayProgress(100);
                                view.displayDownloadContent(false);
                                refresh();
                            });
                        }
                    });

                    kit.setBlockingStartup(false);
                    kit.setAutoSave(true);
                    walletAppKit = kit;
                }

                lastPercent = -1;
                lastChainHeight = safeChainHeight(kit);
                downloadFinished = false;
                touchProgress();

                Log.d(TAG, "Starting WalletAppKit; existing chain file will be reused when valid.");
                kit.startAsync().awaitRunning();
                Log.d(TAG, "WalletAppKit is running. peers=" + safePeerCount(kit));

            } catch (Exception e) {
                walletReady = false;
                Log.e(TAG, "WalletAppKit start failed", e);
                if (!shuttingDown) {
                    runOnUi(() -> view.showToastMessage("Bitcoin sync error: " + safeMessage(e)));
                    scheduleRestartAfterFailure();
                }
            }
        }, "bitcoinj-start").start();
    }

    /**
     * Watches the real chain height as well as the progress callback.  This is
     * important because the percentage callback is not a guarantee of a 0..100
     * sequence; a percentage can remain unchanged while blocks are still being
     * processed.
     */
    private void startWatchdog() {
        stopWatchdog();
        watchdog = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "bitcoinj-watchdog");
            t.setDaemon(true);
            return t;
        });

        watchdog.scheduleWithFixedDelay(() -> {
            if (shuttingDown || downloadFinished || restartInProgress.get()) return;

            WalletAppKit kit = walletAppKit;
            if (kit == null || !kit.isRunning()) return;

            int chainHeight = safeChainHeight(kit);
            int peerHeight = safePeerHeight(kit);
            int peers = safePeerCount(kit);

            // A changing chain height means the sync is healthy even if pct did
            // not change, so refresh the watchdog timestamp here.
            if (chainHeight > lastChainHeight) {
                lastChainHeight = chainHeight;
                touchProgress();
                return;
            }

            long stalledFor = System.currentTimeMillis() - lastProgressAt;
            if (stalledFor < STALL_TIMEOUT_MS) return;

            Log.w(TAG, "SYNC STALLED for " + (stalledFor / 1000)
                    + "s: percent=" + lastPercent
                    + " chain=" + chainHeight
                    + " peerHeight=" + peerHeight
                    + " peers=" + peers);

            if (autoRestartCount >= MAX_AUTO_RESTARTS) {
                Log.e(TAG, "Maximum automatic sync restarts reached; waiting for network.");
                runOnUi(() -> view.showToastMessage(
                        "Sync đang chờ mạng. Hãy giữ ứng dụng mở để tiếp tục."));
                touchProgress();
                return;
            }

            restartWalletKit("download stalled");
        }, 15, 15, TimeUnit.SECONDS);
    }

    private void scheduleRestartAfterFailure() {
        if (shuttingDown || watchdog == null) return;
        watchdog.schedule(() -> {
            if (!shuttingDown && !restartInProgress.get()) {
                restartWalletKit("startup failed");
            }
        }, 10, TimeUnit.SECONDS);
    }

    /**
     * Restart only the WalletAppKit/PeerGroup.  The .spvchain is intentionally
     * preserved so a recovered connection continues from the stored chain head
     * instead of starting from genesis again.
     */
    private void restartWalletKit(String reason) {
        if (shuttingDown || !restartInProgress.compareAndSet(false, true)) return;

        autoRestartCount++;
        walletReady = false;
        downloadFinished = false;

        Log.w(TAG, "Restarting WalletAppKit (#" + autoRestartCount + "): " + reason);
        runOnUi(() -> {
            view.displayDownloadContent(true);
            view.showToastMessage("Kết nối blockchain bị chậm, đang kết nối lại...");
        });

        new Thread(() -> {
            try {
                WalletAppKit oldKit;
                synchronized (kitLock) {
                    oldKit = walletAppKit;
                    walletAppKit = null;
                }

                if (oldKit != null) {
                    try {
                        oldKit.stopAsync().awaitTerminated();
                    } catch (Exception stopError) {
                        Log.w(TAG, "Error stopping stalled WalletAppKit", stopError);
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
        lastProgressAt = System.currentTimeMillis();
    }

    private int safeChainHeight(WalletAppKit kit) {
        try {
            return kit.chain() == null ? 0 : kit.chain().getBestChainHeight();
        } catch (Exception e) {
            return 0;
        }
    }

    private int safePeerHeight(WalletAppKit kit) {
        try {
            PeerGroup peers = kit.peerGroup();
            return peers == null ? 0 : peers.getMostCommonChainHeight();
        } catch (Exception e) {
            return 0;
        }
    }

    private int safePeerCount(WalletAppKit kit) {
        try {
            PeerGroup peers = kit.peerGroup();
            return peers == null ? 0 : peers.getConnectedPeers().size();
        } catch (Exception e) {
            return 0;
        }
    }

    private String safeMessage(Exception e) {
        String message = e.getMessage();
        return TextUtils.isEmpty(message) ? e.getClass().getSimpleName() : message;
    }

    @Override
    public void unsubscribe() {
        shuttingDown = true;
        walletReady = false;
        stopWatchdog();

        new Thread(() -> {
            WalletAppKit kit;
            synchronized (kitLock) {
                kit = walletAppKit;
                walletAppKit = null;
            }

            try {
                if (kit != null)
                    kit.stopAsync().awaitTerminated();
            } catch (Exception e) {
                Log.w(TAG, "Error stopping WalletAppKit", e);
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
        if (!walletReady || walletAppKit == null) return;
        new Thread(() -> {
            try {
                Wallet w = walletAppKit.wallet();
                String myAddress = w.freshReceiveAddress().toString();
                runOnUi(() -> {
                    view.displayMyBalance(w.getBalance().toFriendlyString());
                    view.displayMyAddress(myAddress);
                });
            } catch (Exception e) {
                Log.w(TAG, "Refresh failed", e);
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
        if (!walletReady || walletAppKit == null) return;

        final String recipientAddress = view.getRecipient();
        final String amount = view.getAmount();

        if (TextUtils.isEmpty(recipientAddress) || recipientAddress.equals("Scan recipient QR")) {
            view.showToastMessage("Select recipient");
            return;
        }
        if (TextUtils.isEmpty(amount) || Double.parseDouble(amount) <= 0) {
            view.showToastMessage("Select valid amount");
            return;
        }

        new Thread(() -> {
            try {
                Wallet w = walletAppKit.wallet();
                Coin coinAmount = Coin.parseCoin(amount);

                if (w.getBalance().isLessThan(coinAmount)) {
                    runOnUi(() -> view.showToastMessage("You got not enough coins"));
                    return;
                }

                SendRequest request = SendRequest.to(
                        LegacyAddress.fromBase58(parameters, recipientAddress),
                        coinAmount
                );

                w.completeTx(request);
                w.commitTx(request.tx);
                walletAppKit.peerGroup().broadcastTransaction(request.tx).broadcast();

                runOnUi(() -> {
                    view.clearAmount();
                    view.displayRecipientAddress(null);
                });
            } catch (InsufficientMoneyException e) {
                Log.e(TAG, "Send failed", e);
                runOnUi(() -> view.showToastMessage(e.getMessage()));
            } catch (Exception e) {
                Log.e(TAG, "Send failed", e);
                runOnUi(() -> view.showToastMessage(safeMessage(e)));
            }
        }, "bitcoinj-send").start();
    }

    @Override
    public void getInfoDialog() {
        if (!walletReady || walletAppKit == null) return;
        new Thread(() -> {
            try {
                String addr = walletAppKit.wallet().currentReceiveAddress().toString();
                runOnUi(() -> view.displayInfoDialog(addr));
            } catch (Exception e) {
                Log.w(TAG, "Info failed", e);
            }
        }, "bitcoinj-info").start();
    }

    private void setBtcSDKThread() {
        Threading.USER_THREAD = mainHandler::post;
    }

    private void setupWalletListeners(Wallet wallet) {
        wallet.addCoinsReceivedEventListener((wallet1, tx, prevBalance, newBalance) -> {
            runOnUi(() -> {
                view.displayMyBalance(wallet.getBalance().toFriendlyString());
                if (tx.getPurpose() == Transaction.Purpose.UNKNOWN)
                    view.showToastMessage("Receive " + newBalance.minus(prevBalance).toFriendlyString());
            });
        });

        wallet.addCoinsSentEventListener((wallet12, tx, prevBalance, newBalance) -> {
            runOnUi(() -> {
                view.displayMyBalance(wallet.getBalance().toFriendlyString());
                view.clearAmount();
                view.displayRecipientAddress(null);
                view.showToastMessage("Sent " + prevBalance.minus(newBalance).minus(tx.getFee()).toFriendlyString());
            });
        });
    }

    private void runOnUi(Runnable r) {
        if (Looper.myLooper() == Looper.getMainLooper()) r.run();
        else mainHandler.post(r);
    }
}
