package com.creatorcrm.contacts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Apollo.io's API, an optional second finder for when Hunter knows nobody at a brand. Searching people by company
 * and job title is free; getting one person's email ("enrichment") costs one Apollo credit. Like Hunter, its data
 * is licensed for her own outreach only (tagged APOLLO, never shared).
 */
@Component
public class ApolloApi {
    static final String BASE = "https://api.apollo.io/api/v1/";

    /** Job titles of the people who answer creator pitches. */
    static final List<String> TITLES = List.of("influencer marketing", "creator partnerships", "brand partnerships",
            "partnerships", "social media", "public relations", "brand manager", "marketing manager", "head of marketing");

    /** {@code email} is null until the person is enriched. */
    public record Person(String id, String name, String title, String linkedin, String email, String emailStatus) {}

    public static class ApolloException extends IllegalStateException {
        public ApolloException(String message) {
            super(message);
        }
    }

    public record Reply(int status, String body) {}

    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    /** People at the domain with partnership-type titles. Free: no emails yet. */
    public List<Person> search(String key, String domain, int limit) {
        ObjectNode body = JSON.createObjectNode();
        body.putArray("q_organization_domains_list").add(domain);
        TITLES.forEach(body.putArray("person_titles")::add);
        body.put("page", 1);
        body.put("per_page", limit);
        return parsePeople(ok(post(key, "mixed_people/api_search", body.toString())).path("people"));
    }

    /** One person's work email. Costs one Apollo credit. */
    public Optional<Person> reveal(String key, String id) {
        ObjectNode body = JSON.createObjectNode();
        body.put("id", id);
        body.put("reveal_personal_emails", false);
        JsonNode p = ok(post(key, "people/match", body.toString())).path("person");
        if (p.isMissingNode() || p.isNull()) return Optional.empty();
        return Optional.of(person(p));
    }

    /** One POST to the Apollo API. Demo mode overrides this. */
    protected Reply post(String key, String path, String json) {
        HttpRequest req = HttpRequest.newBuilder(URI.create(BASE + path)).timeout(Duration.ofSeconds(30))
                .header("X-Api-Key", key).header("Content-Type", "application/json").header("Cache-Control", "no-cache")
                .POST(HttpRequest.BodyPublishers.ofString(json)).build();
        try {
            HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
            return new Reply(r.statusCode(), r.body());
        } catch (IOException e) {
            throw new ApolloException("Couldn't reach Apollo. Check the internet connection and try again.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApolloException("Stopped before Apollo answered.");
        }
    }

    static JsonNode ok(Reply r) {
        if (r.status() >= 200 && r.status() < 300) {
            try {
                return JSON.readTree(r.body());
            } catch (IOException e) {
                throw new ApolloException("Apollo sent back something the app couldn't read. Try again later.");
            }
        }
        throw new ApolloException(switch (r.status()) {
            case 401 -> "Apollo didn't accept the key. Copy it again from Apollo (Settings, Integrations, API) into Settings, Accounts.";
            case 403 -> "Apollo says this key can't search people. People search needs a master API key on an Apollo plan that includes the API.";
            case 422 -> "Apollo says you're out of credits, or the request was wrong.";
            case 429 -> "Apollo is busy or this month's limit is reached. Try again later.";
            default -> "Apollo had a problem (" + r.status() + "). Try again later.";
        });
    }

    static List<Person> parsePeople(JsonNode arr) {
        List<Person> out = new ArrayList<>();
        for (JsonNode p : arr) out.add(person(p));
        return out;
    }

    static Person person(JsonNode p) {
        String name = text(p, "name");
        if (name == null) {
            String first = text(p, "first_name"), last = text(p, "last_name");
            String n = ((first == null ? "" : first) + " " + (last == null ? "" : last)).strip();
            name = n.isEmpty() ? null : n;
        }
        String email = text(p, "email");
        // Apollo hides emails it hasn't revealed behind placeholders like email_not_unlocked@domain.com
        if (email != null && email.startsWith("email_not_unlocked")) email = null;
        return new Person(text(p, "id"), name, text(p, "title"), text(p, "linkedin_url"), email, text(p, "email_status"));
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        if (v == null || v.isNull()) return null;
        String s = v.asText().strip();
        return s.isEmpty() ? null : s;
    }
}
