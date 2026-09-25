package wallet.send;

import android.text.TextUtils;
import android.util.Log;

import org.bitcoinj.base.Address;
import org.bitcoinj.base.Coin;
import org.bitcoinj.core.Context;
import org.bitcoinj.core.InsufficientMoneyException;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.TransactionConfidence;
import org.bitcoinj.core.TransactionInput;
import org.bitcoinj.core.TransactionBroadcast;
import org.bitcoinj.kits.WalletAppKit;
import org.bitcoinj.wallet.SendRequest;
import org.bitcoinj.wallet.Wallet;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import wallet.main.MainActivityPresenter;
import wallet.main.R;

/** Owns send validation, transaction preparation, review state, and broadcast state. */
public final class SendPresenter {

    private static final String TAG = "BitcoinSend";
    private static final long RBF_SEQUENCE = 0xfffffffdL;
    private static final long SAFETY_DELAY_MS = 60_000L;

    public static final int MIN_FEE_SAT_VB = 1;
    public static final int MAX_FEE_SAT_VB = 10;

    private static final AtomicBoolean SEND_IN_PROGRESS = new AtomicBoolean(false);
    private static SendPresenter activePresenter;

    private enum State {
        IDLE,
        PREPARING,
        WAITING_CONFIRMATION,
        BROADCASTING
    }

    private volatile View view;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicInteger operationId = new AtomicInteger();
    private final AtomicInteger summaryId = new AtomicInteger();
    private final AtomicInteger maxRequestId = new AtomicInteger();

    private volatile State state = State.IDLE;
    private volatile long confirmationDeadlineMs;

    private SendRequest pendingRequest;
    private Coin pendingAmount;
    private WalletAppKit pendingWalletAppKit;
    private NetworkParameters pendingParameters;
    private SendTransactionPreview pendingPreview;

    public interface View {
        String recipient();

        String amount();

        int feeSatVb();

        boolean replaceByFee();

        void showMessage(String message);

        void showPreparing(boolean preparing);

        void showReview(SendTransactionPreview preview);

        void showSending(boolean sending);

        void showBroadcastPeers(int peers);

        void showWalletBalance(Coin balance);

        void showMaxAmount(Coin amount);

        void showSummaryPending(Coin balance);

        String getStringResource(int resId, Object... formatArgs);
    }

    private SendPresenter(View view) {
        this.view = view;
    }

    public static synchronized SendPresenter getInstance(View view) {
        if (activePresenter == null) {
            activePresenter = new SendPresenter(view);
        } else {
            activePresenter.attachView(view);
        }
        return activePresenter;
    }

    public synchronized void attachView(View newView) {
        view = newView;
        renderState();
    }

    public synchronized void detachView() {
        view = null;
    }

    private void renderState() {
        View currentView = view;
        if (currentView == null) {
            return;
        }

        if (state == State.PREPARING) {
            currentView.showPreparing(true);
        } else if (state == State.WAITING_CONFIRMATION && pendingPreview != null) {
            currentView.showReview(pendingPreview);
        } else if (state == State.BROADCASTING) {
            currentView.showSending(true);
        }
    }

    public long getRemainingConfirmationMs() {
        if (state != State.WAITING_CONFIRMATION || confirmationDeadlineMs <= 0L) {
            return 0L;
        }
        return Math.max(0L, confirmationDeadlineMs - android.os.SystemClock.elapsedRealtime());
    }

    public void refreshWalletSummary() {
        maxRequestId.incrementAndGet();
        final int requestId = summaryId.incrementAndGet();
        final WalletAppKit walletAppKit = MainActivityPresenter.getActiveWalletAppKit();
        final View currentView = view;

        if (walletAppKit == null) {
            if (currentView != null && state == State.IDLE) {
                currentView.showSummaryPending(null);
            }
            return;
        }

        executor.execute(() -> {
            try {
                Coin balance = walletAppKit.wallet().getBalance();
                View target = view;
                if (requestId != summaryId.get() || target == null || state != State.IDLE) {
                    return;
                }
                target.showWalletBalance(balance);
            } catch (Exception error) {
                View target = view;
                if (requestId == summaryId.get() && target != null && state == State.IDLE) {
                    target.showSummaryPending(null);
                }
            }
        });
    }

