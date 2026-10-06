package com.creatorcrm.drafts;

import com.creatorcrm.domain.Draft;
import jakarta.annotation.PreDestroy;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * The undo window after she presses Send: the draft is checked and saved straight away, then actually sent a few
 * seconds later unless she presses Undo. Waiting sends live in memory only, so if the app closes during the window
 * the draft simply stays in Drafts, unsent. Automatic follow-ups don't wait here; nobody is watching to undo them.
 */
@Service
public class SendQueue {
    private static final Logger log = LoggerFactory.getLogger(SendQueue.class);

    /** One waiting send. Whoever removes it from {@link #waiting} first (the timer or Undo) decides what happens. */
    public record Waiting(Long draftId, OffsetDateTime sendAt) {}

    private final DraftService drafts;
    private final int undoSeconds;
    private final Map<Long, Waiting> waiting = new ConcurrentHashMap<>();
    /** Sends past their undo window and on their way out, so nothing else sends the same draft meanwhile. */
    private final Set<Long> inFlight = ConcurrentHashMap.newKeySet();
    /** Why the last send of a draft failed after its window, until she tries again. Shown on the draft. */
    private final Map<Long, String> failures = new ConcurrentHashMap<>();
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "send-queue");
        t.setDaemon(true);
        return t;
    });

    public SendQueue(DraftService drafts, @Value("${crm.send.undo-seconds:10}") int undoSeconds) {
        this.drafts = drafts;
        this.undoSeconds = Math.max(0, undoSeconds);
    }

    public int undoSeconds() {
        return undoSeconds;
    }

    /** Saves her edits, checks the draft can go, and sends it after the undo window. Throws if it can't be sent. */
    public Waiting send(Long draftId, String subject, String body) {
        requireNotWaiting(draftId);
        Draft d = drafts.readyToSend(draftId, subject, body);
        failures.remove(d.id);
        Waiting w = new Waiting(d.id, OffsetDateTime.now().plusSeconds(undoSeconds));
        if (waiting.putIfAbsent(d.id, w) != null) throw new IllegalStateException("This draft is already being sent");
        timer.schedule(() -> fire(w), undoSeconds, TimeUnit.SECONDS);
        return w;
    }

    /** Undo: true when the send was stopped in time, false when it had already gone (or was never waiting). */
    public boolean cancel(Long draftId) {
        return waiting.remove(draftId) != null;
    }

    public Optional<Waiting> waiting(Long draftId) {
        return Optional.ofNullable(waiting.get(draftId));
    }

    /** Waiting out its undo window, or past it and being sent right now. */
    public boolean isWaiting(Long draftId) {
        return waiting.containsKey(draftId) || inFlight.contains(draftId);
    }

    /** Why sending this draft failed after its undo window, if it did. */
    public Optional<String> failure(Long draftId) {
        return Optional.ofNullable(failures.get(draftId));
    }

    /** Refuses a change to a draft that's about to go, so what she previewed is what is sent. */
    public void requireNotWaiting(Long draftId) {
        if (isWaiting(draftId)) throw new IllegalStateException("This draft is being sent. Press Undo first to change it.");
    }

    private void fire(Waiting w) {
        inFlight.add(w.draftId());
        try {
            if (!waiting.remove(w.draftId(), w)) return; // undone
            drafts.send(w.draftId(), null, null);
        } catch (RuntimeException e) {
            // The draft stays in Drafts, unsent, with this reason on it so she can fix it and try again.
            failures.put(w.draftId(), e.getMessage() == null ? "Sending failed" : e.getMessage());
            log.warn("Could not send draft {} after the undo window: {}", w.draftId(), e.getMessage());
        } finally {
            inFlight.remove(w.draftId());
        }
    }

    @PreDestroy
    void stop() {
        timer.shutdownNow();
    }
}
