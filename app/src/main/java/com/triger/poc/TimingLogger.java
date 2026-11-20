package com.triger.poc;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

/** Хранит последние TIMING-события для показа в интерфейсе. */
public final class TimingLogger {

    private static final int MAX_EVENTS = 20;
    private static final TimingLogger INSTANCE = new TimingLogger();

    private final ArrayDeque<String> events = new ArrayDeque<>();
    private final SimpleDateFormat timeFormat =
            new SimpleDateFormat("HH:mm:ss", Locale.US);

    private TimingLogger() {
    }

    public static TimingLogger getInstance() {
        return INSTANCE;
    }

    public static void log(String event) {
        INSTANCE.add(event);
    }

    private synchronized void add(String event) {
        String text = event == null ? "" : event.trim();
        if (text.isEmpty()) {
            return;
        }
        String line = "[" + timeFormat.format(new Date()) + "] " + text;
        events.addLast(line);
        while (events.size() > MAX_EVENTS) {
            events.removeFirst();
        }
    }

    public synchronized String dump() {
        if (events.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (String line : events) {
            if (builder.length() > 0) {
                builder.append('\n');
            }
            builder.append(line);
        }
        return builder.toString();
    }
}