    public void fillMax() {
        final int requestId = maxRequestId.incrementAndGet();
        final WalletAppKit walletAppKit = MainActivityPresenter.getActiveWalletAppKit();
        final NetworkParameters parameters = MainActivityPresenter.getActiveParameters();
        final View currentView = view;
        if (currentView == null) {
            return;
        }
        final String recipientText = currentView.recipient().trim();
        final int feeSatVb = currentView.feeSatVb();
        final boolean replaceByFee = currentView.replaceByFee();

        if (walletAppKit == null || parameters == null) {
            currentView.showMessage(currentView.getStringResource(R.string.wallet_not_ready));
            return;
        }
        if (TextUtils.isEmpty(recipientText)) {
            currentView.showMessage(currentView.getStringResource(R.string.select_recipient));
            return;
        }
        if (feeSatVb < MIN_FEE_SAT_VB || feeSatVb > MAX_FEE_SAT_VB) {
            currentView.showMessage(currentView.getStringResource(R.string.select_valid_fee_rate));
            return;
        }

        executor.execute(() -> {
            Context.propagate(Context.getOrCreate(parameters));
            try {
                Address destination = Address.fromString(parameters, recipientText);
                Wallet wallet = walletAppKit.wallet();
                SendRequest request = SendRequest.emptyWallet(destination);
                request.setFeePerVkb(Coin.valueOf(feeSatVb * 1000L));
                request.ensureMinRequiredFee = true;
                wallet.completeTx(request);

                if (replaceByFee) {
                    enableReplaceByFee(wallet, request);
                }

                if (request.tx.getOutputs().isEmpty()) {
                    throw new IllegalStateException(
                            currentView.getStringResource(R.string.max_amount_unavailable));
                }

                Coin maxAmount = request.tx.getOutput(0).getValue();
                View target = view;
                if (target == null || state != State.IDLE || requestId != maxRequestId.get()) {
                    return;
                }
                target.showMaxAmount(maxAmount);
            } catch (InsufficientMoneyException error) {
                View target = view;
                if (target != null) {
                    target.showMessage(
                            target.getStringResource(
                                    R.string.insufficient_balance,
                                    walletBalance(walletAppKit)));
                }
            } catch (Exception error) {
                View target = view;
                if (target != null) {
                    Log.e(TAG, "Maximum spend calculation failed", error);
                    target.showMessage(
                            target.getStringResource(
                                    R.string.max_amount_failed,
                                    error.getMessage() == null
                                            ? error.getClass().getSimpleName()
                                            : error.getMessage()));
                }
            }
        });
    }

    public void prepareSend() {
        View currentView = view;
        if (currentView == null) {
            return;
        }
        if (!SEND_IN_PROGRESS.compareAndSet(false, true)) {
            currentView.showMessage(currentView.getStringResource(R.string.send_already_pending));
            return;
        }

        final WalletAppKit walletAppKit = MainActivityPresenter.getActiveWalletAppKit();
        final NetworkParameters parameters = MainActivityPresenter.getActiveParameters();
        if (walletAppKit == null || parameters == null) {
            releaseSend();
            currentView.showMessage(currentView.getStringResource(R.string.wallet_not_ready));
            return;
        }

        final String recipient = currentView.recipient().trim();
        final String amountText = currentView.amount().trim();
        final int feeSatVb = currentView.feeSatVb();
        final boolean replaceByFee = currentView.replaceByFee();

        if (TextUtils.isEmpty(recipient)) {
            releaseSend();
            currentView.showMessage(currentView.getStringResource(R.string.select_recipient));
            return;
        }
        if (TextUtils.isEmpty(amountText)) {
            releaseSend();
            currentView.showMessage(currentView.getStringResource(R.string.select_valid_amount));
            return;
        }
        if (feeSatVb < MIN_FEE_SAT_VB || feeSatVb > MAX_FEE_SAT_VB) {
            releaseSend();
            currentView.showMessage(currentView.getStringResource(R.string.select_valid_fee_rate));
            return;
        }

        final Coin amount;
        try {
            amount = Coin.parseCoin(amountText);
            if (!amount.isPositive()) {
                releaseSend();
                currentView.showMessage(currentView.getStringResource(R.string.select_valid_amount));
                return;
            }
        } catch (Exception error) {
            releaseSend();
            currentView.showMessage(currentView.getStringResource(R.string.select_valid_amount));
            return;
        }

        final int operation = operationId.incrementAndGet();
        state = State.PREPARING;
        currentView.showPreparing(true);

        executor.execute(() -> prepareTransaction(
                operation,
                walletAppKit,
                parameters,
                recipient,
                amount,
                feeSatVb,
                replaceByFee));
    }

