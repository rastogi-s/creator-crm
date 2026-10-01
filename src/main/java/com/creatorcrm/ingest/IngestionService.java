package com.creatorcrm.ingest;

import com.creatorcrm.channels.ChannelConnector;
import com.creatorcrm.channels.NormalizedMessage;
import com.creatorcrm.config.CrmProperties;
import com.creatorcrm.domain.AppState;
import com.creatorcrm.domain.Conversation;
import com.creatorcrm.domain.Message;
import com.creatorcrm.llm.LlmClient;
import com.creatorcrm.llm.LlmException;
import com.creatorcrm.repo.AppStateRepo;
import com.creatorcrm.repo.ConversationRepo;
import com.creatorcrm.repo.MessageRepo;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
                OffsetDateTime since = read(key).map(OffsetDateTime::parse)
                        .map(t -> t.minusHours(1)) // overlap; duplicates are ignored
                        .orElse(started.minusDays(props.gmail().initialLookbackDays()));
                try {
                    int n = store(c.fetchSince(since));
                    stored += n;
                    write(key, started.toString());
                    write(key + ".error", "");
                    result.put(c.platform().name(), n + " new");
                } catch (Exception e) {
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
        return n;
    }

    public void processPendingAsync() {
        executor.execute(this::processPending);
    }

    /** Classify and apply every stored message that passed the pre-filter. Oldest first, so state builds up in order. */
    public int processPending() {
        if (!llm.isConfigured() || !processLock.tryLock()) return 0;
        int n = 0;
        try {
            for (Message m : messages.findByAiProcessedFalseAndFilteredReasonIsNullOrderBySentAtAsc()) {
                try {
                    processor.process(m);
                    n++;
                } catch (LlmException e) {
                    log.warn("AI analysis failed for message {}: {}", m.id, e.getMessage());
                    if (e.getMessage() != null && e.getMessage().contains("(401)")) break; // bad key: stop
                } catch (RuntimeException e) {
                    log.error("Processing failed for message {}", m.id, e);
                }
            }
        } finally {
            processLock.unlock();
        }
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
