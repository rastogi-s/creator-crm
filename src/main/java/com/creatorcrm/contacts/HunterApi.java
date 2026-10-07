package com.creatorcrm.contacts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Hunter.io's API: everyone it knows at a company's domain, and whether one address really exists.
 * Its data is licensed for her own outreach only, so contacts it supplies are tagged HUNTER and never shared.
 * The key goes in a header, never in the URL, so it can't end up in a log.
 */
@Component
public class HunterApi {
    static final String BASE = "https://api.hunter.io/v2/";

    /** Hunter's departments that hold the people who run creator partnerships. */
    static final String PARTNERSHIP_DEPARTMENTS = "marketing,communication,executive,management";

    /** A person at the domain. {@code verification}: Hunter's own last check (valid, invalid, accept_all, unknown), or null. */
    public record Person(String email, String firstName, String lastName, String position, String department,
                         String type, Integer confidence, String linkedin, String phone, String verification,
                         String verifiedOn, String sourceUrl) {
        public String name() {
            String n = ((firstName == null ? "" : firstName) + " " + (lastName == null ? "" : lastName)).strip();
            return n.isEmpty() ? null : n;
        }
    }

    /** {@code acceptAll}: the domain accepts every address, so no single address can be proven to exist. */
    public record DomainResult(String organization, boolean acceptAll, String pattern, int total, List<Person> people) {}

    /** {@code result}: deliverable, undeliverable or risky; {@code status}: valid, invalid, accept_all, webmail, disposable, unknown. */
    public record Verification(String status, String result, Integer score, boolean pending) {}

    /** Her Hunter plan, straight from Hunter. {@code used}/{@code available} are this period's credits. */
    public record Account(String plan, double used, double available, String resetDate) {}

    /** Something Hunter said no to, in plain words. */
    public static class HunterException extends IllegalStateException {
        public final int status;

        public HunterException(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    public record Reply(int status, String body) {}

    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    /** Up to {@code limit} people at the domain, optionally only in the given Hunter departments. */
    public DomainResult domainSearch(String key, String domain, String departments, int limit) {
        Map<String, String> q = new LinkedHashMap<>();
        q.put("domain", domain);
        q.put("limit", String.valueOf(limit));
        if (departments != null) q.put("department", departments);
        return parseDomain(ok(call(key, "domain-search", q)));
    }

    public Verification verify(String key, String email) {
        Reply r = call(key, "email-verifier", Map.of("email", email));
        if (r.status() == 202) return new Verification("unknown", null, null, true); // still checking; ask again later
        return parseVerification(ok(r));
    }

    /** Doesn't cost a credit. */
    public Account account(String key) {
        return parseAccount(ok(call(key, "account", Map.of())));
    }

    /** One GET to the Hunter API. Demo mode overrides this so videos never touch the real service. */
    protected Reply call(String key, String path, Map<String, String> query) {
        String qs = query.entrySet().stream()
                .map(e -> e.getKey() + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
        HttpRequest req = HttpRequest.newBuilder(URI.create(BASE + path + (qs.isEmpty() ? "" : "?" + qs)))
                .timeout(Duration.ofSeconds(30)).header("X-API-KEY", key).header("Accept", "application/json").GET().build();
        try {
            HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
            return new Reply(r.statusCode(), r.body());
        } catch (IOException e) {
            throw new HunterException(0, "Couldn't reach Hunter. Check the internet connection and try again.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new HunterException(0, "Stopped before Hunter answered.");
        }
    }

    static JsonNode ok(Reply r) {
        if (r.status() >= 200 && r.status() < 300) {
            try {
                return JSON.readTree(r.body());
            } catch (IOException e) {
                throw new HunterException(r.status(), "Hunter sent back something the app couldn't read. Try again later.");
            }
        }
        throw new HunterException(r.status(), switch (r.status()) {
            case 401 -> "Hunter didn't accept the key. Copy it again from hunter.io (API keys) into Settings, Accounts.";
            case 429 -> "Hunter says this month's credits are used up. They come back when your Hunter month resets.";
            case 403 -> "Hunter is busy (too many requests at once). Try again in a minute.";
            case 451 -> "This person asked Hunter to remove their details, so Hunter can't check them.";
            case 400, 422 -> "Hunter couldn't use that: " + firstError(r.body());
            default -> "Hunter had a problem (" + r.status() + "). Try again later.";
        });
    }

    private static String firstError(String body) {
        try {
            JsonNode e = JSON.readTree(body).path("errors").path(0);
            String d = e.path("details").asText("");
            return d.isBlank() ? "it said the request was wrong." : d;
        } catch (IOException e) {
            return "it said the request was wrong.";
        }
    }

    static DomainResult parseDomain(JsonNode root) {
        JsonNode d = root.path("data");
        List<Person> people = new ArrayList<>();
        for (JsonNode e : d.path("emails")) {
            String email = text(e, "value");
            if (email == null) continue;
            JsonNode v = e.path("verification");
            String source = null;
            for (JsonNode s : e.path("sources")) {
                source = text(s, "uri");
                if (source != null) break;
            }
            people.add(new Person(email, text(e, "first_name"), text(e, "last_name"), text(e, "position"),
                    text(e, "department"), text(e, "type"), e.hasNonNull("confidence") ? e.get("confidence").asInt() : null,
                    text(e, "linkedin"), text(e, "phone_number"), text(v, "status"), text(v, "date"), source));
        }
        return new DomainResult(text(d, "organization"), d.path("accept_all").asBoolean(false), text(d, "pattern"),
                root.path("meta").path("results").asInt(people.size()), people);
    }

    static Verification parseVerification(JsonNode root) {
        JsonNode d = root.path("data");
        return new Verification(text(d, "status"), text(d, "result"),
                d.hasNonNull("score") ? d.get("score").asInt() : null, false);
    }

    /** Newer plans count one pool of credits; older ones count searches and verifications apart. */
    static Account parseAccount(JsonNode root) {
        JsonNode d = root.path("data");
        JsonNode req = d.path("requests");
        double used, available;
        if (req.has("credits")) {
            used = req.path("credits").path("used").asDouble();
            available = req.path("credits").path("available").asDouble();
        } else {
            used = req.path("searches").path("used").asDouble() + req.path("verifications").path("used").asDouble();
            available = req.path("searches").path("available").asDouble() + req.path("verifications").path("available").asDouble();
        }
        return new Account(text(d, "plan_name"), used, available, text(d, "reset_date"));
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n == null ? null : n.get(field);
        if (v == null || v.isNull()) return null;
        String s = v.asText().strip();
        return s.isEmpty() ? null : s;
    }
}
