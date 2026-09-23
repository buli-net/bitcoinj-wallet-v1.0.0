/**
 * Release documentation:
 * Release documentation for the Bitcoin send presenter.
 * Validates the recipient, amount, and fee-rate selection, creates a real bitcoinj transaction, presents the calculated fee details, and broadcasts the confirmed transaction.
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

import java.util.Locale;

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
        void showConfirmation(String details, boolean feeWarning, Runnable confirm);
        void clearForm();
        void showSending(boolean sending);
        String getString(int resId, Object... formatArgs);
    }

    private final View view;

    public SendPresenter(View view) { this.view = view; }

    public void send() {
        WalletAppKit kit = MainActivityPresenter.getActiveWalletAppKit();
        NetworkParameters params = MainActivityPresenter.getActiveParameters();
        if (kit == null || params == null) {
            view.showMessage(view.getString(R.string.wallet_not_ready));
            return;
        }

        final String recipient = view.recipient().trim();
        final String amountText = view.amount().trim();
        final int feeSatVb = view.feeSatVb();
        if (TextUtils.isEmpty(recipient)) { view.showMessage(view.getString(R.string.select_recipient)); return; }
        if (TextUtils.isEmpty(amountText)) { view.showMessage(view.getString(R.string.select_valid_amount)); return; }
        if (feeSatVb < MIN_FEE_SAT_VB || feeSatVb > MAX_FEE_SAT_VB) { view.showMessage(view.getString(R.string.select_valid_fee_rate)); return; }

        final Coin amount;
        try {
            amount = Coin.parseCoin(amountText);
            if (!amount.isPositive()) { view.showMessage(view.getString(R.string.select_valid_amount)); return; }
        } catch (Exception e) {
            view.showMessage(view.getString(R.string.select_valid_amount)); return;
        }

        view.showSending(true);
        new Thread(() -> {
            Context.propagate(Context.getOrCreate(params));
            try {
                Wallet wallet = kit.wallet();
                Coin balance = wallet.getBalance();
                Address destination = Address.fromString(params, recipient);
                if (balance.isLessThan(amount)) {
                    view.showMessage(view.getString(R.string.insufficient_balance, balance.toFriendlyString()));
                    return;
                }

                SendRequest request = SendRequest.to(destination, amount);
                request.setFeePerVkb(Coin.valueOf(feeSatVb * 1000L));
                request.ensureMinRequiredFee = true;
                wallet.completeTx(request);

                final Coin actualFee = request.tx.getFee();
                final int vsize = request.tx.getVsize();
                final Coin total = amount.add(actualFee);
                final Coin remaining = balance.subtract(total);
                final Coin targetFee = Coin.valueOf((long) feeSatVb * vsize);
                final double actualRate = vsize == 0 ? 0.0 : actualFee.getValue() / (double) vsize;
                final boolean feeWarning = actualFee.isGreaterThan(targetFee);

                StringBuilder d = new StringBuilder();
                d.append(view.getString(R.string.details_recipient, recipient))
                        .append(view.getString(R.string.details_amount, amount.toFriendlyString()))
                        .append(view.getString(R.string.details_requested_fee,
                                feeSatVb, feeSatVb / 1000.0))
                        .append(view.getString(R.string.details_tx_size, vsize))
                        .append(view.getString(R.string.details_fee_details))
                        .append(view.getString(R.string.details_relay_policy))
                        .append(view.getString(R.string.details_target_fee, targetFee.toFriendlyString()))
                        .append(view.getString(R.string.details_actual_fee, actualFee.toFriendlyString()))
                        .append(view.getString(R.string.details_actual_rate, actualRate, actualRate * 1000));
                if (feeWarning) {
                    d.append(view.getString(R.string.details_fee_note));
                }
                if (remaining.isZero()) {
                    d.append(view.getString(R.string.details_full_balance));
                }
                d.append(view.getString(R.string.details_total, total.toFriendlyString()))
                        .append(view.getString(R.string.details_balance_after, remaining.toFriendlyString()));

                view.showConfirmation(d.toString(), feeWarning, () -> broadcast(kit, request, actualFee, params));
            } catch (InsufficientMoneyException e) {
                view.showMessage(view.getString(R.string.insufficient_money, e.missing.toFriendlyString(), feeSatVb));
            } catch (Wallet.DustySendRequested e) {
                view.showMessage(view.getString(R.string.dust_amount));
            } catch (Exception e) {
                Log.e(TAG, "Send preparation failed", e);
                view.showMessage(view.getString(R.string.send_failed, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
            } finally {
                view.showSending(false);
            }
        }, "bitcoinj-send").start();
    }

    private void broadcast(WalletAppKit kit, SendRequest request, Coin fee, NetworkParameters params) {
        new Thread(() -> {
            Context.propagate(Context.getOrCreate(params));
            try {
                kit.wallet().commitTx(request.tx);
                kit.peerGroup().broadcastTransaction(request.tx).broadcast();
                view.clearForm();
                view.showMessage(view.getString(R.string.transaction_broadcast, fee.toFriendlyString()));
            } catch (Exception e) {
                Log.e(TAG, "Broadcast failed", e);
                view.showMessage(view.getString(R.string.broadcast_failed, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
            }
        }, "bitcoinj-broadcast").start();
    }
}
