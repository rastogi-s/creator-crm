package com.creatorcrm.calendar;

import com.creatorcrm.channels.gmail.GmailConnector;
import com.creatorcrm.domain.AppState;
import com.creatorcrm.repo.AppStateRepo;
import com.google.api.client.googleapis.json.GoogleJsonError;
import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.client.util.DateTime;
import com.google.api.services.calendar.Calendar;
import com.google.api.services.calendar.model.EventDateTime;
import com.google.auth.http.HttpCredentialsAdapter;
import java.time.ZoneId;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Her Google Calendar through the Calendar API, with the {@code calendar.app.created} scope: the app can only see and
 * change the "Creator CRM" calendar it makes, never her other calendars.
 */
@Component
public class GoogleCalendarGateway implements CalendarGateway {

    static final String GRANTED = "calendar.granted";
    static final String CALENDAR_NAME = "Creator CRM";

    private final GmailConnector gmail;
    private final AppStateRepo state;

    public GoogleCalendarGateway(GmailConnector gmail, AppStateRepo state) {
        this.gmail = gmail;
        this.state = state;
    }

    @Override
    public State state() {
        if (!gmail.isConnected()) return State.NO_GOOGLE;
        boolean granted = state.findById(GRANTED).map(s -> "true".equals(s.stateValue)).orElse(false);
        return granted ? State.READY : State.NEEDS_RECONNECT;
    }

    @Override
    public void recordGrant(boolean granted) {
        AppState s = new AppState();
        s.stateKey = GRANTED;
        s.stateValue = String.valueOf(granted);
        state.save(s);
    }

    private Calendar client() throws Exception {
        return new Calendar.Builder(GmailConnector.transport(), GmailConnector.JSON,
                GmailConnector.withRetries(new HttpCredentialsAdapter(gmail.credentials())))
                .setApplicationName("creator-crm")
                .build();
    }

    @Override
    public String ensureCalendar(String knownId, ZoneId zone) {
        try {
            Calendar c = client();
            if (knownId != null) {
                try {
                    return c.calendars().get(knownId).execute().getId();
                } catch (GoogleJsonResponseException e) {
                    if (!gone(e)) throw e;
                }
            }
            return c.calendars().insert(new com.google.api.services.calendar.model.Calendar()
                    .setSummary(CALENDAR_NAME)
                    .setDescription("Deal dates from Creator CRM: contracts to sign, content due, posting days and payments.")
                    .setTimeZone(zone.getId())).execute().getId();
        } catch (Exception e) {
            throw failure(e);
        }
    }

    @Override
    public String put(String calendarId, String eventId, Event event) {
        com.google.api.services.calendar.model.Event body = new com.google.api.services.calendar.model.Event()
                .setSummary(event.title())
                .setDescription(event.description())
                .setStart(new EventDateTime().setDate(new DateTime(event.date().toString())))
                .setEnd(new EventDateTime().setDate(new DateTime(event.date().plusDays(1).toString())))
                .setTransparency("transparent"); // an all-day reminder, not a busy block
        try {
            Calendar c = client();
            if (eventId != null) {
                try {
                    return c.events().update(calendarId, eventId, body).execute().getId();
                } catch (GoogleJsonResponseException e) {
                    if (!gone(e)) throw e;
                }
            }
            return c.events().insert(calendarId, body).execute().getId();
        } catch (Exception e) {
            throw failure(e);
        }
    }

    @Override
    public void delete(String calendarId, String eventId) {
        try {
            client().events().delete(calendarId, eventId).execute();
        } catch (GoogleJsonResponseException e) {
            if (!gone(e)) throw failure(e);
        } catch (Exception e) {
            throw failure(e);
        }
    }

    /** Deleted by her in Google Calendar: 404 Not Found, or 410 Gone for a cancelled event. */
    private static boolean gone(GoogleJsonResponseException e) {
        return e.getStatusCode() == 404 || e.getStatusCode() == 410;
    }

    private Failure failure(Exception e) {
        if (e instanceof Failure f) return f;
        if (e instanceof GoogleJsonResponseException g) {
            String reason = Optional.ofNullable(g.getDetails()).map(GoogleJsonError::getErrors)
                    .filter(l -> !l.isEmpty()).map(l -> l.get(0).getReason()).orElse("");
            String message = Optional.ofNullable(g.getDetails()).map(GoogleJsonError::getMessage).orElse("");
            if (reason.equals("accessNotConfigured") || message.contains("has not been used in project")
                    || message.contains("is disabled")) {
                return new Failure("Turn on the Google Calendar API in Google Cloud Console (the same project as Gmail), "
                        + "then press Update now.", false, e);
            }
            if (g.getStatusCode() == 401 || g.getStatusCode() == 403 && (reason.equals("insufficientPermissions")
                    || message.contains("insufficient authentication scopes"))) {
                recordGrant(false);
                return new Failure("Google didn't allow calendar access. Press Reconnect Gmail and tick the calendar box.", true, e);
            }
            return new Failure("Google Calendar said: " + (message.isBlank() ? "error " + g.getStatusCode() : message), false, e);
        }
        if (e.getMessage() != null && e.getMessage().contains("invalid_grant")) {
            return new Failure("Google signed the app out. Press Reconnect Gmail.", true, e);
        }
        return new Failure("Couldn't reach Google Calendar: " + e.getMessage(), false, e);
    }
}
