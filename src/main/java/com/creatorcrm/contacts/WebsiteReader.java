package com.creatorcrm.contacts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.stereotype.Component;

/**
 * Reads the email addresses a brand publishes on its own website, politely and with plain code (no Claude): the
 * homepage, then up to five of its contact, partnerships, press, about, wholesale or affiliate pages.
 *
 * <p>The rules that keep this on the right side of every site's wishes: it says who it is in its User-Agent, obeys
 * robots.txt (and reads nothing when robots.txt can't be read), waits a few seconds between pages (longer if the
 * site's Crawl-delay asks), reads public pages only (no cookies, no sign-in, nothing that answers 401/403, no
 * account, cart or login pages), never leaves the brand's own domain, and never reads social networks or
 * marketplaces. Only addresses at the brand's own domain, or a personal mailbox the brand lists, are kept.
 */
@Component
public class WebsiteReader {
    private static final Logger log = LoggerFactory.getLogger(WebsiteReader.class);

    /** The name robots.txt files can use to give this reader its own rules. */
    public static final String TOKEN = "CreatorCRM";
    static final int MAX_PAGES = 6;
    static final long DELAY_MS = 3_000;
    static final long MAX_DELAY_MS = 20_000;
    private static final int MAX_BYTES = 2_000_000;
    private static final int MAX_REDIRECTS = 4;

    /** One response, as read. {@code location} is set on redirects. */
    public record Page(int status, String contentType, String body, String location) {}

    /** An address found on a page, with a name or job title when the page gave one. */
    public record Hit(String email, String name, String title, String pageUrl) {}

    /** What one visit found. {@code problem} is a plain sentence when nothing could be read. */
    public record Result(String site, List<Hit> hits, List<String> pagesRead, String problem) {
        static Result failed(String site, String problem) {
            return new Result(site, List.of(), List.of(), problem);
        }
    }

    /** Social networks, link-in-bio pages and marketplaces: never read, whatever a brand's "website" says. */
    private static final Set<String> NEVER_READ = Set.of(
            "instagram.com", "facebook.com", "fb.com", "tiktok.com", "twitter.com", "x.com", "linkedin.com",
            "youtube.com", "youtu.be", "pinterest.com", "threads.net", "snapchat.com", "reddit.com", "linktr.ee",
            "beacons.ai", "amazon.com", "etsy.com", "ebay.com", "whatsapp.com", "t.me", "discord.gg", "discord.com");
    /** Pages behind a sign-in, or that change something: never read. */
    private static final Pattern PRIVATE_PATH = Pattern.compile(
            "(?i)/(account|accounts|login|log-in|signin|sign-in|signup|sign-up|register|cart|checkout|my-account|admin|wp-admin|wp-login\\.php|password|orders)(/|$|\\?)");
    /** Links worth following, best first. */
    private static final Pattern LINK_BEST = Pattern.compile(
            "(?i)contact|partner|collab|influenc|creator|ambassador|affiliat|work[-_ ]?with|ugc|get[-_ ]in[-_ ]touch");
    private static final Pattern LINK_GOOD = Pattern.compile("(?i)press|media|\\bpr\\b|wholesale|stockist|retailer");
    private static final Pattern LINK_OK = Pattern.compile("(?i)about|our[-_ ]story|team");
    private static final Pattern NOT_A_PAGE = Pattern.compile(
            "(?i)\\.(pdf|jpe?g|png|gif|webp|svg|zip|mp4|mov|css|js|xml|json|txt)$|/(products?|collections|blogs?|tags?|search)(/|$)");
    private static final Pattern AT = Pattern.compile("(?i)\\s*[\\[({]\\s*at\\s*[\\])}]\\s*");
    private static final Pattern DOT = Pattern.compile("(?i)\\s*[\\[({]\\s*dot\\s*[\\])}]\\s*");
    private static final Pattern PERSON = Pattern.compile("^\\p{Lu}[\\p{L}'-]+( \\p{Lu}[\\p{L}'.-]*){1,3}$");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private final String userAgent;

