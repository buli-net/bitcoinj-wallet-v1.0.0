package wallet.send;

import android.text.TextUtils;
import android.util.Log;

import org.bitcoinj.base.Address;
import org.bitcoinj.base.Coin;
import org.bitcoinj.core.Context;
import org.bitcoinj.core.InsufficientMoneyException;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.TransactionInput;
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

    public static final int MIN_FEE_SAT_VB = 1;
    public static final int MAX_FEE_SAT_VB = 10;

    private static final AtomicBoolean SEND_IN_PROGRESS = new AtomicBoolean(false);

    private enum State {
        IDLE,
        PREPARING,
        WAITING_CONFIRMATION,
        BROADCASTING
    }

    private final View view;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicInteger operationId = new AtomicInteger();
    private final AtomicInteger summaryId = new AtomicInteger();
    private final AtomicInteger maxRequestId = new AtomicInteger();

    private volatile State state = State.IDLE;
    private volatile boolean viewActive = true;

    private SendRequest pendingRequest;
    private Coin pendingAmount;
    private WalletAppKit pendingWalletAppKit;
    private NetworkParameters pendingParameters;

    public interface View {
        String recipient();

        String amount();

        int feeSatVb();

        boolean replaceByFee();

        void showMessage(String message);

        void showPreparing(boolean preparing);

        void showReview(SendTransactionPreview preview);

        void showSending(boolean sending);

        void showWalletBalance(Coin balance);

        void showMaxAmount(Coin amount);

        void showSummaryPending(Coin balance);

        String getStringResource(int resId, Object... formatArgs);
    }

    public SendPresenter(View view) {
        this.view = view;
    }

    public void refreshWalletSummary() {
        maxRequestId.incrementAndGet();
        final int requestId = summaryId.incrementAndGet();
        final WalletAppKit walletAppKit = MainActivityPresenter.getActiveWalletAppKit();

        if (walletAppKit == null) {
            if (viewActive) {
                view.showSummaryPending(null);
            }
            return;
        }

        executor.execute(() -> {
            try {
                Coin balance = walletAppKit.wallet().getBalance();
                if (requestId != summaryId.get() || !viewActive || state != State.IDLE) {
                    return;
                }
                view.showWalletBalance(balance);
            } catch (Exception error) {
                if (requestId == summaryId.get() && viewActive && state == State.IDLE) {
                    view.showSummaryPending(null);
                }
            }
        });
    }

    public void fillMax() {
        final int requestId = maxRequestId.incrementAndGet();
        final WalletAppKit walletAppKit = MainActivityPresenter.getActiveWalletAppKit();
        final NetworkParameters parameters = MainActivityPresenter.getActiveParameters();
        final String recipientText = view.recipient().trim();
        final int feeSatVb = view.feeSatVb();
        final boolean replaceByFee = view.replaceByFee();

        if (walletAppKit == null || parameters == null) {
            view.showMessage(view.getStringResource(R.string.wallet_not_ready));
            return;
        }
        if (TextUtils.isEmpty(recipientText)) {
            view.showMessage(view.getStringResource(R.string.select_recipient));
            return;
        }
        if (feeSatVb < MIN_FEE_SAT_VB || feeSatVb > MAX_FEE_SAT_VB) {
            view.showMessage(view.getStringResource(R.string.select_valid_fee_rate));
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
                            view.getStringResource(R.string.max_amount_unavailable));
                }

                Coin maxAmount = request.tx.getOutput(0).getValue();
                if (!viewActive
                        || state != State.IDLE
                        || requestId != maxRequestId.get()) {
                    return;
                }
                view.showMaxAmount(maxAmount);
            } catch (InsufficientMoneyException error) {
                if (viewActive) {
                    view.showMessage(
                            view.getStringResource(
                                    R.string.insufficient_balance,
                                    walletBalance(walletAppKit)));
                }
            } catch (Exception error) {
                if (viewActive) {
                    Log.e(TAG, "Maximum spend calculation failed", error);
                    view.showMessage(
                            view.getStringResource(
                                    R.string.max_amount_failed,
                                    error.getMessage() == null
                                            ? error.getClass().getSimpleName()
                                            : error.getMessage()));
                }
            }
        });
    }

    public void prepareSend() {
        if (!SEND_IN_PROGRESS.compareAndSet(false, true)) {
            view.showMessage(view.getStringResource(R.string.send_already_pending));
            return;
        }

        final WalletAppKit walletAppKit = MainActivityPresenter.getActiveWalletAppKit();
        final NetworkParameters parameters = MainActivityPresenter.getActiveParameters();
        if (walletAppKit == null || parameters == null) {
            releaseSend();
            view.showMessage(view.getStringResource(R.string.wallet_not_ready));
            return;
        }

        final String recipient = view.recipient().trim();
        final String amountText = view.amount().trim();
        final int feeSatVb = view.feeSatVb();
        final boolean replaceByFee = view.replaceByFee();

        if (TextUtils.isEmpty(recipient)) {
            releaseSend();
            view.showMessage(view.getStringResource(R.string.select_recipient));
            return;
        }
        if (TextUtils.isEmpty(amountText)) {
            releaseSend();
            view.showMessage(view.getStringResource(R.string.select_valid_amount));
            return;
        }
        if (feeSatVb < MIN_FEE_SAT_VB || feeSatVb > MAX_FEE_SAT_VB) {
            releaseSend();
            view.showMessage(view.getStringResource(R.string.select_valid_fee_rate));
            return;
        }

        final Coin amount;
        try {
            amount = Coin.parseCoin(amountText);
            if (!amount.isPositive()) {
                releaseSend();
                view.showMessage(view.getStringResource(R.string.select_valid_amount));
                return;
            }
        } catch (Exception error) {
            releaseSend();
            view.showMessage(view.getStringResource(R.string.select_valid_amount));
            return;
        }

        final int operation = operationId.incrementAndGet();
        state = State.PREPARING;
        view.showPreparing(true);

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
            Coin balance = wallet.getBalance();
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
                        view.getStringResource(R.string.transaction_fee_unavailable));
            }

            Coin totalDebit = amount.add(actualFee);
            if (totalDebit.isGreaterThan(balance)) {
                throw new InsufficientMoneyException(totalDebit.subtract(balance));
            }

            Coin remainingBalance = balance.subtract(totalDebit);

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
                state = State.WAITING_CONFIRMATION;
            }

            if (!viewActive) {
                return;
            }

            SendTransactionPreview preview = new SendTransactionPreview(
                    balance,
                    amount,
                    actualFee,
                    totalDebit,
                    remainingBalance,
                    feeSatVb,
                    replaceByFee,
                    recipientText);

            view.showPreparing(false);
            view.showReview(preview);
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
            if (viewActive) {
                view.showPreparing(false);
                view.showMessage(
                        view.getStringResource(
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
        if (viewActive) {
            view.showPreparing(false);
            view.showMessage(view.getStringResource(messageId, args));
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
            throw new IllegalStateException(
                    view.getStringResource(R.string.rbf_enable_failed));
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
            if (viewActive) {
                view.showMessage(view.getStringResource(R.string.send_not_ready));
            }
            return;
        }

        if (viewActive) {
            view.showSending(true);
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

    public synchronized void onViewDestroyed() {
        viewActive = false;
        if (state != State.BROADCASTING) {
            operationId.incrementAndGet();
            clearPendingState();
            executor.shutdownNow();
        }
    }

    private void broadcast(
            WalletAppKit walletAppKit,
            SendRequest request,
            Coin amount,
            NetworkParameters parameters) {
        Context.propagate(Context.getOrCreate(parameters));

        try {
            Wallet wallet = walletAppKit.wallet();
            synchronized (wallet) {
                Coin balance = wallet.getBalance();
                Coin fee = request.tx.getFee();
                if (fee == null) {
                    throw new IllegalStateException(
                            view.getStringResource(R.string.transaction_fee_unavailable));
                }
                if (amount.add(fee).isGreaterThan(balance)) {
                    throw new InsufficientMoneyException(amount.add(fee).subtract(balance));
                }

                wallet.commitTx(request.tx);
                walletAppKit.peerGroup().broadcastTransaction(request.tx).broadcast();
            }

            clearPendingState();
            if (viewActive) {
                view.showSending(false);
                view.showMessage(
                        view.getStringResource(
                                R.string.transaction_broadcast,
                                request.tx.getFee().toFriendlyString()));
                view.showWalletBalance(walletAppKit.wallet().getBalance());
            }
        } catch (InsufficientMoneyException error) {
            clearPendingState();
            if (viewActive) {
                view.showSending(false);
                view.showMessage(
                        view.getStringResource(
                                R.string.insufficient_balance,
                                walletBalance(walletAppKit)));
            }
        } catch (Exception error) {
            Log.e(TAG, "Broadcast failed", error);
            clearPendingState();
            if (viewActive) {
                view.showSending(false);
                view.showMessage(
                        view.getStringResource(
                                R.string.broadcast_failed,
                                error.getMessage() == null
                                        ? error.getClass().getSimpleName()
                                        : error.getMessage()));
            }
        } finally {
            if (!viewActive) {
                executor.shutdown();
            }
        }
    }

    private synchronized void clearPendingState() {
        pendingRequest = null;
        pendingAmount = null;
        pendingWalletAppKit = null;
        pendingParameters = null;
        state = State.IDLE;
        releaseSend();
    }

    private void releaseSend() {
        SEND_IN_PROGRESS.set(false);
    }

    private String walletBalance(WalletAppKit walletAppKit) {
        try {
            return walletAppKit.wallet().getBalance().toFriendlyString();
        } catch (Exception error) {
            return view.getStringResource(R.string.unknown_error);
        }
    }
}
