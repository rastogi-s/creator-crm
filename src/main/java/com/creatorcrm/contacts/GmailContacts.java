package com.creatorcrm.contacts;

import com.creatorcrm.domain.Brand;
import com.creatorcrm.domain.BrandContact;
import com.creatorcrm.domain.ContactSource;
import com.creatorcrm.domain.Conversation;
import com.creatorcrm.domain.Enums.Direction;
import com.creatorcrm.domain.Enums.Platform;
import com.creatorcrm.domain.Message;
import com.creatorcrm.domain.Opportunity;
import com.creatorcrm.domain.Suppression;
import com.creatorcrm.repo.BrandContactRepo;
import com.creatorcrm.repo.BrandRepo;
import com.creatorcrm.repo.ConversationRepo;
import com.creatorcrm.repo.MessageRepo;
import com.creatorcrm.repo.OpportunityRepo;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Builds the contacts database from what's already in the app, with no Claude and no network: every brand's saved
 * contact and website, and every person in her brand email threads (who wrote, who she wrote to, reply-to addresses,
 * job titles and phone numbers from signatures). It also counts, per address, how often she emailed them, how often
 * they replied, how fast, and how many deals came of it, which is what ranks people who have replied. A reply asking
 * her to stop puts that address on the do-not-email list.
 */
@Service
public class GmailContacts {
    private static final Logger log = LoggerFactory.getLogger(GmailContacts.class);

    public record Result(int contacts, int added, int brands) {}

    private static final class Stats {
        Long brandId;
        String name;
        int sent;
        int replies;
        long replyHoursTotal;
        int replyTimes;
        OffsetDateTime lastContacted;
        OffsetDateTime lastReplied;
        final Set<Long> deals = new HashSet<>();
        SignatureParser.Signature signature;
    }

    private final ContactService contacts;
    private final BrandContactRepo contactRepo;
    private final BrandRepo brands;
    private final ConversationRepo conversations;
    private final MessageRepo messages;
    private final OpportunityRepo opportunities;

    public GmailContacts(ContactService contacts, BrandContactRepo contactRepo, BrandRepo brands,
                         ConversationRepo conversations, MessageRepo messages, OpportunityRepo opportunities) {
        this.contacts = contacts;
        this.contactRepo = contactRepo;
        this.brands = brands;
        this.conversations = conversations;
        this.messages = messages;
        this.opportunities = opportunities;
    }

    /** First start after this update: build the contacts list from what's already in the app. */
    @EventListener(ApplicationReadyEvent.class)
    public void firstRun() {
        try {
            if (contactRepo.count() == 0 && brands.count() > 0) refresh();
        } catch (RuntimeException e) {
            log.warn("Could not build brand contacts: {}", e.getMessage());
        }
    }

