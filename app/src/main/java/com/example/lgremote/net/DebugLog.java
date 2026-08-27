package com.example.lgremote.net;

import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedList;
import java.util.Locale;

/**
 * In-memory ring buffer of recent diagnostic lines so connection issues can be
 * inspected on screen without adb. Also mirrors everything to logcat.
 */
public final class DebugLog {

    private static final int MAX_LINES = 80;
    private static final LinkedList<String> LINES = new LinkedList<>();
    private static final Object LOCK = new Object();

    private DebugLog() {
    }

    public static void d(String tag, String message) {
        Log.d(tag, message);
        append("D " + tag + ": " + message);
    }

    public static void e(String tag, String message) {
        Log.e(tag, message);
        append("E " + tag + ": " + message);
    }

    public static void e(String tag, String message, Throwable t) {
        Log.e(tag, message, t);
        append("E " + tag + ": " + message + " (" + t + ")");
    }

    private static String timestamp() {
        return new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date());
    }

    private static void append(String line) {
        synchronized (LOCK) {
            LINES.addLast(timestamp() + " " + line);
            while (LINES.size() > MAX_LINES) {
                LINES.removeFirst();
            }
        }
    }

    public static String dump() {
        synchronized (LOCK) {
            StringBuilder sb = new StringBuilder();
            for (String line : LINES) {
                sb.append(line).append('\n');
            }
            return sb.toString();
        }
    }

    public static void clear() {
        synchronized (LOCK) {
            LINES.clear();
        }
    }
}
