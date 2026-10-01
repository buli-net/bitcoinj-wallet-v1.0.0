package wallet.main;

import android.app.Application;
import android.content.Context;
import android.os.Build;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;

/** Captures the previous process crash so it can be copied on the next launch. */
public class CrashApplication extends Application {

    private static final String CRASH_FILE_NAME = "bitcoin-wallet-last-crash.txt";

    @Override
    public void onCreate() {
        super.onCreate();
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            saveCrashReport(thread, throwable);
            if (previous != null) {
                previous.uncaughtException(thread, throwable);
            }
        });
    }

    public static String readPreviousCrash(Context context) {
        File file = new File(context.getFilesDir(), CRASH_FILE_NAME);
        if (!file.isFile()) {
            return null;
        }
        try {
            byte[] bytes = java.nio.file.Files.readAllBytes(file.toPath());
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            return null;
        }
    }

    public static void clearPreviousCrash(Context context) {
        File file = new File(context.getFilesDir(), CRASH_FILE_NAME);
        if (file.exists()) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }

    private void saveCrashReport(Thread thread, Throwable throwable) {
        try {
            StringWriter stack = new StringWriter();
            throwable.printStackTrace(new PrintWriter(stack));

            StringBuilder report = new StringBuilder();
            report.append("Bitcoin Wallet crash report\n");
            report.append("Time: ").append(System.currentTimeMillis()).append('\n');
            report.append("App version: ").append(BuildConfig.VERSION_NAME).append('\n');
            report.append("Target SDK: ").append(getApplicationInfo().targetSdkVersion).append('\n');
            report.append("Android: ").append(Build.VERSION.RELEASE)
                    .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
            report.append("Device: ").append(Build.MANUFACTURER).append(' ')
                    .append(Build.MODEL).append('\n');
            report.append("Thread: ").append(thread == null ? "unknown" : thread.getName()).append('\n');
            report.append("\nException:\n").append(stack);

            File file = new File(getFilesDir(), CRASH_FILE_NAME);
            try (FileOutputStream output = new FileOutputStream(file, false)) {
                output.write(report.toString().getBytes(StandardCharsets.UTF_8));
                output.flush();
            }
        } catch (Throwable ignored) {
            // Never interfere with the original crash handling.
        }
    }
}