    @Transactional
    public Result refresh() {
        long before = contactRepo.count();
        // 1. What brands already have saved
        for (Brand b : brands.findAll()) {
            contacts.claimDomain(b.id, Emails.domainOfUrl(b.website));
            if (b.contactEmail != null && !b.contactEmail.isBlank()) {
                contacts.save(b.id, ContactService.Found.of(b.contactEmail, b.contactName, ContactSource.Kind.MANUAL, null));
            }
        }

        // 2. Her brand email threads
        List<Conversation> threads = conversations.findAll().stream()
                .filter(c -> c.platform == Platform.EMAIL && c.brandId != null && !Boolean.FALSE.equals(c.brandRelated))
                .toList();
        Map<Long, List<Message>> byThread = new HashMap<>();
        Set<String> own = new HashSet<>();
        for (Conversation c : threads) {
            List<Message> list = messages.findByConversationIdOrderBySentAtAsc(c.id);
            byThread.put(c.id, list);
            for (Message m : list) {
                if (m.direction == Direction.OUTBOUND) {
                    String me = Emails.clean(m.sender);
                    if (me != null) own.add(me);
                }
            }
        }
        Map<String, Stats> stats = new LinkedHashMap<>();
        Set<String> optOuts = new HashSet<>();
        for (Conversation c : threads) {
            OffsetDateTime lastOut = null;
            Set<String> repliedHere = new HashSet<>();
            for (Message m : byThread.get(c.id)) {
                if (m.direction == Direction.OUTBOUND) {
                    for (String to : Emails.findAll(m.recipient)) {
                        if (own.contains(to)) continue;
                        Stats s = stats(stats, to, c.brandId);
                        s.sent++;
                        if (m.sentAt != null && (s.lastContacted == null || m.sentAt.isAfter(s.lastContacted))) s.lastContacted = m.sentAt;
                    }
                    if (m.sentAt != null) lastOut = m.sentAt;
                } else {
                    String from = Emails.clean(m.sender);
                    if (from == null || own.contains(from)) continue;
                    Stats s = stats(stats, from, c.brandId);
                    s.replies++;
                    repliedHere.add(from);
                    if (s.name == null && m.senderName != null && !m.senderName.isBlank()) s.name = m.senderName.strip();
                    if (m.sentAt != null && (s.lastReplied == null || m.sentAt.isAfter(s.lastReplied))) s.lastReplied = m.sentAt;
                    if (lastOut != null && OptOut.asksToStop(m.content)) optOuts.add(from);
                    if (lastOut != null && m.sentAt != null && m.sentAt.isAfter(lastOut)) {
                        s.replyHoursTotal += Duration.between(lastOut, m.sentAt).toHours();
                        s.replyTimes++;
                        lastOut = null;
                    }
                    SignatureParser.Signature sig = SignatureParser.parse(m.content, m.senderName);
                    if (sig.title() != null || sig.phone() != null) s.signature = sig;
                    for (String rt : Emails.findAll(m.replyTo)) {
                        if (!rt.equals(from) && !own.contains(rt)) stats(stats, rt, c.brandId);
                    }
                }
            }
            // A deal the brand said yes to counts for everyone at the brand who wrote in that thread
            Opportunity o = opportunities.findFirstByConversationIdOrderByIdDesc(c.id).orElse(null);
            if (o != null && o.stage != null) {
                for (String who : repliedHere) stats.get(who).deals.add(o.id);
            }
        }

        // 3. Save and count
        Set<Long> touched = new HashSet<>();
        for (Map.Entry<String, Stats> e : stats.entrySet()) {
            Stats s = e.getValue();
            SignatureParser.Signature sig = s.signature;
            contacts.save(s.brandId, new ContactService.Found(e.getKey(), s.name, sig == null ? null : sig.title(), null,
                    sig == null ? null : sig.phone(), null, null, null, ContactSource.Kind.GMAIL, null));
        }
        // Someone who answered one of her emails with "please remove me" is never emailed again
        for (String who : optOuts) contacts.doNotEmail(who, Suppression.Reason.OPTED_OUT);
        OffsetDateTime now = OffsetDateTime.now();
        List<BrandContact> all = contactRepo.findAll();
        for (BrandContact c : all) {
            Stats s = stats.get(c.email);
            c.emailsSent = s == null ? 0 : s.sent;
            c.replies = s == null ? 0 : s.replies;
            c.dealsWon = s == null ? 0 : s.deals.size();
            c.avgReplyHours = s == null || s.replyTimes == 0 ? null : (int) (s.replyHoursTotal / s.replyTimes);
            c.lastContactedAt = s == null ? null : s.lastContacted;
            c.lastRepliedAt = s == null ? null : s.lastReplied;
            ContactService.rescore(c, now);
            touched.add(c.brandId);
        }
        contactRepo.saveAll(all);
        contactRepo.flush();
        touched.forEach(contacts::refreshPrimary);
        Result r = new Result(all.size(), (int) (all.size() - before), touched.size());
        log.info("Contacts: {} people across {} brands ({} new)", r.contacts(), r.brands(), r.added());
        return r;
    }

    private static Stats stats(Map<String, Stats> map, String email, Long brandId) {
        return map.computeIfAbsent(email, k -> {
            Stats s = new Stats();
            s.brandId = brandId;
            return s;
        });
    }
}
