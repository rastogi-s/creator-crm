package com.creatorcrm.campaigns;

import com.creatorcrm.contacts.ContactService;
import com.creatorcrm.contacts.Emails;
import com.creatorcrm.contacts.OptOut;
import com.creatorcrm.domain.CampaignEvent;
import com.creatorcrm.domain.CampaignTarget;
import com.creatorcrm.domain.CampaignTarget.State;
import com.creatorcrm.domain.Enums.Direction;
import com.creatorcrm.domain.Message;
import com.creatorcrm.domain.Suppression;
import com.creatorcrm.repo.CampaignEventRepo;
import com.creatorcrm.repo.CampaignTargetRepo;
import com.creatorcrm.repo.MessageRepo;
import com.creatorcrm.repo.OpportunityRepo;
import java.time.OffsetDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads new emails for what they mean to campaigns, with plain code (no Claude):
 * <ul>
 *   <li>Anyone at a brand writes back: the brand's campaign stops (no more follow-ups, nobody else there is pitched).</li>
 *   <li>"No thanks", "remove me" and the like: that address goes on the do-not-email list for good.</li>
 *   <li>A bounce: the address goes on the do-not-email list, and the brand's next-ranked person is tried instead.</li>
 * </ul>
 * Two bounces or one opt-out in a day (counted since she last pressed Resume) pause all campaign sending.
 */
@Service
public class CampaignWatcher {
    private static final Logger log = LoggerFactory.getLogger(CampaignWatcher.class);

    static final int BOUNCES_TO_PAUSE = 2;
    static final int OPT_OUTS_TO_PAUSE = 1;

    private static final Pattern BOUNCE_SENDER = Pattern.compile("(?i)^(mailer-daemon|postmaster|mail-daemon|mail\\.delivery\\.subsystem)@.*");
    private static final Pattern BOUNCE_SUBJECT = Pattern.compile("(?i)(delivery status notification|undeliver|delivery has failed|"
            + "delivery failure|mail delivery (failed|subsystem)|returned mail|failure notice|address not found|message not delivered)");
    private static final Pattern QUOTE_START = Pattern.compile("(?m)^(On .+wrote:|-----Original Message-----|>|From: )");
    /** The answer the opt-out line in every campaign email asks for, and close cousins. Short replies only. */
    private static final Pattern NO_THANKS = Pattern.compile("(?i)^\\W*(no,? thanks?( you)?|no thank you|not interested|"
            + "we('re| are) not interested|please don'?t (email|contact) (me|us))\\b.*");

    private final MessageRepo messages;
    private final CampaignTargetRepo targets;
    private final CampaignEventRepo events;
    private final OpportunityRepo opportunities;
    private final ContactService contacts;
    private final CampaignService service;
    private final CampaignState state;

    public CampaignWatcher(MessageRepo messages, CampaignTargetRepo targets, CampaignEventRepo events,
                           OpportunityRepo opportunities, ContactService contacts, CampaignService service, CampaignState state) {
        this.messages = messages;
        this.targets = targets;
        this.events = events;
        this.opportunities = opportunities;
        this.contacts = contacts;
        this.service = service;
        this.state = state;
    }

    /** Looks at every email that arrived since the last look. Returns how many changed a campaign. */
    @Transactional
    public int scan() {
        long last = state.lastScanned();
        if (last < 0) {
            // First run: start from now; older mail was never a reply to a campaign
            Long max = messages.maxId();
            state.lastScanned(max == null ? 0 : max);
            return 0;
        }
        int changed = 0;
        List<Message> batch = messages.findTop500ByIdGreaterThanOrderByIdAsc(last);
        for (Message m : batch) {
            last = m.id;
            if (m.direction != Direction.INBOUND || events.existsByMessageId(m.id)) continue;
            if (handle(m)) changed++;
        }
        state.lastScanned(last);
        return changed;
    }

