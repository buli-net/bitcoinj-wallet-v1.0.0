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