    public WebsiteReader(ObjectProvider<BuildProperties> build) {
        BuildProperties b = build == null ? null : build.getIfAvailable();
        String version = b == null ? "dev" : b.getVersion();
        this.userAgent = "Mozilla/5.0 (compatible; " + TOKEN + "/" + version
                + "; reads public contact pages; +https://github.com/rastogi-s/creator-crm)";
    }

    String userAgent() {
        return userAgent;
    }

    // ---------------------------------------------------------------- the visit

    /** Reads a brand's website. Takes a few seconds per page; never throws for a site that can't be read. */
    public Result read(String website) {
        URI home = homeOf(website);
        if (home == null) return Result.failed(website, "That doesn't look like a website address.");
        String site = home.getHost();
        String domain = Emails.registrable(site);
        if (domain == null) return Result.failed(site, "That doesn't look like a website address.");
        if (neverRead(site)) return Result.failed(site, "That's a social media or shop profile, not the brand's own website. Add the brand's website instead.");

        Visit v = new Visit(domain);
        Fetched first = v.get(home);
        if (first == null) return Result.failed(site, v.problem != null ? v.problem : "Couldn't open " + site + ".");
        Map<String, Hit> hits = new LinkedHashMap<>();
        List<String> read = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        seen.add(key(home));
        seen.add(key(first.uri()));
        List<URI> next = new ArrayList<>();
        if (first.doc() != null) {
            read.add(first.uri().toString());
            merge(hits, extract(first.doc(), first.uri().toString(), domain));
            next.addAll(links(first.doc(), first.uri(), domain));
            boolean shopify = first.page().body().contains("cdn.shopify.com") || first.page().body().contains("Shopify.theme");
            boolean hasContact = next.stream().anyMatch(u -> u.getPath() != null && u.getPath().toLowerCase(Locale.ROOT).contains("contact"));
            URI base = first.uri().resolve("/");
            if (shopify) next.add(base.resolve("/pages/contact"));
            if (!hasContact) next.add(base.resolve("/contact"));
        }
        for (URI u : next) {
            if (read.size() >= MAX_PAGES) break;
            if (!seen.add(key(u))) continue;
            Fetched f = v.get(u);
            if (f == null || f.doc() == null) continue;
            if (!f.uri().equals(u) && !seen.add(key(f.uri()))) continue; // redirected to a page already read
            read.add(f.uri().toString());
            merge(hits, extract(f.doc(), f.uri().toString(), domain));
        }
        String problem = read.isEmpty() ? (v.problem != null ? v.problem : "Couldn't read any pages on " + site + ".") : null;
        return new Result(site, new ArrayList<>(hits.values()), read, problem);
    }

    private record Fetched(URI uri, Page page, Document doc) {}

    /** One site visit: robots.txt per host, the pause between requests, redirects kept on the brand's domain. */
    private final class Visit {
        final String domain;
        final Map<String, RobotsTxt> robots = new HashMap<>();
        long delay = DELAY_MS;
        boolean any = false;
        String problem;

        Visit(String domain) {
            this.domain = domain;
        }

        Fetched get(URI start) {
            URI u = start;
            for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
                if (!allowed(u)) return null;
                Page p = request(u);
                if (p == null) return null;
                if (p.status() >= 300 && p.status() < 400 && p.location() != null) {
                    try {
                        u = u.resolve(p.location().strip().replace(" ", "%20"));
                    } catch (IllegalArgumentException e) {
                        return null;
                    }
                    continue;
                }
                if (p.status() == 401 || p.status() == 403) {
                    if (problem == null) problem = u.getHost() + " doesn't let apps read its pages.";
                    return null;
                }
                if (p.status() != 200) {
                    if (problem == null && hop == 0 && !any) problem = u.getHost() + " didn't answer (" + p.status() + ").";
                    return null;
                }
                if (!isHtml(p.contentType())) return null;
                Document doc = Jsoup.parse(p.body(), u.toString());
                if (!doc.select("input[type=password]").isEmpty()) return new Fetched(u, p, null); // a sign-in page
                return new Fetched(u, p, doc);
            }
            return null;
        }

