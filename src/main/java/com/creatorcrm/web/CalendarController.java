package com.creatorcrm.web;

import com.creatorcrm.calendar.CalendarSync;
import com.creatorcrm.calendar.Exclusivity;
import com.creatorcrm.settings.SettingsService;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Google Calendar: whether deal dates are on it, turning that on or off, and exclusivity clashes on a deal. */
@RestController
@RequestMapping("/api")
public class CalendarController {

    public record Toggle(boolean on) {}

    private final CalendarSync calendar;
    private final Exclusivity exclusivity;
    private final SettingsService settings;

    public CalendarController(CalendarSync calendar, Exclusivity exclusivity, SettingsService settings) {
        this.calendar = calendar;
        this.exclusivity = exclusivity;
        this.settings = settings;
    }

    @GetMapping("/calendar")
    public CalendarSync.Status status() {
        return calendar.status();
    }

    /** Turning it off takes the app's events off her calendar straight away. */
    @PostMapping("/calendar")
    public CalendarSync.Status toggle(@RequestBody Toggle body) {
        settings.update(Map.of(SettingsService.CALENDAR_SYNC, String.valueOf(body.on())));
        calendar.sync();
        return calendar.status();
    }

    @PostMapping("/calendar/sync")
    public CalendarSync.Status syncNow() {
        calendar.sync();
        return calendar.status();
    }

    @GetMapping("/opportunities/{id}/exclusivity")
    public List<Exclusivity.Overlap> overlaps(@PathVariable Long id) {
        return exclusivity.forDeal(id);
    }
}
