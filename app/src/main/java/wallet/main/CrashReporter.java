package wallet.main;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Temporary crash capture for diagnosing startup and sync failures. */
public class CrashReporter extends Application {
    static final String CRASH_FILE_NAME = "bitcoin-wallet-crash.txt";

    @Override
    public void onCreate() {
        super.onCreate();
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            writeCrashReport(thread, throwable);
            try {
                Intent intent = new Intent(this, CrashLogActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                startActivity(intent);
            } catch (Exception ignored) {
                // Keep the report on disk even if the diagnostic screen cannot start.
            }
            // Do not invoke the previous handler: the diagnostic activity needs to remain visible.
        });
    }

    static File getCrashFile(Context context) {
        return new File(context.getFilesDir(), CRASH_FILE_NAME);
    }

    static String readCrashReport(Context context) {
        File file = getCrashFile(context);
        if (!file.isFile()) {
            return "";
        }
        try {
            java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
            try (java.io.FileInputStream input = new java.io.FileInputStream(file)) {
                byte[] buffer = new byte[4096];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    output.write(buffer, 0, count);
                }
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception error) {
            return "Unable to read crash report: " + error.getClass().getName()
                    + ": " + error.getMessage();
        }
    }

    static void clearCrashReport(Context context) {
        File file = getCrashFile(context);
        if (file.isFile() && !file.delete()) {
            // Best effort only.
        }
    }

    private void writeCrashReport(Thread thread, Throwable throwable) {
        StringWriter stack = new StringWriter();
        throwable.printStackTrace(new PrintWriter(stack));
        StringBuilder report = new StringBuilder();
        report.append("BitcoinWalletSync crash report\n");
        report.append("time=").append(new SimpleDateFormat(
                "yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date())).append('\n');
        report.append("thread=").append(thread.getName()).append('\n');
        report.append("thread_id=").append(thread.getId()).append('\n');
        report.append("package=").append(getPackageName()).append('\n');
        String versionName = "unknown";
        long versionCode = -1L;
        try {
            android.content.pm.PackageInfo info = getPackageManager().getPackageInfo(
                    getPackageName(), 0);
            versionName = info.versionName;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                versionCode = info.getLongVersionCode();
            } else {
                versionCode = info.versionCode;
            }
        } catch (Exception ignored) {
            // Keep the crash report usable even if package metadata is unavailable.
        }
        report.append("version_name=").append(versionName).append('\n');
        report.append("version_code=").append(versionCode).append('\n');
        report.append("target_sdk=33\n");
        report.append("android_sdk=").append(Build.VERSION.SDK_INT).append('\n');
        report.append("android_release=").append(Build.VERSION.RELEASE).append('\n');
        report.append("device=").append(Build.MANUFACTURER).append(' ')
                .append(Build.MODEL).append('\n');
        report.append("\nSTACKTRACE\n");
        report.append(stack);

        File file = getCrashFile(this);
        try (FileOutputStream output = new FileOutputStream(file, false)) {
            output.write(report.toString().getBytes(StandardCharsets.UTF_8));
            output.flush();
        } catch (Exception ignored) {
            // Nothing else can safely be done from an uncaught-exception handler.
        }
    }
}
