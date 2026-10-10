package com.creatorcrm.news;

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
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Recent news articles that name a brand, from the GDELT project's free public news index (its DOC 2.0 API, no
 * key or account). Bigger launches, new stores and retail deals show up here. One request per brand, a few
 * seconds apart as GDELT asks.
 */
@Component
public class NewsIndex {
    private static final Logger log = LoggerFactory.getLogger(NewsIndex.class);
    static final String API = "https://api.gdeltproject.org/api/v2/doc/doc";
    static final int MAX_ARTICLES = 10;
    /** GDELT asks for at most one request every five seconds. */
    static final long PAUSE_MS = 6_000;
    private static final DateTimeFormatter SEEN = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'");
    private static final ObjectMapper JSON = new ObjectMapper();

    /** One article: its headline, link, the site it's on and when GDELT first saw it. */
    public record Article(String title, String url, String site, OffsetDateTime seenAt) {}

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private long lastRequest;

    /**
     * Articles from the last {@code days} days whose headline names the brand, newest first. Empty when the name is
     * too short or common to search for, or the index can't be reached: news is a bonus, never a failure.
     */
    public synchronized List<Article> search(String brandName, int days) {
        String name = brandName == null ? "" : brandName.strip();
        // GDELT refuses very short phrases, and a two-letter name matches everything anyway
        if (name.replaceAll("[^\\p{L}\\p{N}]", "").length() < 4) return List.of();
        String query = "\"" + name.replace("\"", "") + "\"";
        URI uri = URI.create(API + "?query=" + URLEncoder.encode(query, StandardCharsets.UTF_8)
                + "&mode=artlist&format=json&sort=datedesc&maxrecords=" + MAX_ARTICLES + "&timespan=" + Math.max(1, days) + "d");
        try {
            long wait = lastRequest + PAUSE_MS - System.currentTimeMillis();
            if (wait > 0) pause(wait);
            lastRequest = System.currentTimeMillis();
            return parse(fetch(uri), name);
        } catch (IOException e) {
            log.info("News index: no results for {}: {}", name, e.getMessage());
            return List.of();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return List.of();
        }
    }

    /** The articles in a GDELT reply whose headline contains the brand's name. */
    static List<Article> parse(String json, String brandName) {
        List<Article> out = new ArrayList<>();
        if (json == null || json.isBlank() || !json.strip().startsWith("{")) return out; // GDELT answers errors in plain text
        JsonNode root;
        try {
            root = JSON.readTree(json);
        } catch (IOException e) {
            return out;
        }
        String needle = brandName.toLowerCase(Locale.ROOT);
        for (JsonNode a : root.path("articles")) {
            String title = a.path("title").asText("").strip();
            String url = a.path("url").asText("");
            if (title.isEmpty() || !url.startsWith("http") || !title.toLowerCase(Locale.ROOT).contains(needle)) continue;
            OffsetDateTime seen = null;
            try {
                seen = LocalDateTime.parse(a.path("seendate").asText(""), SEEN).atOffset(ZoneOffset.UTC);
            } catch (DateTimeParseException ignored) {
                // kept without a date
            }
            out.add(new Article(title, url, a.path("domain").asText(""), seen));
        }
        return out;
    }

    /** Demo mode and tests replace this. */
    protected String fetch(URI uri) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(20))
                .header("User-Agent", "CreatorCRM (+https://github.com/rastogi-s/creator-crm)").GET().build();
        HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() != 200) throw new IOException("GDELT answered " + r.statusCode());
        return r.body();
    }

    protected void pause(long ms) throws InterruptedException {
        Thread.sleep(ms);
    }
}
