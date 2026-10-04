package com.creatorcrm.demo;

import com.creatorcrm.calendar.CalendarGateway;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Demo mode only: a pretend Google Calendar that is always connected and keeps events in memory. */
@Component
@Primary
@Profile("demo")
public class DemoCalendar implements CalendarGateway {

    private final Map<String, Event> events = new ConcurrentHashMap<>();

    @Override
    public State state() {
        return State.READY;
    }

    @Override
    public void recordGrant(boolean granted) {}

    @Override
    public String ensureCalendar(String knownId, ZoneId zone) {
        return knownId != null ? knownId : "demo-calendar";
    }

    @Override
    public String put(String calendarId, String eventId, Event event) {
        String id = eventId != null ? eventId : UUID.randomUUID().toString();
        events.put(id, event);
        return id;
    }

    @Override
    public void delete(String calendarId, String eventId) {
        events.remove(eventId);
    }
}