        /** On the brand's domain, a public page, and robots.txt says yes. */
        boolean allowed(URI u) {
            String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
            if (!scheme.equals("https") && !scheme.equals("http")) return false;
            if (u.getHost() == null || !domain.equals(Emails.registrable(u.getHost()))) return false;
            if (u.getUserInfo() != null || PRIVATE_PATH.matcher(pathOf(u)).find()) return false;
            RobotsTxt r = robots.computeIfAbsent(u.getScheme() + "://" + u.getRawAuthority(), k -> robotsFor(u));
            if (!r.allows(pathOf(u))) {
                if (problem == null) problem = u.getHost() + " asks apps not to read its pages, so it was left alone.";
                return false;
            }
            return true;
        }

        RobotsTxt robotsFor(URI u) {
            URI ru = u.resolve("/robots.txt");
            for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
                Page p = request(ru);
                if (p == null) return RobotsTxt.disallowAll();
                if (p.status() >= 300 && p.status() < 400 && p.location() != null) {
                    try {
                        ru = ru.resolve(p.location().strip());
                    } catch (IllegalArgumentException e) {
                        return RobotsTxt.disallowAll();
                    }
                    continue;
                }
                if (p.status() == 200) {
                    RobotsTxt r = RobotsTxt.parse(p.body(), TOKEN);
                    if (r.crawlDelaySeconds() != null) {
                        delay = Math.min(MAX_DELAY_MS, Math.max(DELAY_MS, (long) (r.crawlDelaySeconds() * 1000)));
                    }
                    return r;
                }
                if (p.status() >= 400 && p.status() < 500) return RobotsTxt.allowAll();
                return RobotsTxt.disallowAll();
            }
            return RobotsTxt.allowAll(); // RFC 9309: more than five redirects counts as "no robots.txt"
        }

        Page request(URI u) {
            if (any) pause(delay);
            any = true;
            try {
                return fetch(u);
            } catch (IOException e) {
                log.info("Website reader: {} {}", u, e.getMessage());
                if (problem == null) problem = "Couldn't reach " + u.getHost() + ". Check the website address.";
                return null;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
    }

    // ---------------------------------------------------------------- network (demo mode and tests replace these)

    /** One plain GET, no cookies, no redirects followed. Refuses addresses on this computer or its home network. */
    protected Page fetch(URI uri) throws IOException, InterruptedException {
        requirePublic(uri.getHost());
        HttpRequest req = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(15))
                .header("User-Agent", userAgent)
                .header("Accept", "text/html,application/xhtml+xml,text/plain;q=0.8")
                .GET().build();
        HttpResponse<InputStream> r = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
        String type = r.headers().firstValue("content-type").orElse("");
        String location = r.headers().firstValue("location").orElse(null);
        String body = "";
        try (InputStream in = r.body()) {
            if (r.statusCode() == 200) body = readCapped(in, charset(type));
        }
        return new Page(r.statusCode(), type, body, location);
    }

    protected void pause(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    static void requirePublic(String host) throws IOException {
        if (host == null) throw new IOException("no host");
        for (InetAddress a : InetAddress.getAllByName(host)) {
            boolean uniqueLocal = a instanceof Inet6Address && (a.getAddress()[0] & 0xfe) == 0xfc;
            if (a.isLoopbackAddress() || a.isAnyLocalAddress() || a.isSiteLocalAddress() || a.isLinkLocalAddress()
                    || a.isMulticastAddress() || uniqueLocal) {
                throw new IOException(host + " is not a public website");
            }
        }
    }

    private static String readCapped(InputStream in, Charset cs) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0 && out.size() < MAX_BYTES) out.write(buf, 0, Math.min(n, MAX_BYTES - out.size()));
        return out.toString(cs);
    }

    private static Charset charset(String contentType) {
        Matcher m = Pattern.compile("(?i)charset=\"?([\\w.:-]+)").matcher(contentType == null ? "" : contentType);
        if (m.find()) {
            try {
                return Charset.forName(m.group(1));
            } catch (RuntimeException ignored) {
                // unknown charset: fall back to UTF-8
            }
        }
        return StandardCharsets.UTF_8;
    }

    // ---------------------------------------------------------------- reading a page (plain code, tested directly)

    /** "glowberry.com", "www.glowberry.com/shop?x" or "http://glowberry.com" → its homepage; null when unusable. */
    static URI homeOf(String website) {
        if (website == null || website.isBlank()) return null;
        String w = website.strip();
        if (!w.matches("(?i)^[a-z][a-z0-9+.-]*://.*")) w = "https://" + w;
        try {
            URI u = new URI(w.replace(" ", "%20"));
            String scheme = u.getScheme().toLowerCase(Locale.ROOT);
            if (u.getHost() == null || (!scheme.equals("http") && !scheme.equals("https"))) return null;
            return new URI(scheme, null, u.getHost().toLowerCase(Locale.ROOT), u.getPort(), "/", null, null);
        } catch (URISyntaxException | RuntimeException e) {
            return null;
        }
    }

    static boolean neverRead(String host) {
        String d = Emails.registrable(host);
        return d != null && (NEVER_READ.contains(d) || d.startsWith("amazon.") || d.startsWith("etsy."));
    }

    static boolean isHtml(String contentType) {
        String t = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        return t.isEmpty() || t.contains("text/html") || t.contains("application/xhtml");
    }

    private static String pathOf(URI u) {
        String p = u.getRawPath() == null || u.getRawPath().isEmpty() ? "/" : u.getRawPath();
        return u.getRawQuery() == null ? p : p + "?" + u.getRawQuery();
    }

    private static String key(URI u) {
        String p = u.getPath() == null || u.getPath().isEmpty() ? "/" : u.getPath().replaceFirst("/+$", "");
        return (u.getHost() == null ? "" : u.getHost().replaceFirst("^www\\.", "")) + (p.isEmpty() ? "/" : p).toLowerCase(Locale.ROOT);
    }

    /** Contact-type links on a page that stay on the brand's domain, best first. */
    static List<URI> links(Document doc, URI page, String domain) {
        Map<String, URI> found = new LinkedHashMap<>();
        Map<String, Integer> rank = new HashMap<>();
        for (Element a : doc.select("a[href]")) {
            String href = a.attr("href").strip();
            if (href.isEmpty() || href.startsWith("#") || href.regionMatches(true, 0, "mailto:", 0, 7)
                    || href.regionMatches(true, 0, "tel:", 0, 4) || href.regionMatches(true, 0, "javascript:", 0, 11)) continue;
            URI u;
            try {
                u = page.resolve(href.replace(" ", "%20"));
                u = new URI(u.getScheme(), null, u.getHost(), u.getPort(), u.getPath(), null, null);
            } catch (URISyntaxException | IllegalArgumentException e) {
                continue;
            }
            if (u.getHost() == null || !domain.equals(Emails.registrable(u.getHost()))) continue;
            String path = u.getPath() == null ? "" : u.getPath();
            if (path.isEmpty() || path.equals("/") || NOT_A_PAGE.matcher(path).find() || PRIVATE_PATH.matcher(path).find()) continue;
            if (path.split("/").length > 4) continue;
            String said = path + " " + a.text();
            int r = LINK_BEST.matcher(said).find() ? 3 : LINK_GOOD.matcher(said).find() ? 2 : LINK_OK.matcher(said).find() ? 1 : 0;
            if (r == 0) continue;
            String k = key(u);
            if (!found.containsKey(k) || rank.get(k) < r) {
                found.putIfAbsent(k, u);
                rank.put(k, r);
            }
        }
        List<String> keys = new ArrayList<>(found.keySet());
        keys.sort(Comparator.comparingInt((String k) -> -rank.get(k)));
        return keys.stream().map(found::get).toList();
    }

    /**
     * Every address a page publishes: mailto links (with the person's name when the link text is one), structured
     * data (schema.org contactPoint, with its contact type), Cloudflare-hidden addresses, and addresses written in
     * the text, including "name [at] brand [dot] com". Keeps the brand's own addresses and personal mailboxes only.
     */
    static List<Hit> extract(Document doc, String pageUrl, String domain) {
        Map<String, Hit> out = new LinkedHashMap<>();
        for (Element a : doc.select("a[href]")) {
            String href = a.attr("href").strip();
            if (!href.regionMatches(true, 0, "mailto:", 0, 7)) continue;
            String text = a.text().strip();
            String name = PERSON.matcher(text).matches() ? text : null;
            for (String part : href.substring(7).split(",")) add(out, part, name, null, pageUrl, domain);
        }
        for (Element s : doc.select("script[type=application/ld+json]")) {
            try {
                walk(JSON.readTree(s.data()), out, pageUrl, domain, 0);
            } catch (IOException | RuntimeException ignored) {
                // broken structured data: the rest of the page still counts
            }
        }
        for (Element e : doc.select("[data-cfemail]")) {
            add(out, cloudflare(e.attr("data-cfemail")), null, null, pageUrl, domain);
        }
        String text = doc.body() == null ? "" : doc.body().text();
        text = DOT.matcher(AT.matcher(text).replaceAll("@")).replaceAll(".");
        for (String e : Emails.findAll(text)) add(out, e, null, null, pageUrl, domain);
        return new ArrayList<>(out.values());
    }

    private static void walk(JsonNode n, Map<String, Hit> out, String pageUrl, String domain, int depth) {
        if (n == null || depth > 8) return;
        if (n.isArray()) {
            for (JsonNode c : n) walk(c, out, pageUrl, domain, depth + 1);
            return;
        }
        if (!n.isObject()) return;
        if (n.path("email").isTextual()) {
            String type = n.path("contactType").asText("").strip();
            String name = n.path("@type").asText("").equals("Person") ? n.path("name").asText(null) : null;
            String title = !type.isEmpty() ? type : n.path("jobTitle").asText(null);
            add(out, n.path("email").asText(), name, title, pageUrl, domain);
        }
        n.fields().forEachRemaining(f -> walk(f.getValue(), out, pageUrl, domain, depth + 1));
    }

    private static void add(Map<String, Hit> out, String raw, String name, String title, String pageUrl, String domain) {
        String e = Emails.clean(raw);
        if (e == null) return;
        String d = Emails.domainOf(e);
        if (!Emails.isFreeMail(d) && !domain.equals(Emails.registrable(d))) return; // an agency's, a platform's, another brand's
        Hit old = out.get(e);
        if (old == null) out.put(e, new Hit(e, blankToNull(name), blankToNull(title), pageUrl));
        else out.put(e, new Hit(e, old.name() != null ? old.name() : blankToNull(name),
                old.title() != null ? old.title() : blankToNull(title), old.pageUrl()));
    }

    private static void merge(Map<String, Hit> into, List<Hit> more) {
        for (Hit h : more) {
            Hit old = into.get(h.email());
            if (old == null) into.put(h.email(), h);
            else into.put(h.email(), new Hit(h.email(), old.name() != null ? old.name() : h.name(),
                    old.title() != null ? old.title() : h.title(), old.pageUrl()));
        }
    }

    /** Cloudflare's email protection: hex, the first byte XORs the rest. */
    static String cloudflare(String hex) {
        if (hex == null || hex.length() < 4 || hex.length() % 2 != 0 || !hex.matches("[0-9a-fA-F]+")) return null;
        int k = Integer.parseInt(hex.substring(0, 2), 16);
        StringBuilder sb = new StringBuilder();
        for (int i = 2; i < hex.length(); i += 2) sb.append((char) (Integer.parseInt(hex.substring(i, i + 2), 16) ^ k));
        return sb.toString();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
