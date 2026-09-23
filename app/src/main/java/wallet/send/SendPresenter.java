/**
 * Release documentation:
 * Release documentation for the Bitcoin send presenter.
 * Validates the recipient, amount, and fee-rate selection, creates a real bitcoinj
 * transaction, presents the calculated fee details, and broadcasts the transaction.
 * All user-visible release text is resolved from Android string resources.
 */
package wallet.send;

import android.text.TextUtils;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

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

        void showConfirmation(List<ConfirmationDisplayItem> items, boolean feeWarning, Runnable confirm);

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
                int vsize = request.tx.getVsize();
                Coin total = amount.add(actualFee);
                Coin remaining = balance.subtract(total);
                Coin targetFee = Coin.valueOf((long) feeSatVb * vsize);
                double actualRate = vsize == 0
                        ? 0.0
                        : actualFee.getValue() / (double) vsize;
                boolean feeWarning = actualFee.isGreaterThan(targetFee);

                List<ConfirmationDisplayItem> confirmationItems = new ArrayList<>();

                confirmationItems.add(new ConfirmationDisplayItem(
                        view.getStringResource(R.string.details_recipient_label),
                        recipient,
                        "",
                        false,
                        false,
                        R.drawable.ic_dialog_recipient_24dp,
                        false,
                        false));

                confirmationItems.add(new ConfirmationDisplayItem(
                        view.getStringResource(R.string.details_amount_label),
                        amount.toFriendlyString(),
                        "",
                        true,
                        false,
                        R.drawable.ic_dialog_amount_24dp,
                        false,
                        false));

                confirmationItems.add(new ConfirmationDisplayItem(
                        view.getStringResource(R.string.details_requested_fee_label),
                        view.getStringResource(
                                R.string.details_requested_fee_value,
                                targetFee.toFriendlyString(),
                                feeSatVb),
                        view.getStringResource(R.string.details_transaction_fee_title),
                        false,
                        true,
                        R.drawable.ic_dialog_fee_24dp,
                        false,
                        false));

                confirmationItems.add(new ConfirmationDisplayItem(
                        view.getStringResource(R.string.details_tx_size_label),
                        view.getStringResource(R.string.details_tx_size_value, vsize),
                        "",
                        false,
                        false,
                        0,
                        false,
                        false));

                if (feeWarning) {
                    confirmationItems.add(new ConfirmationDisplayItem(
                            view.getStringResource(R.string.details_fee_warning_title),
                            view.getStringResource(R.string.details_fee_warning_message),
                            "",
                            true,
                            true,
                            R.drawable.ic_dialog_warning_24dp,
                            false,
                            true));
                }

                confirmationItems.add(new ConfirmationDisplayItem(
                        view.getStringResource(R.string.details_fee_details),
                        "",
                        "",
                        true,
                        true,
                        R.drawable.ic_dialog_fee_details_24dp,
                        true,
                        false));

                confirmationItems.add(new ConfirmationDisplayItem(
                        view.getStringResource(R.string.details_target_fee_label),
                        targetFee.toFriendlyString(),
                        "",
                        false,
                        false,
                        0,
                        false,
                        false));

                confirmationItems.add(new ConfirmationDisplayItem(
                        view.getStringResource(R.string.details_actual_fee_label),
                        actualFee.toFriendlyString(),
                        "",
                        true,
                        false,
                        0,
                        false,
                        false));

                confirmationItems.add(new ConfirmationDisplayItem(
                        view.getStringResource(R.string.details_actual_rate_label),
                        view.getStringResource(
                                R.string.details_actual_rate_value,
                                actualRate,
                                actualRate * 1000),
                        "",
                        true,
                        false,
                        0,
                        false,
                        false));

                if (remaining.isZero()) {
                    confirmationItems.add(new ConfirmationDisplayItem(
                            "",
                            view.getStringResource(R.string.details_full_balance),
                            view.getStringResource(R.string.details_full_balance_title),
                            true,
                            true,
                            R.drawable.ic_dialog_warning_24dp,
                            false,
                            true));
                }

                confirmationItems.add(new ConfirmationDisplayItem(
                        view.getStringResource(R.string.details_total_label),
                        total.toFriendlyString(),
                        "",
                        true,
                        true,
                        R.drawable.ic_dialog_total_24dp,
                        false,
                        false));

                confirmationItems.add(new ConfirmationDisplayItem(
                        view.getStringResource(R.string.details_balance_after_label),
                        remaining.toFriendlyString(),
                        "",
                        false,
                        false,
                        R.drawable.ic_dialog_balance_24dp,
                        false,
                        false));

                view.showConfirmation(
                        confirmationItems,
                        feeWarning,
                        () -> broadcast(
                                walletAppKit,
                                request,
                                actualFee,
                                parameters));
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
        new Thread(() -> {
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
        }, "bitcoinj-broadcast").start();
    }
}
