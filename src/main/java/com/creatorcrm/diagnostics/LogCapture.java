package com.creatorcrm.diagnostics;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.StackTraceElementProxy;
import ch.qos.logback.core.AppenderBase;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Logback appender that keeps the last few hundred log lines in memory (context for a report) and hands every
 * error to a listener. Nothing here is redacted yet; that happens just before anything is sent.
 */
class LogCapture extends AppenderBase<ILoggingEvent> {

    /** One error as it happened. {@code fingerprint} is stable across runs and versions, so repeats group together. */
    record Captured(String fingerprint, Instant at, String logger, String thread, String message, String exception,
                    String stackTrace, List<String> recentLines) {}

    static final int KEEP_LINES = 300;
    static final int CONTEXT_LINES = 60;
    private static final int MAX_STACK_LINES = 40;
    private static final int MAX_LINE = 600;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneId.systemDefault());

    /** No internet, not a bug: a laptop that's offline shouldn't file issues about it. */
    private static final Set<String> OFFLINE = Set.of(UnknownHostException.class.getName(), ConnectException.class.getName(),
            SocketTimeoutException.class.getName(), HttpTimeoutException.class.getName(),
            HttpConnectTimeoutException.class.getName(), "java.nio.channels.UnresolvedAddressException");

    private final ArrayDeque<String> recent = new ArrayDeque<>();
    private final Consumer<Captured> onError;

    LogCapture(Consumer<Captured> onError) {
        this.onError = onError;
        setName("creator-crm-error-reports");
    }

    @Override
    protected void append(ILoggingEvent e) {
        if (!e.getLevel().isGreaterOrEqual(Level.INFO)) return;
        String line = format(e);
        List<String> context;
        synchronized (recent) {
            for (String l : line.split("\n")) {
                recent.addLast(l);
                if (recent.size() > KEEP_LINES) recent.removeFirst();
            }
            context = List.copyOf(recent).subList(Math.max(0, recent.size() - CONTEXT_LINES), recent.size());
        }
        if (!isReportable(e)) return;
        IThrowableProxy t = e.getThrowableProxy();
        onError.accept(new Captured(fingerprint(e), Instant.ofEpochMilli(e.getTimeStamp()), e.getLoggerName(),
                e.getThreadName(), clip(e.getFormattedMessage()), t == null ? null : t.getClassName(),
                t == null ? null : stackTrace(t), context));
    }

    List<String> recentLines() {
        synchronized (recent) {
            return List.copyOf(recent);
        }
    }

    static boolean isReportable(ILoggingEvent e) {
        if (e.getLoggerName().startsWith(LogCapture.class.getPackageName())) return false; // never report about reporting
        IThrowableProxy t = e.getThrowableProxy();
        boolean error = e.getLevel().isGreaterOrEqual(Level.ERROR) || (e.getLevel() == Level.WARN && t != null);
        if (!error) return false;
        for (IThrowableProxy c = t; c != null; c = c.getCause()) {
            if (OFFLINE.contains(c.getClassName())) return false;
        }
        return true;
    }

    /** Logger + exception types + where in our code it was thrown (no line numbers, so it survives edits). */
    static String fingerprint(ILoggingEvent e) {
        StringBuilder key = new StringBuilder(e.getLoggerName());
        IThrowableProxy t = e.getThrowableProxy();
        if (t == null) {
            key.append('|').append(e.getMessage()); // the {} template, not the filled-in values
        }
        for (IThrowableProxy c = t; c != null; c = c.getCause()) {
            key.append('|').append(c.getClassName());
            int ours = 0;
            for (StackTraceElementProxy f : c.getStackTraceElementProxyArray()) {
                StackTraceElement s = f.getStackTraceElement();
                if (s.getClassName().startsWith("com.creatorcrm.") && ours++ < 4) {
                    key.append('>').append(s.getClassName()).append('.').append(s.getMethodName());
                }
            }
        }
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(key.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(h, 0, 6);
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static String format(ILoggingEvent e) {
        StringBuilder b = new StringBuilder()
                .append(TIME.format(Instant.ofEpochMilli(e.getTimeStamp()))).append(' ')
                .append(String.format("%-5s", e.getLevel())).append(" [").append(e.getThreadName()).append("] ")
                .append(shortLogger(e.getLoggerName())).append(" - ").append(clip(e.getFormattedMessage()));
        if (e.getThrowableProxy() != null) b.append('\n').append(stackTrace(e.getThrowableProxy()));
        return b.toString();
    }

    static String stackTrace(IThrowableProxy t) {
        List<String> lines = new ArrayList<>();
        for (IThrowableProxy c = t; c != null && lines.size() < MAX_STACK_LINES; c = c.getCause()) {
            lines.add((c == t ? "" : "Caused by: ") + c.getClassName() + ": " + clip(c.getMessage()));
            StackTraceElementProxy[] frames = c.getStackTraceElementProxyArray();
            int shown = 0;
            for (StackTraceElementProxy f : frames) {
                if (lines.size() >= MAX_STACK_LINES || shown >= 12) break;
                lines.add("\tat " + f.getStackTraceElement());
                shown++;
            }
            if (frames.length > shown) lines.add("\t... " + (frames.length - shown) + " more");
        }
        return String.join("\n", lines);
    }

    private static String shortLogger(String name) {
        int i = name.lastIndexOf('.');
        return i < 0 ? name : name.substring(i + 1);
    }

    private static String clip(String s) {
        if (s == null) return "";
        String one = s.replace('\r', ' ');
        return one.length() <= MAX_LINE ? one : one.substring(0, MAX_LINE) + "…";
    }
}
