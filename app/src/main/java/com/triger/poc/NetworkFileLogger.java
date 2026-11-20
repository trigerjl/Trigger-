package com.triger.poc;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class NetworkFileLogger {

    private static final String TAG = "TriggerNet";
    private static final String LOG_DIR = "logs";
    private static final String LOG_FILE = "trigger_net.log";
    private static final String ROTATED_FILE = "trigger_net.log.1";
    private static final long MAX_BYTES = 1024L * 1024L;
    private static final Object LOCK = new Object();
    private static final SimpleDateFormat TIME_FORMAT =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);

    private static volatile Context appContext;

    private NetworkFileLogger() {
    }

    public static void init(Context context) {
        if (context != null) {
            appContext = context.getApplicationContext();
        }
    }

    public static void log(String event) {
        String text = event == null ? "" : event.trim();
        if (text.isEmpty()) {
            return;
        }
        Log.i(TAG, text);
        Context context = appContext;
        if (context == null) {
            return;
        }
        File file = getLogFile(context);
        String line = TIME_FORMAT.format(new Date()) + " [" + Thread.currentThread().getName()
                + "] " + text + "\n";
        byte[] bytes = line.getBytes(StandardCharsets.UTF_8);
        synchronized (LOCK) {
            try {
                File dir = file.getParentFile();
                if (dir != null && !dir.exists() && !dir.mkdirs()) {
                    return;
                }
                if (file.exists() && file.length() + bytes.length > MAX_BYTES) {
                    rotate(file);
                }
                try (FileOutputStream out = new FileOutputStream(file, true)) {
                    out.write(bytes);
                    out.flush();
                }
            } catch (Throwable t) {
                Log.w(TAG, "Failed to write network log", t);
            }
        }
    }

    public static File getLogFile(Context context) {
        Context ctx = context != null ? context.getApplicationContext() : appContext;
        File base = null;
        if (ctx != null) {
            base = ctx.getExternalFilesDir(null);
            if (base == null) {
                base = ctx.getFilesDir();
            }
        }
        if (base == null) {
            base = new File(System.getProperty("java.io.tmpdir", "."));
        }
        return new File(new File(base, LOG_DIR), LOG_FILE);
    }

    private static void rotate(File file) {
        File rotated = new File(file.getParentFile(), ROTATED_FILE);
        if (rotated.exists() && !rotated.delete()) {
            Log.w(TAG, "Failed to delete old rotated network log");
        }
        if (!file.renameTo(rotated)) {
            Log.w(TAG, "Failed to rotate network log, truncating");
            try (FileOutputStream ignored = new FileOutputStream(file, false)) {
            } catch (Throwable t) {
                Log.w(TAG, "Failed to truncate network log", t);
            }
        }
    }
}
