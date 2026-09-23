/**
 * Release documentation:
 * Release documentation for the main wallet presentation contract.
 * Defines the view and presenter boundaries used by the main Activity and its persistent bitcoinj wallet service.
 * The presenter attachment method supports Android Activity recreation while retaining wallet state.
 */

package wallet.main;

import wallet.base.BasePresenter;
import wallet.base.BaseView;

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
        void attachView(MainActivityContract.MainActivityView newView);

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