    private void prepareTransaction(
            int operation,
            WalletAppKit walletAppKit,
            NetworkParameters parameters,
            String recipientText,
            Coin amount,
            int feeSatVb,
            boolean replaceByFee) {
        Context.propagate(Context.getOrCreate(parameters));

        try {
            Wallet wallet = walletAppKit.wallet();
            Coin balance = wallet.getBalance(Wallet.BalanceType.AVAILABLE);
            Address destination = Address.fromString(parameters, recipientText);

            SendRequest request = SendRequest.to(destination, amount);
            request.setFeePerVkb(Coin.valueOf(feeSatVb * 1000L));
            request.ensureMinRequiredFee = true;
            wallet.completeTx(request);

            if (replaceByFee) {
                enableReplaceByFee(wallet, request);
            }

            Coin actualFee = request.tx.getFee();
            if (actualFee == null) {
                throw new IllegalStateException(
                        string(R.string.transaction_fee_unavailable));
            }

            Coin totalDebit = amount.add(actualFee);
            if (totalDebit.isGreaterThan(balance)) {
                throw new InsufficientMoneyException(totalDebit.subtract(balance));
            }

            Coin remainingBalance = balance.subtract(totalDebit);
            SendTransactionPreview preview = new SendTransactionPreview(
                    balance,
                    amount,
                    actualFee,
                    totalDebit,
                    remainingBalance,
                    feeSatVb,
                    replaceByFee,
                    recipientText);

            synchronized (this) {
                if (operation != operationId.get()
                        || state != State.PREPARING
                        || !SEND_IN_PROGRESS.get()) {
                    return;
                }
                pendingRequest = request;
                pendingAmount = amount;
                pendingWalletAppKit = walletAppKit;
                pendingParameters = parameters;
                pendingPreview = preview;
                confirmationDeadlineMs = android.os.SystemClock.elapsedRealtime() + SAFETY_DELAY_MS;
                state = State.WAITING_CONFIRMATION;
            }

            View target = view;
            if (target != null) {
                target.showPreparing(false);
                target.showReview(preview);
            }
        } catch (InsufficientMoneyException error) {
            if (operation != operationId.get() || state != State.PREPARING) {
                return;
            }
            failPreparation(operation, R.string.insufficient_balance, walletBalance(walletAppKit));
        } catch (Wallet.DustySendRequested error) {
            failPreparation(operation, R.string.dust_amount);
        } catch (Exception error) {
            if (operation != operationId.get() || state != State.PREPARING) {
                return;
            }
            Log.e(TAG, "Send preparation failed", error);
            clearPendingState();
            View target = view;
            if (target != null) {
                target.showPreparing(false);
                target.showMessage(
                        target.getStringResource(
                                R.string.send_failed,
                                error.getMessage() == null
                                        ? error.getClass().getSimpleName()
                                        : error.getMessage()));
            }
        }
    }

    private void failPreparation(int operation, int messageId, Object... args) {
        if (operation != operationId.get() || state != State.PREPARING) {
            return;
        }
        clearPendingState();
        View target = view;
        if (target != null) {
            target.showPreparing(false);
            target.showMessage(target.getStringResource(messageId, args));
        }
    }

    private void enableReplaceByFee(Wallet wallet, SendRequest request) {
        request.tx.setVersion(2);

        for (int i = 0; i < request.tx.getInputs().size(); i++) {
            TransactionInput input = request.tx.getInput(i);
            TransactionInput replacement = new TransactionInput(
                    request.tx,
                    new byte[0],
                    input.getOutpoint(),
                    RBF_SEQUENCE,
                    input.getValue(),
                    null);
            request.tx.replaceInput(i, replacement);
        }

        wallet.signTransaction(request);
        if (!request.tx.isOptInFullRBF()) {
            throw new IllegalStateException(string(R.string.rbf_enable_failed));
        }
    }

    public void confirmSend() {
        final SendRequest request;
        final WalletAppKit walletAppKit;
        final NetworkParameters parameters;
        final Coin amount;

        synchronized (this) {
            if (state != State.WAITING_CONFIRMATION) {
                return;
            }
            request = pendingRequest;
            amount = pendingAmount;
            walletAppKit = pendingWalletAppKit;
            parameters = pendingParameters;
            state = State.BROADCASTING;
        }

        if (request == null || amount == null || walletAppKit == null || parameters == null) {
            clearPendingState();
            View target = view;
            if (target != null) {
                target.showMessage(target.getStringResource(R.string.send_not_ready));
            }
            return;
        }

        View target = view;
        if (target != null) {
            target.showSending(true);
        }

        executor.execute(() -> broadcast(walletAppKit, request, amount, parameters));
    }

