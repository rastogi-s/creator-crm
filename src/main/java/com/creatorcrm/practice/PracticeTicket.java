package com.creatorcrm.practice;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;

/**
 * What the real app and its practice copy share. They run in the same Java process but as two separate Spring
 * apps, so this is the one place both can see: the sign-in ticket for the practice copy, when it was last used,
 * and how to shut it down.
 */
public final class PracticeTicket {
    private static volatile String ticket;
    private static volatile Runnable stopper;
    private static volatile Instant lastSeen = Instant.now();

    private PracticeTicket() {}

    static void open(String newTicket, Runnable stop) {
        ticket = newTicket;
        stopper = stop;
        lastSeen = Instant.now();
    }

    static void close() {
        ticket = null;
        stopper = null;
    }

    /** True for the ticket of the practice copy that is running now. */
    public static boolean matches(String candidate) {
        String t = ticket;
        return t != null && candidate != null && MessageDigest.isEqual(
                t.getBytes(StandardCharsets.UTF_8), candidate.getBytes(StandardCharsets.UTF_8));
    }

    /** The practice copy was just used (so it isn't closed for being idle). */
    public static void touch() {
        lastSeen = Instant.now();
    }

    static Instant lastSeen() {
        return lastSeen;
    }

    /** Asks the real app to close the practice copy, after the current request has had time to answer. */
    public static void requestStop() {
        Runnable stop = stopper;
        if (stop == null) return;
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            stop.run();
        }, "practice-stop");
        t.setDaemon(true);
        t.start();
    }
}