    boolean handle(Message m) {
        if (isBounce(m)) return bounce(m);
        Long brandId = brandOf(m);
        if (brandId == null) return false;
        List<CampaignTarget> live = targets.findByBrandId(brandId).stream()
                .filter(t -> CampaignService.ACTIVE_OR_SENT.contains(t.state)).toList();
        if (live.isEmpty()) return false;
        boolean stop = asksToStop(m.content);
        String who = m.senderName != null && !m.senderName.isBlank() ? m.senderName.strip() : m.sender;
        boolean answeredPitch = false;
        for (CampaignTarget t : live) {
            if (t.state == State.SENT) {
                if (m.sentAt != null && t.sentAt != null && m.sentAt.isBefore(t.sentAt)) continue;
                service.end(t, stop ? State.OPTED_OUT : State.REPLIED, (stop ? "Asked not to be emailed: " : "Replied: ") + who);
                answeredPitch = true;
            } else {
                service.end(t, State.STOPPED, "The brand wrote to you (" + who + ") before this was sent");
            }
        }
        String email = Emails.clean(m.sender);
        if (stop && email != null) {
            contacts.doNotEmail(email, Suppression.Reason.OPTED_OUT);
            record(CampaignEvent.Kind.OPT_OUT, brandId, email, m.id, live.get(0).id);
            checkPause();
        } else if (answeredPitch) {
            record(CampaignEvent.Kind.REPLY, brandId, email, m.id, live.get(0).id);
        }
        return true;
    }

    /** The brand an email is about: the campaign deal whose thread it's in, else whoever owns the sender's address. */
    private Long brandOf(Message m) {
        if (m.conversationId != null) {
            Optional<Long> viaDeal = opportunities.findFirstByConversationIdOrderByIdDesc(m.conversationId)
                    .flatMap(o -> targets.findFirstByOpportunityIdOrderByIdDesc(o.id)).map(t -> t.brandId);
            if (viaDeal.isPresent()) return viaDeal.get();
        }
        return contacts.brandFor(m.sender).map(b -> b.id).orElse(null);
    }

    private boolean bounce(Message m) {
        Set<String> failed = new LinkedHashSet<>(Emails.findAll((m.subject == null ? "" : m.subject) + "\n" + (m.content == null ? "" : m.content)));
        OffsetDateTime month = OffsetDateTime.now().minusDays(30);
        boolean any = false;
        for (String addr : failed) {
            for (CampaignTarget t : targets.findByStateIn(Set.of(State.SENT))) {
                if (!addr.equals(t.email) || t.sentAt == null || t.sentAt.isBefore(month)) continue;
                contacts.doNotEmail(addr, Suppression.Reason.BOUNCED);
                service.end(t, State.BOUNCED, "Bounced: " + addr + " doesn't accept email");
                record(CampaignEvent.Kind.BOUNCE, t.brandId, addr, any ? null : m.id, t.id);
                any = true;
            }
        }
        if (any) checkPause();
        return any;
    }

    static boolean isBounce(Message m) {
        return (m.sender != null && BOUNCE_SENDER.matcher(m.sender.strip()).matches())
                || (m.subject != null && BOUNCE_SUBJECT.matcher(m.subject).find());
    }

    /** "Remove me", or a short "no thanks" (what the footer asks people to reply). */
    static boolean asksToStop(String body) {
        if (OptOut.asksToStop(body)) return true;
        if (body == null) return false;
        String own = body;
        var q = QUOTE_START.matcher(own);
        if (q.find()) own = own.substring(0, q.start());
        own = own.strip();
        return !own.isEmpty() && own.length() <= 120 && NO_THANKS.matcher(own.toLowerCase(Locale.ROOT)).matches();
    }

    private void record(CampaignEvent.Kind kind, Long brandId, String email, Long messageId, Long targetId) {
        CampaignEvent e = new CampaignEvent();
        e.kind = kind;
        e.brandId = brandId;
        e.email = email;
        e.messageId = messageId;
        e.targetId = targetId;
        e.at = OffsetDateTime.now();
        events.save(e);
    }

    private void checkPause() {
        OffsetDateTime today = state.pauseCountFrom();
        long bounces = events.countByKindAndAtGreaterThanEqual(CampaignEvent.Kind.BOUNCE, today);
        long optOuts = events.countByKindAndAtGreaterThanEqual(CampaignEvent.Kind.OPT_OUT, today);
        String why = bounces >= BOUNCES_TO_PAUSE
                ? bounces + " campaign emails bounced today. Sending is paused so your Gmail stays healthy: check the list, then press Resume."
                : optOuts >= OPT_OUTS_TO_PAUSE
                ? "Someone asked not to be emailed today. Sending is paused so you can check the list and template, then press Resume."
                : null;
        if (why != null && state.pausedReason().isEmpty()) {
            state.pause(why);
            log.warn("Campaigns paused: {}", why);
        }
    }
}
