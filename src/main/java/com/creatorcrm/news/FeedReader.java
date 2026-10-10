package com.creatorcrm.news;

import java.net.URI;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;

/**
 * Reads RSS and Atom feeds: a brand's blog or news feed, or a Shopify store's product feed. Plain code, no network:
 * {@code WebsiteReader} fetches, this reads.
 */
public final class FeedReader {
    static final int MAX_ITEMS = 30;
    static final int MAX_SUMMARY = 1000;

    /** One post or product. {@code published} is null when the feed gives no date. */
    public record Item(String title, String link, OffsetDateTime published, String summary) {}

    private FeedReader() {}

    /** Whether a response body looks like a feed rather than a web page. */
    public static boolean looksLikeFeed(String body) {
        if (body == null) return false;
        String head = body.substring(0, Math.min(body.length(), 2000)).toLowerCase(Locale.ROOT);
        return head.contains("<rss") || head.contains("<feed") || head.contains("<rdf:rdf");
    }

    /** The feeds a page links to with {@code <link rel="alternate">}, as absolute addresses. */
    public static List<URI> feedLinks(Document page) {
        List<URI> out = new ArrayList<>();
        for (Element l : page.select("link[rel~=(?i)alternate][href]")) {
            String type = l.attr("type").toLowerCase(Locale.ROOT);
            if (!type.contains("rss") && !type.contains("atom")) continue;
            String title = l.attr("title").toLowerCase(Locale.ROOT);
            if (title.contains("comment")) continue; // WordPress's comments feed: not news
            try {
                URI u = URI.create(l.absUrl("href").replace(" ", "%20"));
                if (u.getHost() != null && !out.contains(u)) out.add(u);
            } catch (IllegalArgumentException ignored) {
                // a broken link: skip it
            }
        }
        return out;
    }

    /** The items in an RSS 2.0, RSS 1.0 or Atom feed, newest first as the feed lists them. */
    public static List<Item> parse(String xml, String baseUrl) {
        Document doc = Jsoup.parse(xml == null ? "" : xml, baseUrl == null ? "" : baseUrl, Parser.xmlParser());
        List<Item> out = new ArrayList<>();
        for (Element e : doc.select("item, entry")) {
            String title = text(e, "title");
            if (title.isBlank()) continue;
            String link = link(e);
            OffsetDateTime when = date(first(e, "pubDate", "published", "dc|date", "updated"));
            String summary = clean(first(e, "description", "summary", "content", "content|encoded"));
            out.add(new Item(title, link, when, summary.length() > MAX_SUMMARY ? summary.substring(0, MAX_SUMMARY) + "…" : summary));
            if (out.size() == MAX_ITEMS) break;
        }
        return out;
    }

    private static String link(Element e) {
        // Atom: <link rel="alternate" href="..."/>; RSS: <link>...</link>
        for (Element l : e.getElementsByTag("link")) {
            String href = l.attr("href");
            String rel = l.attr("rel");
            if (!href.isBlank() && (rel.isBlank() || rel.equalsIgnoreCase("alternate"))) return l.absUrl("href").isBlank() ? href : l.absUrl("href");
            if (href.isBlank() && !l.text().isBlank()) return l.text().strip();
        }
        String guid = text(e, "guid");
        return guid.startsWith("http") ? guid : null;
    }

    private static String text(Element e, String tag) {
        Element t = e.getElementsByTag(tag).first();
        return t == null ? "" : t.text().strip();
    }

    private static String first(Element e, String... tags) {
        for (String tag : tags) {
            String v = text(e, tag.replace('|', ':'));
            if (!v.isBlank()) return v;
        }
        return "";
    }

    /** Feeds put HTML, escaped or not, in their summaries: keep the words. */
    static String clean(String s) {
        if (s == null || s.isBlank()) return "";
        return Jsoup.parse(s).text().replaceAll("\\s+", " ").strip();
    }

    /** RFC 1123 ("Tue, 07 Oct 2026 09:00:00 +0000"), ISO 8601, or null. */
    static OffsetDateTime date(String s) {
        if (s == null || s.isBlank()) return null;
        String v = s.strip();
        try {
            return OffsetDateTime.parse(v, DateTimeFormatter.RFC_1123_DATE_TIME);
        } catch (DateTimeParseException ignored) {
            // try the next format
        }
        try {
            return OffsetDateTime.parse(v.replace(" GMT", " +0000").replace(" UTC", " +0000"), DateTimeFormatter.RFC_1123_DATE_TIME);
        } catch (DateTimeParseException ignored) {
            // try the next format
        }
        try {
            return OffsetDateTime.parse(v);
        } catch (DateTimeParseException ignored) {
            // try the next format
        }
        try {
            return java.time.LocalDate.parse(v.length() >= 10 ? v.substring(0, 10) : v).atStartOfDay().atOffset(ZoneOffset.UTC);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
