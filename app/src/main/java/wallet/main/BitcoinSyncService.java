package wallet.main;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.support.v4.app.NotificationCompat;

import java.io.File;

import wallet.Constants;

/**
 * Keeps the bitcoinj WalletAppKit alive independently of the Activity.
 * The Activity is only a UI client; this foreground service owns the sync lifecycle.
 */
public class BitcoinSyncService extends Service {

    private static final String CHANNEL_ID = "bitcoin_sync";
    private static final int NOTIFICATION_ID = 1001;

    public static void start(Context context) {
        Intent intent = new Intent(context.getApplicationContext(), BitcoinSyncService.class);
        Context app = context.getApplicationContext();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            app.startForegroundService(intent);
        } else {
            app.startService(intent);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification());
        ensureWalletPresenter();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        ensureWalletPresenter();
        return START_STICKY;
    }

    private void ensureWalletPresenter() {
        MainActivityPresenter presenter = MainActivityPresenter.getActivePresenter();
        if (presenter == null) {
            presenter = new MainActivityPresenter(
                    new ServiceView(getApplicationContext()),
                    getFilesDir());
        }
        presenter.subscribe();
    }

    private Notification buildNotification() {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(wallet.main.R.drawable.app_icon)
                .setContentTitle(getString(wallet.main.R.string.app_name))
                .setContentText("Bitcoin synchronization is running")
                .setOngoing(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Bitcoin synchronization",
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Keeps the Bitcoin wallet synchronized in the background.");
        manager.createNotificationChannel(channel);
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // Do not stop the service when the wallet task is removed from Recents.
        // START_STICKY keeps the sync engine alive/restartable independently of the UI task.
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        MainActivityPresenter presenter = MainActivityPresenter.getActivePresenter();
        if (presenter != null) {
            presenter.unsubscribe();
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private static final class ServiceView implements MainActivityContract.MainActivityView {
        private final Context context;

        ServiceView(Context context) {
            this.context = context.getApplicationContext();
        }

        @Override public void setPresenter(MainActivityContract.MainActivityPresenter presenter) { }
        @Override public void displayDownloadContent(boolean shown) { }
        @Override public void displayProgress(int percent) { }
        @Override public void displayPercentage(int percent) { }
        @Override public void displayMyBalance(String balance) { }
        @Override public void displayBalanceState(String available, String pending) { }
        @Override public void displayMyAddress(String address) { }
        @Override public void displayWalletType(String type) { }
        @Override public void displayTransactions(java.util.List<wallet.model.TransactionItem> transactions) { }
        @Override public void showToastMessage(String message) { }
        @Override public Context getActivityContext() { return context; }
    }
}
