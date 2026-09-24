/**
 * Release documentation:
 * Release documentation for the Bitcoin send presenter.
 * Validates the recipient, amount, and fee-rate selection, creates a real bitcoinj
 * transaction, and broadcasts it directly.
 * All user-visible release text is resolved from Android string resources.
 */
package wallet.send;

import android.text.TextUtils;
import android.util.Log;

import org.bitcoinj.base.Address;
import org.bitcoinj.base.Coin;
import org.bitcoinj.core.Context;
import org.bitcoinj.core.InsufficientMoneyException;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.kits.WalletAppKit;
import org.bitcoinj.wallet.SendRequest;
import org.bitcoinj.wallet.Wallet;

import wallet.main.MainActivityPresenter;
import wallet.main.R;

public final class SendPresenter {

    private static final String TAG = "BitcoinSend";

    public static final int MIN_FEE_SAT_VB = 1;
    public static final int MAX_FEE_SAT_VB = 10;

    public interface View {
        String recipient();

        String amount();

        int feeSatVb();

        void showMessage(String message);

        void clearForm();

        void showSending(boolean sending);

        String getStringResource(int resId, Object... formatArgs);
    }

    private final View view;

    public SendPresenter(View view) {
        this.view = view;
    }

    public void send() {
        WalletAppKit walletAppKit = MainActivityPresenter.getActiveWalletAppKit();
        NetworkParameters parameters = MainActivityPresenter.getActiveParameters();

        if (walletAppKit == null || parameters == null) {
            view.showMessage(view.getStringResource(R.string.wallet_not_ready));
            return;
        }

        final String recipient = view.recipient().trim();
        final String amountText = view.amount().trim();
        final int feeSatVb = view.feeSatVb();

        if (TextUtils.isEmpty(recipient)) {
            view.showMessage(view.getStringResource(R.string.select_recipient));
            return;
        }

        if (TextUtils.isEmpty(amountText)) {
            view.showMessage(view.getStringResource(R.string.select_valid_amount));
            return;
        }

        if (feeSatVb < MIN_FEE_SAT_VB || feeSatVb > MAX_FEE_SAT_VB) {
            view.showMessage(view.getStringResource(R.string.select_valid_fee_rate));
            return;
        }

        final Coin amount;
        try {
            amount = Coin.parseCoin(amountText);
            if (!amount.isPositive()) {
                view.showMessage(view.getStringResource(R.string.select_valid_amount));
                return;
            }
        } catch (Exception error) {
            view.showMessage(view.getStringResource(R.string.select_valid_amount));
            return;
        }

        view.showSending(true);

        new Thread(() -> {
            Context.propagate(Context.getOrCreate(parameters));

            try {
                Wallet wallet = walletAppKit.wallet();
                Coin balance = wallet.getBalance();
                Address destination = Address.fromString(parameters, recipient);

                if (balance.isLessThan(amount)) {
                    view.showMessage(
                            view.getStringResource(
                                    R.string.insufficient_balance,
                                    balance.toFriendlyString()));
                    return;
                }

                SendRequest request = SendRequest.to(destination, amount);
                request.setFeePerVkb(Coin.valueOf(feeSatVb * 1000L));
                request.ensureMinRequiredFee = true;
                wallet.completeTx(request);

                Coin actualFee = request.tx.getFee();
                broadcast(walletAppKit, request, actualFee, parameters);
            } catch (InsufficientMoneyException error) {
                view.showMessage(
                        view.getStringResource(
                                R.string.insufficient_money,
                                error.missing.toFriendlyString(),
                                feeSatVb));
            } catch (Wallet.DustySendRequested error) {
                view.showMessage(view.getStringResource(R.string.dust_amount));
            } catch (Exception error) {
                Log.e(TAG, "Send preparation failed", error);
                view.showMessage(
                        view.getStringResource(
                                R.string.send_failed,
                                error.getMessage() == null
                                        ? error.getClass().getSimpleName()
                                        : error.getMessage()));
            } finally {
                view.showSending(false);
            }
        }, "bitcoinj-send").start();
    }

    private void broadcast(
            WalletAppKit walletAppKit,
            SendRequest request,
            Coin fee,
            NetworkParameters parameters) {
        Context.propagate(Context.getOrCreate(parameters));

        try {
            walletAppKit.wallet().commitTx(request.tx);
            walletAppKit.peerGroup().broadcastTransaction(request.tx).broadcast();
            view.clearForm();
            view.showMessage(
                    view.getStringResource(
                            R.string.transaction_broadcast,
                            fee.toFriendlyString()));
        } catch (Exception error) {
            Log.e(TAG, "Broadcast failed", error);
            view.showMessage(
                    view.getStringResource(
                            R.string.broadcast_failed,
                            error.getMessage() == null
                                    ? error.getClass().getSimpleName()
                                    : error.getMessage()));
        }
    }

}
