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
    }

    private final View view;

    public SendPresenter(View view) { this.view = view; }

    public void send() {
        WalletAppKit kit = MainActivityPresenter.getActiveWalletAppKit();
        NetworkParameters params = MainActivityPresenter.getActiveParameters();
        if (kit == null || params == null) {
            view.showMessage("Wallet chưa sẵn sàng");
            return;
        }

        final String recipient = view.recipient().trim();
        final String amountText = view.amount().trim();
        final int feeSatVb = view.feeSatVb();
        if (TextUtils.isEmpty(recipient)) { view.showMessage("Nhập địa chỉ nhận"); return; }
        if (TextUtils.isEmpty(amountText)) { view.showMessage("Nhập số BTC"); return; }
        if (feeSatVb < MIN_FEE_SAT_VB || feeSatVb > MAX_FEE_SAT_VB) { view.showMessage("Fee rate không hợp lệ"); return; }

        final Coin amount;
        try {
            amount = Coin.parseCoin(amountText);
            if (!amount.isPositive()) { view.showMessage("Số BTC không hợp lệ"); return; }
        } catch (Exception e) {
            view.showMessage("Số BTC không hợp lệ"); return;
        }

        view.showSending(true);
        new Thread(() -> {
            Context.propagate(Context.getOrCreate(params));
            try {
                Wallet wallet = kit.wallet();
                Coin balance = wallet.getBalance();
                Address destination = Address.fromString(params, recipient);
                if (balance.isLessThan(amount)) {
                    view.showMessage("Không đủ số dư. Có: " + balance.toFriendlyString());
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
                d.append("Recipient\n").append(recipient)
                        .append("\n\nAmount\n").append(amount.toFriendlyString())
                        .append("\n\nFee rate (requested)\n")
                        .append(feeSatVb).append(" sat/vB (" ).append(feeSatVb * 1000).append(" sat/vkB)")
                        .append("\n\nTransaction size\n").append(vsize).append(" vbytes")
                        .append("\n\nFee details\n")
                        .append("Minimum relay/dust rules are enforced by bitcoinj.")
                        .append("\nActual fee: ").append(actualFee.toFriendlyString())
                        .append("\nFee rate (actual): ").append(String.format(Locale.US, "%.2f", actualRate)).append(" sat/vB (" )
                        .append(String.format(Locale.US, "%.0f", actualRate * 1000)).append(" sat/vkB)")
                        .append("\n\nTotal\nAmount + Fee: ").append(total.toFriendlyString())
                        .append("\nEstimated balance after: ").append(remaining.toFriendlyString());
                if (feeWarning) {
                    d.append("\n\nNote\nThe actual fee is higher than the requested fee rate because bitcoinj adjusted the transaction to satisfy network minimum/dust rules. The final fee shown above is the amount that will be paid.");
                }
                if (remaining.isZero()) {
                    d.append("\n\nWarning\nThis transaction spends the entire available balance.");
                }

                view.showConfirmation(d.toString(), feeWarning, () -> broadcast(kit, request, actualFee, params));
            } catch (InsufficientMoneyException e) {
                view.showMessage("Không đủ tiền. Thiếu: " + e.missing.toFriendlyString());
            } catch (Wallet.DustySendRequested e) {
                view.showMessage("Giao dịch bị từ chối vì output/change là dust.");
            } catch (Exception e) {
                Log.e(TAG, "Send preparation failed", e);
                view.showMessage(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
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
                view.showMessage("Transaction broadcast. Fee: " + fee.toFriendlyString());
            } catch (Exception e) {
                Log.e(TAG, "Broadcast failed", e);
                view.showMessage("Broadcast failed: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
            }
        }, "bitcoinj-broadcast").start();
    }
}
