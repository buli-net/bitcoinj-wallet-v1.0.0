/** View and presenter contracts for the main wallet screen. */


package wallet.main;

import wallet.base.BasePresenter;
import wallet.base.BaseView;

public interface MainActivityContract {
    interface MainActivityView extends BaseView<MainActivityPresenter> {
        void displayDownloadContent(boolean isShown);
        void displayProgress(int percent);
        void displayPercentage(int percent);

        void displayMyBalance(String myBalance);
        void displayTransactionHistory(java.util.List<TransactionDisplayItem> historyItems);

        void displayMyAddress(String myAddress);

        void showToastMessage(String message);
        android.content.Context getActivityContext();
    }
    interface MainActivityPresenter extends BasePresenter {
        void attachView(MainActivityContract.MainActivityView newView);

        void refresh();
        void restoreWallet(android.net.Uri backupUri);
    }
}
