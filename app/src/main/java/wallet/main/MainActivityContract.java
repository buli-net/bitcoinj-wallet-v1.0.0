package wallet.main;

import com.example.thinkmobiles.bitcoinwalletsample.base.BasePresenter;
import com.example.thinkmobiles.bitcoinwalletsample.base.BaseView;

/**
 * Created by Lynx on 4/11/2017.
 */

public interface MainActivityContract {
    interface MainActivityView extends BaseView<MainActivityPresenter> {
        void displayDownloadContent(boolean isShown);
        void displayProgress(int percent);
        void displayPercentage(int percent);

        void displayMyBalance(String myBalance);
        void displayTransactionHistory(String history);
        void displayTransactionHistoryPages(int currentPage, int pageCount);
        void displayWalletPath(String walletPath);

        void displayMyAddress(String myAddress);
        void displayRecipientAddress(String recipientAddress);

        void showToastMessage(String message);
        String getRecipient();
        String getAmount();
        void clearAmount();
        int getFeeRateSatPerVkb();
        void displaySendDetails(String feeRate, String fee, String total, String remaining);
        android.content.Context getActivityContext();

        void startScanQR();
        void displayInfoDialog(String myAddress);
        void startWalletBackup(String suggestedFileName);
    }
    interface MainActivityPresenter extends BasePresenter {
        void refresh();
        void selectTransactionHistoryPage(int page);
        void pickRecipient();
        void send();

        void getInfoDialog();
        void prepareWalletBackup();
        void restoreWallet(android.net.Uri backupUri);
    }
    interface MainActivityModel {

    }
}
