package wallet.main;

import android.content.Context;
import android.net.Uri;

import java.util.List;

import wallet.model.TransactionItem;

/** Contract between the main screen and wallet presenter. */
public interface MainActivityContract {

    interface MainActivityView {
        void setPresenter(MainActivityPresenter presenter);
        void displayDownloadContent(boolean shown);
        void displayProgress(int percent);
        void displayPercentage(int percent);
        void displayMyBalance(String balance);
        void displayMyAddress(String address);
        void displayTransactionHistory(List<TransactionItem> historyItems);
        void showToastMessage(String message);
        Context getActivityContext();
    }

    interface MainActivityPresenter {
        void attachView(MainActivityView view);
        void subscribe();
        void unsubscribe();
        void refresh();
        void restoreWallet(Uri backupUri);
    }
}
