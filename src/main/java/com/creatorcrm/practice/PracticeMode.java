package com.creatorcrm.practice;

import com.creatorcrm.CreatorCrmApplication;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.logging.LoggingApplicationListener;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Practice mode: a second, throwaway copy of the app with made-up brands (the same ones demo mode uses), so she can
 * try anything without touching her real deals.
 *
 * <p>The copy runs inside this same program but is otherwise fully separate: its own database in a temporary
 * folder, its own sign-in, a pretend Claude, no Gmail, Instagram, backups or updates, and Send only pretends to send.
 * It listens on its own local port; the real app keeps working (and checking mail) the whole time. Each start
 * begins from fresh sample data, and the copy and its folder are deleted when she leaves, after an hour unused, or
 * when the app closes.
 */
@Service
@ConditionalOnProperty(name = "crm.practice", havingValue = "false", matchIfMissing = true)
public class PracticeMode {
    private static final Logger log = LoggerFactory.getLogger(PracticeMode.class);
    static final String FOLDER_PREFIX = "creator-crm-practice-";
    static final Duration IDLE = Duration.ofHours(1);

    public enum State { OFF, STARTING, READY, FAILED }

    public record Status(State state, String url, String error) {}

    private final SecureRandom random = new SecureRandom();
    private State state = State.OFF;
    private String url;
    private String error;
    private ConfigurableApplicationContext practice;
    private Path folder;
    /** Bumped on every start and stop, so a start that finishes after a stop knows to throw its copy away. */
    private long generation;

    public synchronized Status status() {
        return new Status(state, state == State.READY ? url : null, state == State.FAILED ? error : null);
    }

    /**
     * Starts a fresh practice copy in the background (it takes a few seconds); poll {@link #status()} until it's
     * READY. {@code host} is how the browser reached the real app (localhost), and {@code returnUrl} is where
     * "Leave practice" goes back to.
     */
    public synchronized Status start(String host, String returnUrl) {
        if (state == State.STARTING || state == State.READY) return status();
        state = State.STARTING;
        error = null;
        long gen = ++generation;
        String ticket = newTicket();
        PracticeTicket.open(ticket, this::stop);
        Thread t = new Thread(() -> launch(gen, ticket, host, returnUrl), "practice-start");
        t.setDaemon(true);
        t.setContextClassLoader(CreatorCrmApplication.class.getClassLoader());
        t.start();
        return status();
    }

    private void launch(long gen, String ticket, String host, String returnUrl) {
        Path dir = null;
        ConfigurableApplicationContext ctx = null;
        try {
            deleteLeftovers();
            dir = Files.createTempDirectory(FOLDER_PREFIX);
            ctx = app().run(args(dir, returnUrl));
            int port = ((WebServerApplicationContext) ctx).getWebServer().getPort();
            boolean wanted;
            synchronized (this) {
                wanted = gen == generation;
                if (wanted) {
                    practice = ctx;
                    folder = dir;
                    url = "http://" + host + ":" + port + "/practice/enter?ticket=" + ticket;
                    state = State.READY;
                }
            }
            if (!wanted) {
                close(ctx, dir); // she left (or the app closed) while it was starting
                return;
            }
            log.info("Practice mode started on port {}", port);
        } catch (Throwable e) {
            log.warn("Practice mode could not start", e);
            close(ctx, dir);
            synchronized (this) {
                if (gen == generation) {
                    state = State.FAILED;
                    error = "Practice mode couldn't start. Try again, or restart the app.";
                    PracticeTicket.close();
                }
            }
        }
    }

    /** Closes the practice copy and deletes its data. Safe to call when it isn't running. */
    public void stop() {
        ConfigurableApplicationContext ctx;
        Path dir;
        synchronized (this) {
            generation++;
            ctx = practice;
            dir = folder;
            practice = null;
            folder = null;
            url = null;
            state = State.OFF;
            PracticeTicket.close();
        }
        if (ctx != null) log.info("Practice mode closed");
        close(ctx, dir);
    }

    @Scheduled(fixedDelay = 5 * 60 * 1000L, initialDelay = 5 * 60 * 1000L)
    public void closeWhenIdle() {
        boolean idle;
        synchronized (this) {
            idle = state == State.READY && Duration.between(PracticeTicket.lastSeen(), Instant.now()).compareTo(IDLE) > 0;
        }
        if (idle) stop();
    }

    @jakarta.annotation.PreDestroy
    public void shutdown() {
        stop();
    }

    private static SpringApplication app() {
        SpringApplication app = new SpringApplication(CreatorCrmApplication.class);
        app.setBannerMode(Banner.Mode.OFF);
        // Logging is set up once, by the real app; letting the copy redo it would reset the real app's logs.
        List<org.springframework.context.ApplicationListener<?>> listeners = new ArrayList<>(app.getListeners());
        listeners.removeIf(l -> l instanceof LoggingApplicationListener);
        app.setListeners(listeners);
        return app;
    }

    /** Command-line arguments outrank environment variables, so none of the real install's settings leak in. */
    static String[] args(Path dir, String returnUrl) {
        String data = dir.toAbsolutePath().toString().replace('\\', '/');
        return new String[] {
                "--spring.profiles.active=demo,practice",
                "--crm.practice=true",
                "--crm.practice-return-url=" + returnUrl,
                "--crm.data-dir=" + data,
                "--spring.datasource.url=jdbc:h2:file:" + data
                        + "/creator-crm;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
                "--spring.datasource.username=sa",
                "--spring.datasource.password=",
                "--server.port=0",
                "--server.address=127.0.0.1",
                // Both copies live on localhost, and browsers share cookies across ports: keep the sign-ins apart.
                "--server.servlet.session.cookie.name=CRM_PRACTICE_SESSION",
                "--crm.desktop=false",
                "--crm.updates.enabled=false",
                "--crm.updates.simulate-latest=",
                "--crm.updates.check-cron=-",
                "--crm.schedule.sync-cron=-",
                "--crm.schedule.morning-check-cron=-",
                "--crm.schedule.backup-check-cron=-",
                "--crm.schedule.batch-check-cron=-",
                "--crm.diagnostics.repo=",
                "--crm.public-base-url=http://localhost",
                "--spring.jmx.enabled=false",
        };
    }

    private String newTicket() {
        byte[] b = new byte[32];
        random.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private static void close(ConfigurableApplicationContext ctx, Path dir) {
        if (ctx != null) {
            try {
                ctx.close();
            } catch (RuntimeException e) {
                log.warn("Could not close practice mode cleanly: {}", e.getMessage());
            }
        }
        if (dir != null) deleteTree(dir);
    }

    /** Folders left behind if the app was closed suddenly while practising. */
    private static void deleteLeftovers() {
        Path tmp = Path.of(System.getProperty("java.io.tmpdir"));
        try (DirectoryStream<Path> old = Files.newDirectoryStream(tmp, FOLDER_PREFIX + "*")) {
            for (Path p : old) if (Files.isDirectory(p)) deleteTree(p);
        } catch (IOException e) {
            log.debug("Could not look for old practice folders: {}", e.getMessage());
        }
    }

    static void deleteTree(Path dir) {
        if (!dir.getFileName().toString().startsWith(FOLDER_PREFIX)) return; // never anything else
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    log.debug("Could not delete {}: {}", p, e.getMessage());
                }
            });
        } catch (IOException e) {
            log.debug("Could not delete practice folder {}: {}", dir, e.getMessage());
        }
    }
}
