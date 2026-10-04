package com.creatorcrm.links;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * Reads the public links off a Linktree profile so the creator can bring them all in at once.
 * Only ever fetches https://linktr.ee/&lt;username&gt;; the user-supplied text is reduced to a username first.
 */
@Service
public class LinktreeImporter {

    public record FoundLink(String label, String url, boolean social) {}

    public record Profile(String username, String name, List<FoundLink> links) {}

    private static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9_.-]{1,60}");
    private static final Pattern NEXT_DATA = Pattern.compile(
            "<script[^>]*id=\"__NEXT_DATA__\"[^>]*>(.*?)</script>", Pattern.DOTALL);
    private static final Pattern ANCHOR = Pattern.compile(
            "<a[^>]*href=\"(https?://[^\"]+)\"[^>]*>(.*?)</a>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    /** Query parameters that only track where a click came from; dropped so saved links stay clean. */
    private static final Pattern TRACKING_PARAM = Pattern.compile("(?i)^(utm_[a-z]+|fbclid|gclid|igshid|ltsid)=.*");

    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL).build();

    public Profile fetch(String input) {
        String user = username(input);
        Profile p = parse(user, download(user));
        if (p.links().isEmpty()) throw new IllegalArgumentException("No links found on that Linktree page");
        return p;
    }

    /** The profile page's HTML. Demo mode overrides this so walkthrough videos never depend on a real page. */
    protected String download(String user) {
        HttpResponse<String> r;
        try {
            r = http.send(HttpRequest.newBuilder(URI.create("https://linktr.ee/" + user))
                    .timeout(Duration.ofSeconds(20))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36")
                    .header("Accept", "text/html").GET().build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new IllegalArgumentException("Couldn't reach Linktree. Check the internet connection and try again.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalArgumentException("Linktree import was interrupted");
        }
        if (r.statusCode() == 404) throw new IllegalArgumentException("No Linktree page found for " + user);
        if (r.statusCode() != 200) throw new IllegalArgumentException("Linktree didn't answer (" + r.statusCode() + "). Try again in a minute.");
        return r.body();
    }

    /** "linktr.ee/name?utm_source=...", "https://www.linktr.ee/name/", "@name" or "name" all become "name". */
    static String username(String input) {
        String v = input == null ? "" : input.strip();
        v = v.replaceFirst("(?i)^https?://", "").replaceFirst("(?i)^(www\\.)?linktr\\.ee/", "");
        v = v.replaceFirst("[?#].*$", "").replaceFirst("/+$", "").replaceFirst("^@", "");
        if (!USERNAME.matcher(v).matches()) throw new IllegalArgumentException("Paste a Linktree link like linktr.ee/yourname");
        return v;
    }

    static Profile parse(String user, String html) {
        Map<String, FoundLink> found = new LinkedHashMap<>();
        String name = user;
        Matcher m = NEXT_DATA.matcher(html);
        if (m.find()) {
            try {
                JsonNode page = JSON.readTree(m.group(1)).path("props").path("pageProps");
                JsonNode account = page.path("account");
                String title = text(account, "pageTitle");
                if (title.isEmpty()) title = text(page, "pageTitle");
                if (!title.isEmpty()) name = title.replaceFirst("^@", "");
                JsonNode links = page.path("links").isArray() ? page.path("links") : account.path("links");
                for (JsonNode l : links) {
                    if (l.path("locked").asBoolean(false)) continue;
                    put(found, text(l, "title"), text(l, "url"), false);
                }
                JsonNode socials = page.path("socialLinks").isArray() ? page.path("socialLinks") : account.path("socialLinks");
                for (JsonNode s : socials) put(found, pretty(text(s, "type")), text(s, "url"), true);
            } catch (IOException ignored) {
                // fall through to reading plain links off the page
            }
        }
        if (found.isEmpty()) {
            Matcher a = ANCHOR.matcher(html);
            while (a.find()) {
                String url = a.group(1).replace("&amp;", "&");
                if (url.matches("(?i)^https?://([a-z0-9-]+\\.)*linktr\\.ee(/.*)?$")) continue;
                put(found, a.group(2).replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").strip(), url, false);
            }
        }
        // Social icons last, in the order Linktree shows them.
        List<FoundLink> out = new ArrayList<>(found.values().stream().filter(l -> !l.social()).toList());
        out.addAll(found.values().stream().filter(FoundLink::social).toList());
        return new Profile(user, name, out);
    }

    private static void put(Map<String, FoundLink> found, String label, String url, boolean social) {
        if (url.isEmpty()) return;
        String clean;
        try {
            clean = LinkService.normalizeUrl(stripTracking(url));
        } catch (IllegalArgumentException e) {
            return;
        }
        String key = LinkService.sameLinkKey(clean);
        if (found.containsKey(key)) return; // e.g. YouTube both as a button and a social icon: keep the button
        String l = label.length() > 100 ? label.substring(0, 100).strip() : label;
        found.put(key, new FoundLink(l.isEmpty() ? LinkService.labelOrDefault("", clean) : l, clean, social));
    }

    static String stripTracking(String url) {
        int q = url.indexOf('?');
        if (q < 0) return url;
        int hash = url.indexOf('#', q);
        String query = hash < 0 ? url.substring(q + 1) : url.substring(q + 1, hash);
        String kept = String.join("&", Arrays.stream(query.split("&"))
                .filter(p -> !p.isEmpty() && !TRACKING_PARAM.matcher(p).matches()).toList());
        return url.substring(0, q) + (kept.isEmpty() ? "" : "?" + kept) + (hash < 0 ? "" : url.substring(hash));
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.path(field);
        return v.isTextual() ? v.asText().strip() : "";
    }

    /** INSTAGRAM -> Instagram, TIKTOK -> TikTok. */
    private static String pretty(String type) {
        return switch (type.toUpperCase(Locale.ROOT)) {
            case "TIKTOK" -> "TikTok";
            case "YOUTUBE" -> "YouTube";
            case "LINKEDIN" -> "LinkedIn";
            case "TWITTER", "X" -> "X";
            case "EMAIL_ADDRESS" -> "";
            default -> type.isEmpty() ? "" : type.charAt(0) + type.substring(1).toLowerCase(Locale.ROOT).replace('_', ' ');
        };
    }
}
