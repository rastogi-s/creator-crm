package com.creatorcrm.calendar;

import java.time.ZoneId;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** An in-memory Google Calendar for tests: what's on it, how often it was written to, and failures on demand. */
public class FakeCalendar implements CalendarGateway {

    public State state = State.READY;
    public final Set<String> calendars = new HashSet<>();
    public final Map<String, Event> events = new LinkedHashMap<>();
    public int puts;
    public int calendarsCreated;
    public RuntimeException failWith;
    private int next;

    @Override
    public State state() {
        return state;
    }

    @Override
    public void recordGrant(boolean granted) {
        state = granted ? State.READY : State.NEEDS_RECONNECT;
    }

    @Override
    public synchronized String ensureCalendar(String knownId, ZoneId zone) {
        if (failWith != null) throw failWith;
        if (knownId != null && calendars.contains(knownId)) return knownId;
        String id = "cal-" + (++calendarsCreated);
        calendars.add(id);
        return id;
    }

    @Override
    public synchronized String put(String calendarId, String eventId, Event event) {
        if (failWith != null) throw failWith;
        puts++;
        String id = eventId != null && events.containsKey(eventId) ? eventId : "ev-" + (++next);
        events.put(id, event);
        return id;
    }

    @Override
    public synchronized void delete(String calendarId, String eventId) {
        if (failWith != null) throw failWith;
        events.remove(eventId);
    }
}
