package com.creatorcrm.learning;

import com.creatorcrm.domain.Draft;
import com.creatorcrm.domain.Enums.Platform;
import com.creatorcrm.domain.Message;
import com.creatorcrm.domain.WritingExample;
import com.creatorcrm.llm.Intent;
import com.creatorcrm.llm.Untrusted;
import com.creatorcrm.repo.WritingExampleRepo;
import com.creatorcrm.settings.SettingsService;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Learns the creator's voice from what they actually send. Every sent message is kept with what Claude first
 * wrote (if it was a draft) and whether the brand replied; each new draft gets the few closest past examples,
 * favouring ones the creator edited and ones that got a reply. No model training, no vector store: just
 * retrieval from the CRM's own database, which the creator can inspect and prune on the Settings page.
 */
@Service
public class LearningService {

    static final int EXAMPLES_PER_DRAFT = 3;
    static final int MAX_EXAMPLE_CHARS = 1_500;
    /** A brand message this long after the creator's message no longer counts as a reply to it. */
    static final Duration REPLY_WINDOW = Duration.ofDays(30);
    private static final int MIN_WRITTEN_CHARS = 20;

    /** Creator messages written outside the app that are worth learning from, by what they were. */
    private static final Map<Intent, String> WRITTEN_KINDS = Map.of(
            Intent.PITCH, "PITCH", Intent.CREATOR_REPLY, "REPLY", Intent.CREATOR_FOLLOW_UP, "FOLLOW_UP",
            Intent.SENT_RATES_OR_MEDIA_KIT, "RATES", Intent.CREATOR_DECLINED, "DECLINE");

    public record Stats(boolean enabled, long examples, long edited, long gotReply) {}

    private final WritingExampleRepo examples;
    private final SettingsService settings;

    public LearningService(WritingExampleRepo examples, SettingsService settings) {
        this.examples = examples;
        this.settings = settings;
    }

    /** An approved draft went out (sent from the app or marked as sent by the creator). */
    @Transactional
    public void recordDraftSent(Draft d, String brandName) {
        if (d.body == null || d.body.isBlank()) return;
        WritingExample e = new WritingExample();
        e.opportunityId = d.opportunityId;
        e.draftId = d.id;
        e.kind = d.type.name();
        e.channel = d.channel;
        e.source = WritingExample.APP_DRAFT;
        e.brandName = brandName;
        e.aiSubject = d.originalSubject;
        e.aiBody = d.originalBody;
        e.sentSubject = d.subject;
        e.sentBody = d.body;
        e.edited = d.originalBody != null && (!same(d.originalBody, d.body) || !same(d.originalSubject, d.subject));
        e.sentAt = OffsetDateTime.now();
        examples.save(e);
    }

    /** The creator wrote to a brand directly in Gmail or Instagram: pure examples of their own voice. */
    @Transactional
    public void recordWritten(Message m, Platform channel, Long opportunityId, Intent intent, String brandName) {
        String kind = WRITTEN_KINDS.get(intent);
        if (kind == null || m.content == null || m.content.strip().length() < MIN_WRITTEN_CHARS) return;
        // A draft marked "I sent it myself" comes back on the next sync; it's already recorded.
        boolean known = examples.findByOpportunityIdOrderBySentAtDesc(opportunityId).stream()
                .anyMatch(x -> WritingExample.APP_DRAFT.equals(x.source) && same(x.sentBody, m.content));
        if (known) return;
        WritingExample e = new WritingExample();
        e.opportunityId = opportunityId;
        e.kind = kind;
        e.channel = channel;
        e.source = WritingExample.WRITTEN;
        e.brandName = brandName;
        e.sentSubject = m.subject;
        e.sentBody = m.content.strip();
        e.sentAt = m.sentAt != null ? m.sentAt : OffsetDateTime.now();
        examples.save(e);
    }

    /** The brand wrote back: credit the creator's latest message to them, if it was recent enough. */
    @Transactional
    public void onBrandReply(Long opportunityId, OffsetDateTime at) {
        OffsetDateTime when = at != null ? at : OffsetDateTime.now();
        examples.findByOpportunityIdOrderBySentAtDesc(opportunityId).stream()
                .filter(e -> !e.sentAt.isAfter(when))
                .findFirst()
                .filter(e -> !e.gotReply && Duration.between(e.sentAt, when).compareTo(REPLY_WINDOW) <= 0)
                .ifPresent(e -> {
                    e.gotReply = true;
                    e.repliedAt = when;
                    examples.save(e);
                });
    }

    /** The best past examples for a new draft, rendered for the prompt. Empty when learning is off. */
    public List<String> examplesFor(String kind, Platform channel, Long opportunityId) {
        if (!settings.learnFromHistory()) return List.of();
        return pick(kind, channel, opportunityId).stream().map(LearningService::render).toList();
    }

    List<WritingExample> pick(String kind, Platform channel, Long opportunityId) {
        // The candidate list is newest first and the sort is stable, so ties go to the most recent.
        return examples.findTop300ByExcludedFalseOrderBySentAtDesc().stream()
                .filter(e -> !Objects.equals(e.opportunityId, opportunityId)) // that thread is already in the prompt
                .sorted(Comparator.comparingInt((WritingExample e) -> score(e, kind, channel)).reversed())
                .limit(EXAMPLES_PER_DRAFT)
                .toList();
    }

    static int score(WritingExample e, String kind, Platform channel) {
        return (kind.equals(e.kind) ? 4 : 0) + (e.channel == channel ? 2 : 0) + (e.gotReply ? 2 : 0)
                + (e.edited ? 1 : 0) + (WritingExample.WRITTEN.equals(e.source) ? 1 : 0);
    }

    static String render(WritingExample e) {
        StringBuilder sb = new StringBuilder("<past_example kind=\"").append(e.kind).append("\" channel=\"")
                .append(e.channel).append("\" brand_replied=\"").append(e.gotReply ? "yes" : "unknown").append("\">\n");
        if (e.edited && e.aiBody != null) {
            sb.append("Your earlier draft:\n").append(clip(e.aiBody)).append("\n\nWhat the creator changed it to and sent:\n");
        } else {
            sb.append("What the creator sent:\n");
        }
        if (e.channel == Platform.EMAIL && e.sentSubject != null && !e.sentSubject.isBlank()) {
            sb.append("Subject: ").append(Untrusted.escape(e.sentSubject)).append('\n');
        }
        return sb.append(clip(e.sentBody)).append("\n</past_example>").toString();
    }

    private static String clip(String s) {
        String v = Untrusted.escape(s.strip()).replace("</past_example", "&lt;/past_example");
        return v.length() > MAX_EXAMPLE_CHARS ? v.substring(0, MAX_EXAMPLE_CHARS) + "\n[...]" : v;
    }

    private static boolean same(String a, String b) {
        return norm(a).equals(norm(b));
    }

    private static String norm(String s) {
        return s == null ? "" : s.strip().replaceAll("\\s+", " ");
    }

    // ---------------------------------------------------------------- Settings page

    public Stats stats() {
        return new Stats(settings.learnFromHistory(), examples.countByExcludedFalse(),
                examples.countByExcludedFalseAndEditedTrue(), examples.countByExcludedFalseAndGotReplyTrue());
    }

    public List<WritingExample> recent() {
        return examples.findTop50ByOrderBySentAtDesc();
    }

    /** Stop (or resume) using one message as an example. */
    @Transactional
    public WritingExample setExcluded(Long id, boolean excluded) {
        WritingExample e = examples.findById(id).orElseThrow();
        e.excluded = excluded;
        return examples.save(e);
    }
}
