package wallet.main;

import android.app.Application;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.util.Map;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;

/** Captures the previous process crash so it can be copied on the next launch. */
public class CrashApplication extends Application {

    private static final String CRASH_FILE_NAME = "bitcoin-wallet-last-crash.txt";
    private static final long ANR_THRESHOLD_MS = 3_000L;

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
        startMainThreadWatchdog();
    }


    private void startMainThreadWatchdog() {
        final Handler mainHandler = new Handler(Looper.getMainLooper());
        final long[] lastHeartbeat = {System.currentTimeMillis()};
        final boolean[] reportWritten = {false};

        final Runnable heartbeat = new Runnable() {
            @Override
            public void run() {
                lastHeartbeat[0] = System.currentTimeMillis();
                reportWritten[0] = false;
                mainHandler.postDelayed(this, 500L);
            }
        };
        mainHandler.post(heartbeat);

        Thread watchdog = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(1_000L);
                    long stalledFor = System.currentTimeMillis() - lastHeartbeat[0];
                    if (stalledFor >= ANR_THRESHOLD_MS && !reportWritten[0]) {
                        reportWritten[0] = true;
                        saveAnrReport(stalledFor);
                    }
                } catch (InterruptedException ignored) {
                    return;
                } catch (Throwable ignored) {
                    // Never interfere with the application.
                }
            }
        }, "bitcoin-wallet-anr-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
    }

    private void saveAnrReport(long stalledFor) {
        try {
            StringBuilder report = new StringBuilder();
            report.append("Bitcoin Wallet ANR report\n");
            report.append("Time: ").append(System.currentTimeMillis()).append('\n');
            report.append("App version: ").append(getPackageVersionName()).append('\n');
            report.append("Target SDK: ").append(getApplicationInfo().targetSdkVersion).append('\n');
            report.append("Android: ").append(Build.VERSION.RELEASE)
                    .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
            report.append("Device: ").append(Build.MANUFACTURER).append(' ')
                    .append(Build.MODEL).append('\n');
            report.append("Main thread unresponsive for at least: ")
                    .append(stalledFor).append(" ms\n");
            report.append("\nMain thread stack:\n");
            appendStack(report, Looper.getMainLooper().getThread().getStackTrace());

            report.append("\nAll thread stacks:\n");
            for (Map.Entry<Thread, StackTraceElement[]> entry
                    : Thread.getAllStackTraces().entrySet()) {
                Thread thread = entry.getKey();
                report.append("\n--- ").append(thread.getName())
                        .append(" (state=").append(thread.getState()).append(") ---\n");
                appendStack(report, entry.getValue());
            }

            File file = new File(getFilesDir(), CRASH_FILE_NAME);
            try (FileOutputStream output = new FileOutputStream(file, false)) {
                output.write(report.toString().getBytes(StandardCharsets.UTF_8));
                output.flush();
            }
        } catch (Throwable ignored) {
            // Never interfere with the application.
        }
    }

    private static void appendStack(StringBuilder report, StackTraceElement[] stack) {
        for (StackTraceElement element : stack) {
            report.append("    at ").append(element).append('\n');
        }
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

    private String getPackageVersionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {
            return "unknown";
        }
    }

    private void saveCrashReport(Thread thread, Throwable throwable) {
        try {
            StringWriter stack = new StringWriter();
            throwable.printStackTrace(new PrintWriter(stack));

            StringBuilder report = new StringBuilder();
            report.append("Bitcoin Wallet crash report\n");
            report.append("Time: ").append(System.currentTimeMillis()).append('\n');
            report.append("App version: ").append(getPackageVersionName()).append('\n');
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
