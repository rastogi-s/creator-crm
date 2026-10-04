package com.creatorcrm.calendar;

import java.time.LocalDate;
import java.time.ZoneId;

/** Where deal dates go: her Google Calendar in real use, an in-memory calendar in demo mode and tests. */
public interface CalendarGateway {

    enum State {
        /** Gmail isn't connected, so there is no Google account to write to. */
        NO_GOOGLE,
        /** Gmail is connected, but calendar access wasn't granted (connected before 1.22.0, or unticked on Google's screen). */
        NEEDS_RECONNECT,
        READY
    }

    /** An all-day event. */
    record Event(String title, LocalDate date, String description) {}

    /** Something Google refused, in words she can act on. {@code reconnect} = calendar access is missing. */
    class Failure extends RuntimeException {
        public final boolean reconnect;

        public Failure(String message, boolean reconnect, Throwable cause) {
            super(message, cause);
            this.reconnect = reconnect;
        }
    }

    State state();

    /** Remembers whether the latest Google sign-in granted calendar access. */
    void recordGrant(boolean granted);

    /** The id of the app's own "Creator CRM" calendar: {@code knownId} if it still exists, otherwise a new one. */
    String ensureCalendar(String knownId, ZoneId zone);

    /** Creates or replaces an event and returns its id (a new id if the old event is gone). */
    String put(String calendarId, String eventId, Event event);

    /** Removes an event; one that is already gone is fine. */
    void delete(String calendarId, String eventId);
}