    public synchronized void cancelPendingSend() {
        if (state == State.BROADCASTING) {
            return;
        }
        operationId.incrementAndGet();
        clearPendingState();
    }

    public synchronized void onActivityFinished() {
        if (state == State.BROADCASTING) {
            detachView();
            return;
        }
        operationId.incrementAndGet();
        clearPendingState();
        detachView();
    }

    private void broadcast(
            WalletAppKit walletAppKit,
            SendRequest request,
            Coin amount,
            NetworkParameters parameters) {
        Context.propagate(Context.getOrCreate(parameters));

        try {
            Wallet wallet = walletAppKit.wallet();
            TransactionBroadcast broadcast;
            synchronized (wallet) {
                Coin balance = wallet.getBalance(Wallet.BalanceType.AVAILABLE);
                Coin fee = request.tx.getFee();
                if (fee == null) {
                    throw new IllegalStateException(string(R.string.transaction_fee_unavailable));
                }
                if (amount.add(fee).isGreaterThan(balance)) {
                    throw new InsufficientMoneyException(amount.add(fee).subtract(balance));
                }

                wallet.commitTx(request.tx);
                broadcast = walletAppKit.peerGroup().broadcastTransaction(request.tx);
                broadcast.broadcast();
            }

            TransactionConfidence confidence = request.tx.getConfidence();
            if (confidence != null) {
                confidence.addEventListener((tx, reason) -> {
                    if (reason == TransactionConfidence.Listener.ChangeReason.SEEN_PEERS) {
                        View target = view;
                        if (target != null) {
                            target.showBroadcastPeers(tx.getConfidence().numBroadcastPeers());
                        }
                    }
                });
            }

            View target = view;
            if (target != null) {
                target.showBroadcastPeers(confidence == null ? 0 : confidence.numBroadcastPeers());
            }

            broadcast.awaitRelayed().whenComplete((result, error) -> {
                View currentView = view;
                if (currentView == null) {
                    return;
                }
                if (error == null) {
                    currentView.showBroadcastPeers(request.tx.getConfidence().numBroadcastPeers());
                    currentView.showSending(false);
                    currentView.showMessage(
                            currentView.getStringResource(
                                    R.string.transaction_broadcast,
                                    request.tx.getFee().toFriendlyString()));
                    currentView.showWalletBalance(walletAppKit.wallet().getBalance());
                } else {
                    currentView.showSending(false);
                    currentView.showMessage(
                            currentView.getStringResource(
                                    R.string.broadcast_failed,
                                    error.getMessage() == null
                                            ? error.getClass().getSimpleName()
                                            : error.getMessage()));
                }
            });

            clearPendingState();
            target = view;
            if (target != null) {
                target.showBroadcastPeers(confidence == null ? 0 : confidence.numBroadcastPeers());
                target.showWalletBalance(walletAppKit.wallet().getBalance());
            }
        } catch (InsufficientMoneyException error) {
            clearPendingState();
            View target = view;
            if (target != null) {
                target.showSending(false);
                target.showMessage(
                        target.getStringResource(
                                R.string.insufficient_balance,
                                walletBalance(walletAppKit)));
            }
        } catch (Exception error) {
            Log.e(TAG, "Broadcast failed", error);
            clearPendingState();
            View target = view;
            if (target != null) {
                target.showSending(false);
                target.showMessage(
                        target.getStringResource(
                                R.string.broadcast_failed,
                                error.getMessage() == null
                                        ? error.getClass().getSimpleName()
                                        : error.getMessage()));
            }
        }
    }

    private synchronized void clearPendingState() {
        pendingRequest = null;
        pendingAmount = null;
        pendingWalletAppKit = null;
        pendingParameters = null;
        pendingPreview = null;
        confirmationDeadlineMs = 0L;
        state = State.IDLE;
        releaseSend();
    }

    private void releaseSend() {
        SEND_IN_PROGRESS.set(false);
    }

    private String walletBalance(WalletAppKit walletAppKit) {
        try {
            return walletAppKit.wallet().getBalance(Wallet.BalanceType.AVAILABLE).toFriendlyString();
        } catch (Exception error) {
            return string(R.string.unknown_error);
        }
    }

    private String string(int resId, Object... args) {
        View target = view;
        if (target == null) {
            return "";
        }
        return target.getStringResource(resId, args);
    }
}
