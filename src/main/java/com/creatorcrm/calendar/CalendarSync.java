package com.creatorcrm.calendar;

import com.creatorcrm.calendar.CalendarGateway.Event;
import com.creatorcrm.calendar.CalendarGateway.State;
import com.creatorcrm.domain.AppState;
import com.creatorcrm.domain.Deadline;
import com.creatorcrm.domain.Enums.DeadlineType;
import com.creatorcrm.domain.Opportunity;
import com.creatorcrm.repo.AppStateRepo;
import com.creatorcrm.repo.DeadlineRepo;
import com.creatorcrm.repo.OpportunityRepo;
import com.creatorcrm.settings.SettingsService;
import com.creatorcrm.workflow.WorkflowEngine;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Keeps the "Creator CRM" calendar in step with deal deadlines: every open deadline on an open deal is an all-day event,
 * changed when its date or wording changes and removed when it's done, the deal closes, or she turns the calendar off.
 * Only the deadlines that changed since the last run call Google.
 */
@Service
public class CalendarSync {
    private static final Logger log = LoggerFactory.getLogger(CalendarSync.class);

    static final String CALENDAR_ID = "calendar.id";
    static final String LAST_RUN = "calendar.lastRun";
    static final String LAST_ERROR = "calendar.lastError";
    /** Deadlines this far in the past stay on the calendar, so a missed date is still visible. */
    static final int KEEP_PAST_DAYS = 14;

    public record Result(int added, int changed, int removed) {
        public static final Result NONE = new Result(0, 0, 0);
    }

    public record Status(String state, boolean on, long events, OffsetDateTime lastRun, String error) {}

    private final CalendarGateway gateway;
    private final DeadlineRepo deadlines;
    private final OpportunityRepo opportunities;
    private final WorkflowEngine workflow;
    private final SettingsService settings;
    private final AppStateRepo state;

    public CalendarSync(CalendarGateway gateway, DeadlineRepo deadlines, OpportunityRepo opportunities,
                        WorkflowEngine workflow, SettingsService settings, AppStateRepo state) {
        this.gateway = gateway;
        this.deadlines = deadlines;
        this.opportunities = opportunities;
        this.workflow = workflow;
        this.settings = settings;
        this.state = state;
    }

    public Status status() {
        long events = deadlines.findAll().stream().filter(d -> d.calendarEventId != null).count();
        OffsetDateTime last = get(LAST_RUN) == null ? null : OffsetDateTime.parse(get(LAST_RUN));
        String error = get(LAST_ERROR);
        return new Status(gateway.state().name(), settings.calendarSync(), events, last, error == null || error.isBlank() ? null : error);
    }

    /** Never throws: a Google problem is kept for Settings to show, and the next run tries again. */
    public synchronized Result sync() {
        if (gateway.state() != State.READY) return Result.NONE;
        try {
            Result r = run();
            put(LAST_RUN, OffsetDateTime.now().toString());
            put(LAST_ERROR, "");
            if (!r.equals(Result.NONE)) log.info("Calendar: {} added, {} changed, {} removed", r.added(), r.changed(), r.removed());
            return r;
        } catch (CalendarGateway.Failure e) {
            log.warn("Calendar sync failed: {}", e.getMessage());
            put(LAST_ERROR, e.getMessage());
            return Result.NONE;
        }
    }

    private Result run() {
        boolean on = settings.calendarSync();
        List<Deadline> all = deadlines.findAll();
        String calendarId = get(CALENDAR_ID);
        if (!on && all.stream().noneMatch(d -> d.calendarEventId != null)) return Result.NONE;
        if (on) {
            String id = gateway.ensureCalendar(calendarId, settings.zone());
            if (!id.equals(calendarId)) {
                // A new calendar (first run, or she deleted the old one): nothing is on it yet.
                put(CALENDAR_ID, id);
                calendarId = id;
                for (Deadline d : all) {
                    if (d.calendarEventId == null) continue;
                    d.calendarEventId = null;
                    d.calendarFingerprint = null;
                    deadlines.save(d);
                }
            }
        }
        Map<Long, Opportunity> deals = opportunities.findAllById(all.stream().map(d -> d.opportunityId).distinct().toList())
                .stream().collect(Collectors.toMap(o -> o.id, Function.identity()));
        LocalDate keepFrom = settings.today().minusDays(KEEP_PAST_DAYS);
        int added = 0, changed = 0, removed = 0;
        for (Deadline d : all) {
            Opportunity o = deals.get(d.opportunityId);
            boolean wanted = on && !d.done && d.dueDate != null && !d.dueDate.isBefore(keepFrom) && o != null && o.status.isOpen();
            if (wanted) {
                Event e = event(d, o);
                String fingerprint = fingerprint(e);
                if (d.calendarEventId != null && fingerprint.equals(d.calendarFingerprint)) continue;
                boolean isNew = d.calendarEventId == null;
                d.calendarEventId = gateway.put(calendarId, d.calendarEventId, e);
                d.calendarFingerprint = fingerprint;
                deadlines.save(d);
                if (isNew) added++; else changed++;
            } else if (d.calendarEventId != null) {
                if (calendarId != null) gateway.delete(calendarId, d.calendarEventId);
                d.calendarEventId = null;
                d.calendarFingerprint = null;
                deadlines.save(d);
                removed++;
            }
        }
        return new Result(added, changed, removed);
    }

    Event event(Deadline d, Opportunity o) {
        String brand = workflow.brandName(o);
        StringBuilder text = new StringBuilder();
        if (d.description != null && !d.description.isBlank()) text.append(d.description.strip()).append("\n\n");
        if (o.campaign != null && !o.campaign.isBlank()) text.append("Campaign: ").append(o.campaign.strip()).append('\n');
        if (o.deliverables != null && !o.deliverables.isBlank()) text.append("Deliverables: ").append(o.deliverables.strip()).append('\n');
        text.append("\nAdded by Creator CRM. Change dates in the app; edits made here are replaced.");
        return new Event(label(d.type) + ": " + brand, d.dueDate, text.toString());
    }

    static String label(DeadlineType type) {
        if (type == null) return "📌 Deadline";
        return switch (type) {
            case CONTENT_DUE -> "🎬 Content due";
            case CONTRACT -> "✍️ Contract";
            case APPLICATION -> "📝 Application";
            case POSTING -> "📸 Post";
            case APPROVAL -> "👀 Approval";
            case LAUNCH -> "🚀 Launch";
            case PAYMENT -> "💵 Payment";
            case OTHER -> "📌 Deadline";
        };
    }

    static String fingerprint(Event e) {
        return Integer.toHexString(Objects.hash(e.title(), e.date(), e.description()));
    }

    private String get(String key) {
        return state.findById(key).map(s -> s.stateValue).orElse(null);
    }

    private void put(String key, String value) {
        AppState s = new AppState();
        s.stateKey = key;
        s.stateValue = value;
        state.save(s);
    }
}
