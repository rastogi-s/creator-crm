package com.creatorcrm.ingest;

import com.creatorcrm.channels.ChannelConnector;
import com.creatorcrm.channels.NormalizedMessage;
import com.creatorcrm.channels.PartialFetchException;
import com.creatorcrm.config.CrmProperties;
import com.creatorcrm.domain.AppState;
import com.creatorcrm.domain.Conversation;
import com.creatorcrm.domain.Enums.Platform;
import com.creatorcrm.domain.Message;
import com.creatorcrm.llm.ClassificationInput;
import com.creatorcrm.llm.LlmClient;
import com.creatorcrm.llm.LlmException;
import com.creatorcrm.llm.MessageAnalysis;
import com.creatorcrm.llm.OutOfCreditsException;
import com.creatorcrm.repo.AppStateRepo;
import com.creatorcrm.repo.ConversationRepo;
import com.creatorcrm.repo.MessageRepo;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Optional;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Pulls messages from every connected channel, stores them once, and runs the AI + workflow pipeline. */
@Service
public class IngestionService {
    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);

    private final List<ChannelConnector> connectors;
    private final ConversationRepo conversations;
    private final MessageRepo messages;
    private final AppStateRepo state;
    private final PreFilter preFilter;
    private final MessageProcessor processor;
    private final LlmClient llm;
    private final TaskExecutor executor;
    private final CrmProperties props;
    private final ReentrantLock syncLock = new ReentrantLock();
    private final ReentrantLock processLock = new ReentrantLock();
    /** Bumped whenever stored data changes, so open pages know to refresh. */
    private final AtomicLong version = new AtomicLong(System.currentTimeMillis());

    public IngestionService(List<ChannelConnector> connectors, ConversationRepo conversations, MessageRepo messages,
                            AppStateRepo state, PreFilter preFilter, MessageProcessor processor, LlmClient llm,
                            @Qualifier("applicationTaskExecutor") TaskExecutor executor, CrmProperties props) {
        this.connectors = connectors;
        this.conversations = conversations;
        this.messages = messages;
        this.state = state;
        this.preFilter = preFilter;
        this.processor = processor;
        this.llm = llm;
        this.executor = executor;
        this.props = props;
    }

    public record SyncReport(Map<String, String> channels, int stored, int processed) {}

    /** What the header shows: is work running, how much is waiting for AI, and why it might be stuck. */
    public record Status(boolean syncing, boolean analyzing, long waitingForAi, boolean aiConfigured,
                         String aiError, String importingSince, long version, int inBatch) {}

    public Status status() {
        String importing = connectors.stream().map(c -> read("sync." + c.platform() + IMPORT))
                .flatMap(Optional::stream).findFirst().orElse(null);
        return new Status(syncLock.isLocked(), processLock.isLocked(),
                messages.countByAiProcessedFalseAndFilteredReasonIsNull(), llm.isConfigured(),
                read(AI_ERROR).orElse(null), importing, version.get(), batchMessageIds().size());
    }

    private static final String AI_ERROR = "ai.error";
    private static final String IMPORT = ".import";
    /** A big import can need many capped fetches; keep going in one sync instead of one batch per 30 minutes. */
    private static final int MAX_ROUNDS_PER_SYNC = 20;

    /**
     * Import older history: the next syncs reach back to {@code days} ago (skipping what's stored), until one
     * completes. Rate limits pause and resume it like any sync.
     */
    public void startImport(Platform platform, int days) {
        if (days < 1 || days > 730) throw new IllegalArgumentException("Pick between 1 and 730 days");
        ChannelConnector c = connectors.stream().filter(x -> x.platform() == platform).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown channel " + platform));
        if (!c.isConnected()) throw new IllegalStateException("Connect " + platform.name().toLowerCase() + " first");
        write("sync." + platform + IMPORT, OffsetDateTime.now().minusDays(days).toString());
    }

    public SyncReport syncAll() {
        if (!syncLock.tryLock()) return new SyncReport(Map.of("sync", "already running"), 0, 0);
        try {
            Map<String, String> result = new LinkedHashMap<>();
            int stored = 0;
            for (ChannelConnector c : connectors) {
                String key = "sync." + c.platform();
                if (!c.isConnected()) {
                    result.put(c.platform().name(), "not connected");
                    continue;
                }
                OffsetDateTime started = OffsetDateTime.now();
                Optional<OffsetDateTime> pausedUntil = read(key + ".pausedUntil").map(OffsetDateTime::parse)
                        .filter(started::isBefore);
                if (pausedUntil.isPresent()) {
                    // The channel told us to back off; calling it sooner only extends the block.
                    result.put(c.platform().name(), "rate limited, resuming after " + pausedUntil.get());
                    continue;
                }
                OffsetDateTime cursorSince = read(key).map(OffsetDateTime::parse)
                        .map(t -> t.minusHours(1)) // overlap; duplicates are ignored
                        .orElse(started.minusDays(props.gmail().initialLookbackDays()));
                OffsetDateTime since = read(key + IMPORT).map(OffsetDateTime::parse)
                        .filter(cursorSince::isAfter).orElse(cursorSince);
                String prefix = c.platform().name().toLowerCase() + ":";
                Predicate<String> known = id -> messages.existsByExternalId(prefix + id);
                int n = 0;
                try {
                    for (int round = 1; ; round++) {
                        try {
                            n += store(c.fetchSince(since, known));
                            break;
                        } catch (PartialFetchException e) {
                            n += store(e.fetched());
                            boolean capOnly = e.getCause() == null && e.retryAfter().isEmpty() && !e.fetched().isEmpty();
                            if (!capOnly || round >= MAX_ROUNDS_PER_SYNC) throw e;
                        }
                    }
                    stored += n;
                    write(key, started.toString());
                    write(key + ".error", "");
                    write(key + ".pausedUntil", "");
                    write(key + IMPORT, "");
                    result.put(c.platform().name(), n + " new");
                } catch (PartialFetchException e) {
                    // Keep what we got (stored above); leave the cursor alone so the next sync fetches the rest.
                    stored += n;
                    log.warn("Sync of {} partial ({} new): {}", c.platform(), n, e.getMessage());
                    Optional<OffsetDateTime> resume = e.retryAfter().map(t -> t.atZone(ZoneId.systemDefault()).toOffsetDateTime());
                    write(key + ".pausedUntil", resume.map(OffsetDateTime::toString).orElse(""));
                    String next = resume.map(t -> "paused until " + t.toLocalTime().withNano(0) + ", then continues automatically")
                            .orElse("will continue next sync");
                    write(key + ".error", e.getCause() == null ? ""
                            : OffsetDateTime.now() + " " + e.getMessage() + " (saved " + n + "; " + next + ")");
                    result.put(c.platform().name(), n + " new, more pending");
                } catch (Exception e) {
                    stored += n;
                    log.warn("Sync of {} failed: {}", c.platform(), e.getMessage());
                    write(key + ".error", OffsetDateTime.now() + " " + e.getMessage());
                    result.put(c.platform().name(), "error: " + e.getMessage());
                }
            }
            return new SyncReport(result, stored, processPending());
        } finally {
            syncLock.unlock();
        }
    }

    /** Store new messages (idempotent). Returns how many were new. */
    @Transactional
    public int store(List<NormalizedMessage> batch) {
        int n = 0;
        for (NormalizedMessage nm : batch.stream().sorted(Comparator.comparing(NormalizedMessage::sentAt)).toList()) {
            String externalId = nm.platform().name().toLowerCase() + ":" + nm.externalId();
            if (nm.externalId() == null || nm.externalId().isBlank() || messages.existsByExternalId(externalId)) continue;

            Conversation conv = conversations.findByPlatformAndExternalId(nm.platform(), nm.threadKey()).orElseGet(() -> {
                Conversation c = new Conversation();
                c.platform = nm.platform();
                c.externalId = nm.threadKey();
                c.createdAt = OffsetDateTime.now();
                return c;
            });
            if (conv.subject == null || conv.subject.isBlank()) conv.subject = nm.subject();
            if (nm.counterparty() != null && !nm.counterparty().isBlank()) conv.counterparty = nm.counterparty();
            if (conv.lastMessageAt == null || nm.sentAt().isAfter(conv.lastMessageAt)) conv.lastMessageAt = nm.sentAt();
            conv = conversations.save(conv);

            Message m = new Message();
            m.conversationId = conv.id;
            m.externalId = externalId;
            m.direction = nm.direction();
            m.sender = nm.sender();
            m.senderName = nm.senderName();
            m.recipient = nm.recipient();
            m.subject = nm.subject();
            m.content = nm.content();
            m.rfcMessageId = nm.rfcMessageId();
            m.replyTo = nm.replyTo();
            m.sentAt = nm.sentAt();
            m.filteredReason = preFilter.skipReason(nm, conv);
            messages.save(m);
            n++;
        }
        if (n > 0) version.incrementAndGet();
        return n;
    }

    public void processPendingAsync() {
        executor.execute(this::processPending);
    }

    /** History at least this old can wait for a batch: half price, results usually within an hour or two. */
    static final Duration BATCH_OLDER_THAN = Duration.ofDays(14);
    /** Fewer old messages waiting than this (no import running): not worth the wait, analyze them right away. */
    static final int MIN_BATCH = 20;
    static final int MAX_BATCH = 1000;
    /** Batches end within 24 hours; one older than this was lost, so its messages go again. */
    static final Duration BATCH_GIVE_UP = Duration.ofHours(48);
    private static final String BATCH_ID = "ai.batch.id";
    private static final String BATCH_MESSAGES = "ai.batch.messages";
    private static final String BATCH_AT = "ai.batch.submittedAt";

    public boolean batchInFlight() {
        return read(BATCH_ID).isPresent();
    }

    private List<Long> batchMessageIds() {
        return read(BATCH_MESSAGES).map(v -> Arrays.stream(v.split(",")).map(Long::valueOf).toList()).orElse(List.of());
    }

    /**
     * Classify and apply every stored message that passed the pre-filter. Oldest first, so state builds up in order.
     *
     * <p>A big history import goes through the Batch API at half price instead: each round sends the oldest waiting
     * message of every conversation, and the next message of a conversation waits until the one before it is
     * applied, because its analysis builds on that. New messages in other conversations are analyzed right away.
     */
    public int processPending() {
        if (!llm.isConfigured() || !processLock.tryLock()) return 0;
        int n = 0;
        int claudeFailuresInARow = 0;
        boolean claudeDown = false;
        try {
            n += collectBatch();
            List<Long> inFlight = batchMessageIds();
            Set<Long> waiting = new HashSet<>(); // conversations waiting on a batch
            messages.findAllById(inFlight).forEach(m -> waiting.add(m.conversationId));

            List<Message> pending = messages.findByAiProcessedFalseAndFilteredReasonIsNullOrderBySentAtAsc();
            OffsetDateTime old = OffsetDateTime.now().minus(BATCH_OLDER_THAN);
            boolean batching = llm.supportsBatch() && (!inFlight.isEmpty()
                    || pending.stream().filter(m -> m.sentAt.isBefore(old)).count() >= MIN_BATCH);
            List<Message> toBatch = new ArrayList<>();

            for (Message m : pending) {
                if (waiting.contains(m.conversationId)) continue;
                if (messages.existsByConversationIdAndAiProcessedTrueAndSentAtAfter(m.conversationId, m.sentAt)) {
                    // Imported history from a thread already analyzed past this point: applying it now would move
                    // the deal backwards. Keep it as context for future analysis instead.
                    m.filteredReason = "older than messages already analyzed (kept as context)";
                    messages.save(m);
                    continue;
                }
                if (batching && m.sentAt.isBefore(old)) {
                    waiting.add(m.conversationId);
                    if (inFlight.isEmpty() && toBatch.size() < MAX_BATCH) toBatch.add(m);
                    continue;
                }
                try {
                    processor.process(m);
                    n++;
                    claudeFailuresInARow = 0;
                    version.incrementAndGet();
                    if (read(AI_ERROR).isPresent()) write(AI_ERROR, "");
                } catch (LlmException e) {
                    log.warn("AI analysis failed for message {}: {}", m.id, e.getMessage());
                    write(AI_ERROR, OffsetDateTime.now() + " " + e.getMessage());
                    // Out of credits, bad key, rate limited, or Claude failing over and over: stop; the rest retry on
                    // the next run.
                    claudeDown = true;
                    if (e instanceof OutOfCreditsException) break;
                    if (e.getMessage() != null && e.getMessage().matches("(?s).*\\((401|429)\\).*")) break;
                    if (++claudeFailuresInARow >= 3) break;
                    claudeDown = false;
                } catch (LinkageError e) {
                    // A broken build (e.g. clashing library versions): every message would fail the same way.
                    log.error("Analysis is broken in this build", e);
                    write(AI_ERROR, OffsetDateTime.now() + " App error, please update Creator CRM: "
                            + e.getClass().getSimpleName() + ": " + e.getMessage());
                    claudeDown = true;
                    break;
                } catch (RuntimeException e) {
                    // Something specific to this message (e.g. saving the result). Show it, and keep going.
                    log.error("Processing failed for message {}", m.id, e);
                    write(AI_ERROR, OffsetDateTime.now() + " Analysis failed for one message: "
                            + e.getClass().getSimpleName() + ": " + e.getMessage());
                }
            }
            if (!toBatch.isEmpty() && !claudeDown) submitBatch(toBatch);
        } finally {
            processLock.unlock();
        }
        return n;
    }

    private void submitBatch(List<Message> toBatch) {
        Map<String, ClassificationInput> inputs = new LinkedHashMap<>();
        try {
            toBatch.forEach(m -> inputs.put(m.id.toString(), processor.input(m)));
            String id = llm.submitClassifyBatch(inputs);
            write(BATCH_MESSAGES, String.join(",", inputs.keySet()));
            write(BATCH_AT, OffsetDateTime.now().toString());
            write(BATCH_ID, id);
            log.info("Sent {} older messages for half-price batch analysis ({})", inputs.size(), id);
        } catch (LlmException e) {
            log.warn("Couldn't start batch analysis: {}", e.getMessage());
            write(AI_ERROR, OffsetDateTime.now() + " " + e.getMessage());
        }
    }

    /** If the batch in flight has finished, apply its results oldest first. Returns how many were applied. */
    private int collectBatch() {
        String id = read(BATCH_ID).orElse(null);
        if (id == null) return 0;
        Map<String, MessageAnalysis> results;
        try {
            results = llm.pollClassifyBatch(id);
        } catch (LlmException e) {
            log.warn("Couldn't check batch analysis {}: {}", id, e.getMessage());
            results = null;
        }
        if (results == null) {
            boolean lost = read(BATCH_AT).map(at -> OffsetDateTime.parse(at).plus(BATCH_GIVE_UP)
                    .isBefore(OffsetDateTime.now())).orElse(true);
            if (!lost) return 0;
            log.warn("Batch analysis {} didn't finish; its messages will be analyzed again", id);
            results = Map.of();
        }
        List<Message> sent = new ArrayList<>(messages.findAllById(batchMessageIds()));
        write(BATCH_ID, "");
        write(BATCH_MESSAGES, "");
        write(BATCH_AT, "");
        sent.sort(Comparator.comparing((Message m) -> m.sentAt));
        int n = 0;
        for (Message m : sent) {
            MessageAnalysis a = results.get(m.id.toString());
            if (a == null || m.aiProcessed || m.filteredReason != null) continue; // failed ones go again
            try {
                processor.apply(m, a);
                n++;
                version.incrementAndGet();
            } catch (RuntimeException e) {
                log.error("Processing failed for message {}", m.id, e);
                write(AI_ERROR, OffsetDateTime.now() + " Analysis failed for one message: "
                        + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }
        if (n > 0 && read(AI_ERROR).isPresent()) write(AI_ERROR, "");
        return n;
    }

    /** Run work with syncing and AI processing paused (backup/restore need a quiet database). */
    public <T> T runExclusive(java.util.function.Supplier<T> work) {
        boolean sync = false;
        boolean process = false;
        try {
            sync = syncLock.tryLock(2, java.util.concurrent.TimeUnit.MINUTES);
            process = sync && processLock.tryLock(2, java.util.concurrent.TimeUnit.MINUTES);
            if (!process) throw new IllegalStateException("A sync is still running. Try again in a minute.");
            return work.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted");
        } finally {
            if (process) processLock.unlock();
            if (sync) syncLock.unlock();
        }
    }

    public java.util.Optional<String> read(String key) {
        return state.findById(key).map(s -> s.stateValue).filter(v -> !v.isBlank());
    }

    private void write(String key, String value) {
        AppState s = new AppState();
        s.stateKey = key;
        s.stateValue = value == null ? "" : value.length() > 1900 ? value.substring(0, 1900) : value;
        state.save(s);
    }
}
