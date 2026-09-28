package wallet.main;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Debug;
import android.os.Process;
import android.app.Activity;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Temporary diagnostics for investigating background/task removal and memory pressure. */
final class DebugLog {
    private static final String TAG = "BitcoinWalletDebug";
    private static final String LOG_NAME = "bitcoin-wallet-debug-log.txt";
    private static final String CRASH_NAME = "bitcoin-wallet-crash-report.txt";
    private static final int MAX_LOG_BYTES = 512 * 1024;

    private static final Object LOCK = new Object();
    private static volatile Thread.UncaughtExceptionHandler previousHandler;
    private static volatile boolean installed;

    private DebugLog() { }

    static void installCrashHandler(Context context) {
        if (context == null || installed) {
            return;
        }
        synchronized (LOCK) {
            if (installed) {
                return;
            }
            final Context app = context.getApplicationContext();
            previousHandler = Thread.getDefaultUncaughtExceptionHandler();
            Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
                writeCrash(app, thread, throwable);
                Thread.UncaughtExceptionHandler handler = previousHandler;
                if (handler != null) {
                    handler.uncaughtException(thread, throwable);
                } else {
                    Process.killProcess(Process.myPid());
                    System.exit(10);
                }
            });
            installed = true;
            log(app, "CRASH_HANDLER_INSTALLED pid=" + Process.myPid());
        }
    }

    static void event(Context context, String event) {
        log(context, "EVENT " + event + " " + activityState(context) + " " + processState(context) + " " + memory(context));
    }

    static void activityEvent(Activity activity, String event) {
        if (activity == null) {
            return;
        }
        log(activity, "ACTIVITY " + event
                + " taskId=" + activity.getTaskId()
                + " finishing=" + activity.isFinishing()
                + " changingConfig=" + activity.isChangingConfigurations()
                + " " + activityState(activity)
                + " " + processState(activity)
                + " " + memory(activity));
    }

    static void syncState(Context context, MainActivityPresenter presenter, String reason) {
        if (presenter == null) {
            log(context, "SYNC reason=" + reason + " presenter=null " + processState(context) + " " + memory(context));
            return;
        }
        log(context, "SYNC reason=" + reason
                + " ready=" + presenter.isWalletReady()
                + " running=" + presenter.isWalletKitRunning()
                + " syncing=" + presenter.isSyncing()
                + " stalled=" + presenter.isSyncStalled()
                + " percent=" + presenter.getSyncPercent()
                + " current=" + presenter.getCurrentBlock()
                + " target=" + presenter.getNetworkBlock()
                + " peers=" + presenter.getConnectedPeerCount()
                + " restarts=" + presenter.getAutoRestartCount()
                + " " + processState(context)
                + " " + memory(context));
    }

    static void memory(Context context, String reason) {
        log(context, "MEMORY " + reason + " " + memory(context));
    }

    static File getLogFile(Context context) throws IOException {
        Context app = context.getApplicationContext();
        File file = new File(app.getFilesDir(), LOG_NAME);
        if (!file.exists()) {
            appendAndTrim(file, ("DEBUG LOG CREATED " + new Date() + "\n").getBytes(StandardCharsets.UTF_8));
        }
        return file;
    }

    static void log(Context context, String message) {
        if (context == null) {
            return;
        }
        Context app = context.getApplicationContext();
        String line = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
                .format(new Date())
                + " [" + Thread.currentThread().getName() + "] " + message + "\n";
        synchronized (LOCK) {
            try {
                File file = new File(app.getFilesDir(), LOG_NAME);
                appendAndTrim(file, line.getBytes(StandardCharsets.UTF_8));
            } catch (Exception error) {
                Log.e(TAG, "Unable to write debug log", error);
            }
        }
    }

    private static void writeCrash(Context context, Thread thread, Throwable throwable) {
        synchronized (LOCK) {
            try {
                File file = new File(context.getApplicationContext().getFilesDir(), CRASH_NAME);
                StringBuilder out = new StringBuilder();
                out.append("BitcoinWalletSync crash report\n");
                out.append("time=")
                        .append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
                                .format(new Date()))
                        .append('\n');
                out.append("thread=").append(thread == null ? "unknown" : thread.getName()).append('\n');
                out.append("threadId=").append(thread == null ? -1 : thread.getId()).append('\n');
                out.append(memory(context)).append('\n');
                out.append('\n');
                appendStack(out, throwable);
                writeReplace(file, out.toString().getBytes(StandardCharsets.UTF_8));

                File log = new File(context.getApplicationContext().getFilesDir(), LOG_NAME);
                appendAndTrim(log, ("CRASH thread=" +
                        (thread == null ? "unknown" : thread.getName()) + "\n")
                        .getBytes(StandardCharsets.UTF_8));
            } catch (Exception error) {
                Log.e(TAG, "Unable to write crash report", error);
            }
        }
    }

    private static void appendStack(StringBuilder out, Throwable throwable) {
        Throwable current = throwable;
        int depth = 0;
        while (current != null && depth++ < 8) {
            out.append(current.getClass().getName())
                    .append(": ")
                    .append(current.getMessage())
                    .append('\n');
            for (StackTraceElement element : current.getStackTrace()) {
                out.append("\tat ").append(element).append('\n');
            }
            current = current.getCause();
            if (current != null) {
                out.append("Caused by: ");
            }
        }
    }

    private static String activityState(Context context) {
        ActivityManager manager = (ActivityManager) context.getApplicationContext()
                .getSystemService(Context.ACTIVITY_SERVICE);
        if (manager == null) {
            return "tasks=unknown";
        }
        try {
            java.util.List<ActivityManager.AppTask> tasks = manager.getAppTasks();
            return "tasks=" + tasks.size();
        } catch (Exception ignored) {
            return "tasks=error";
        }
    }

    private static String processState(Context context) {
        ActivityManager manager = (ActivityManager) context.getApplicationContext()
                .getSystemService(Context.ACTIVITY_SERVICE);
        if (manager == null) {
            return "importance=unknown";
        }
        try {
            java.util.List<ActivityManager.RunningAppProcessInfo> processes = manager.getRunningAppProcesses();
            if (processes != null) {
                int pid = Process.myPid();
                for (ActivityManager.RunningAppProcessInfo process : processes) {
                    if (process.pid == pid) {
                        return "importance=" + process.importance
                                + " importanceReason=" + process.importanceReasonCode;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return "importance=unknown";
    }

    private static String memory(Context context) {
        Runtime runtime = Runtime.getRuntime();
        long used = runtime.totalMemory() - runtime.freeMemory();
        long max = runtime.maxMemory();
        long nativeUsed = Debug.getNativeHeapAllocatedSize();
        ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
        ActivityManager manager =
                (ActivityManager) context.getApplicationContext().getSystemService(Context.ACTIVITY_SERVICE);
        if (manager != null) {
            manager.getMemoryInfo(info);
        }
        return "heap=" + used + "/" + max
                + " native=" + nativeUsed
                + " availRam=" + info.availMem
                + " lowMemory=" + info.lowMemory
                + " threshold=" + info.threshold;
    }

    private static void appendAndTrim(File file, byte[] bytes) throws IOException {
        FileOutputStream output = new FileOutputStream(file, true);
        output.write(bytes);
        output.flush();
        output.getFD().sync();
        output.close();
        if (file.length() <= MAX_LOG_BYTES) {
            return;
        }
        byte[] all = new byte[(int) file.length()];
        FileInputStream input = new FileInputStream(file);
        int offset = 0;
        int read;
        while (offset < all.length && (read = input.read(all, offset, all.length - offset)) > 0) {
            offset += read;
        }
        input.close();
        int start = Math.max(0, offset - (MAX_LOG_BYTES / 2));
        FileOutputStream trimmed = new FileOutputStream(file, false);
        trimmed.write(all, start, offset - start);
        trimmed.close();
    }

    private static void writeReplace(File file, byte[] bytes) throws IOException {
        FileOutputStream output = new FileOutputStream(file, false);
        output.write(bytes);
        output.close();
    }
}
