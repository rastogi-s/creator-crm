package com.creatorcrm.campaigns;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Locale;
import java.util.Map;

/**
 * Campaign emails go out on weekdays between 9am and 5pm where the recipient is. The place is guessed from the
 * address's country domain (.co.uk, .de, .com.au); anything else (.com) uses her own time zone.
 */
public final class RecipientHours {
    private RecipientHours() {}

    static final LocalTime START = LocalTime.of(9, 0);
    static final LocalTime END = LocalTime.of(17, 0);

    private static final Map<String, String> ZONES = Map.ofEntries(
            Map.entry("uk", "Europe/London"), Map.entry("ie", "Europe/Dublin"), Map.entry("de", "Europe/Berlin"),
            Map.entry("fr", "Europe/Paris"), Map.entry("es", "Europe/Madrid"), Map.entry("it", "Europe/Rome"),
            Map.entry("nl", "Europe/Amsterdam"), Map.entry("be", "Europe/Brussels"), Map.entry("se", "Europe/Stockholm"),
            Map.entry("dk", "Europe/Copenhagen"), Map.entry("no", "Europe/Oslo"), Map.entry("fi", "Europe/Helsinki"),
            Map.entry("pl", "Europe/Warsaw"), Map.entry("pt", "Europe/Lisbon"), Map.entry("ch", "Europe/Zurich"),
            Map.entry("at", "Europe/Vienna"), Map.entry("in", "Asia/Kolkata"), Map.entry("sg", "Asia/Singapore"),
            Map.entry("ae", "Asia/Dubai"), Map.entry("jp", "Asia/Tokyo"), Map.entry("kr", "Asia/Seoul"),
            Map.entry("au", "Australia/Sydney"), Map.entry("nz", "Pacific/Auckland"), Map.entry("ca", "America/Toronto"),
            Map.entry("mx", "America/Mexico_City"), Map.entry("br", "America/Sao_Paulo"), Map.entry("za", "Africa/Johannesburg"));

    public static ZoneId zoneFor(String email, ZoneId fallback) {
        if (email == null) return fallback;
        String e = email.strip().toLowerCase(Locale.ROOT);
        int dot = e.lastIndexOf('.');
        if (dot < 0 || e.indexOf('@') < 0) return fallback;
        String zone = ZONES.get(e.substring(dot + 1));
        return zone == null ? fallback : ZoneId.of(zone);
    }

    public static boolean isWorkingTime(String email, ZonedDateTime now, ZoneId fallback) {
        ZonedDateTime there = now.withZoneSameInstant(zoneFor(email, fallback));
        DayOfWeek d = there.getDayOfWeek();
        if (d == DayOfWeek.SATURDAY || d == DayOfWeek.SUNDAY) return false;
        LocalTime t = there.toLocalTime();
        return !t.isBefore(START) && t.isBefore(END);
    }
}
