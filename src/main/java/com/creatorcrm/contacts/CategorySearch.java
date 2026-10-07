package com.creatorcrm.contacts;

import com.creatorcrm.domain.Brand;
import com.creatorcrm.domain.BrandLead;
import com.creatorcrm.repo.BrandDomainRepo;
import com.creatorcrm.repo.BrandLeadRepo;
import com.creatorcrm.repo.BrandRepo;
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
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Free brand discovery by category ("skincare", "running shoes", "coffee"): looks the category up in Wikidata, the
 * open (CC0) database behind Wikipedia, and lists the companies and brands filed under it that have an official
 * website. They become brand suggestions, and their websites are then read for published emails by
 * {@link WebsiteContacts}. No Claude, no paid service; one search and one query per category, with an identifying
 * User-Agent as Wikidata's rules ask.
 */
@Service
public class CategorySearch {
    private static final Logger log = LoggerFactory.getLogger(CategorySearch.class);
    static final int MAX_RESULTS = 50;
    private static final ObjectMapper JSON = new ObjectMapper();

    /** What a search added. {@code categories} = the Wikidata topics the words matched, for the message. */
    public record Result(List<BrandLead> added, List<String> categories, String message) {}

    /** A brand as Wikidata lists it. */
    record Found(String name, String website, String instagram, String category) {}

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL).build();
    private final WebsiteReader reader;
    private final WebsiteContacts website;
    private final BrandLeadRepo leads;
    private final BrandRepo brands;
    private final BrandDomainRepo domains;

    public CategorySearch(WebsiteReader reader, WebsiteContacts website, BrandLeadRepo leads, BrandRepo brands, BrandDomainRepo domains) {
        this.reader = reader;
        this.website = website;
        this.leads = leads;
        this.brands = brands;
        this.domains = domains;
    }

    /** Adds up to {@code count} brands in a category she doesn't have yet, then reads their websites in the background. */
    public Result search(String category, int count) {
        String q = category == null ? "" : category.strip().replaceAll("\\s+", " ");
        if (q.length() < 3 || q.length() > 100) throw new IllegalArgumentException("Type a category, like skincare or running shoes");
        int limit = Math.max(1, Math.min(MAX_RESULTS, count));
        Map<String, String> topics;
        List<Found> found;
        try {
            topics = topics(get(URI.create("https://www.wikidata.org/w/api.php?action=wbsearchentities&type=item&language=en"
                    + "&uselang=en&limit=7&format=json&search=" + URLEncoder.encode(q, StandardCharsets.UTF_8))));
            if (topics.isEmpty()) return new Result(List.of(), List.of(), "No category called \"" + q + "\" was found. Try a simpler word, like skincare or snacks.");
            found = brandsIn(get(URI.create("https://query.wikidata.org/sparql?format=json&query="
                    + URLEncoder.encode(sparql(topics.keySet()), StandardCharsets.UTF_8))));
        } catch (IOException e) {
            log.info("Category search failed: {}", e.getMessage());
            throw new IllegalStateException("Couldn't reach Wikidata, the free brand database. Check the internet connection and try again.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("The search was interrupted");
        }
        List<BrandLead> added = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Found f : found) {
            if (added.size() >= limit) break;
            String key = Brand.key(f.name());
            String domain = Emails.domainOfUrl(f.website());
            if (key.isEmpty() || domain == null || WebsiteReader.neverRead(domain) || !seen.add(key) || !seen.add(domain)) continue;
            if (known(key, f.instagram(), domain)) continue;
            BrandLead lead = new BrandLead();
            lead.name = clip(f.name(), 200);
            lead.nameKey = key;
            lead.website = clip(f.website(), 1000);
            lead.instagram = f.instagram() == null ? null : clip(f.instagram().replaceFirst("^@", ""), 100);
            lead.fitReason = clip("Listed under " + f.category() + " in Wikidata, the free open brand database.", 2000);
            lead.searchQuery = clip(q, 500);
            lead.source = BrandLead.Source.CATEGORY;
            lead.status = BrandLead.Status.NEW;
            lead.createdAt = OffsetDateTime.now();
            added.add(leads.save(lead));
        }
        website.readLeadsSoon(added.stream().map(l -> l.id).toList());
        List<String> names = new ArrayList<>(topics.values());
        String msg = added.isEmpty()
                ? "No new brands under " + String.join(", ", names) + ". You may have them all already, or try a related word."
                : added.size() + (added.size() == 1 ? " new brand" : " new brands") + " found. Their websites are being read for emails now; "
                        + "addresses appear here as they're found.";
        return new Result(added, names, msg);
    }

    private boolean known(String key, String instagram, String domain) {
        if (brands.findByNameKey(key).isPresent() || leads.existsByNameKey(key) || domains.findByDomain(domain).isPresent()) return true;
        return instagram != null && (brands.findFirstByInstagramIgnoreCase(instagram).isPresent() || leads.existsByInstagramIgnoreCase(instagram));
    }

    /** Wikidata topics matching her words: id → label. */
    static Map<String, String> topics(String json) throws IOException {
        Map<String, String> out = new LinkedHashMap<>();
        for (JsonNode n : JSON.readTree(json).path("search")) {
            String id = n.path("id").asText("");
            if (id.matches("Q\\d{1,12}")) out.put(id, n.path("label").asText(id));
        }
        return out;
    }

    /** Organizations and brands in an industry, making a product, or of a type, that have an official website and still exist. */
    static String sparql(Set<String> ids) {
        String values = String.join(" ", ids.stream().map(id -> "wd:" + id).toList());
        return "SELECT ?b ?bLabel ?site ?ig ?catLabel WHERE {\n"
                + "  VALUES ?cat { " + values + " }\n"
                + "  { ?b wdt:P452 ?cat } UNION { ?b wdt:P1056 ?cat } UNION { ?b wdt:P31 ?cat }\n"
                + "  UNION { ?b wdt:P452/wdt:P279 ?cat } UNION { ?b wdt:P31/wdt:P279 ?cat }\n"
                + "  ?b wdt:P856 ?site .\n"
                + "  FILTER NOT EXISTS { ?b wdt:P576 [] }\n"
                + "  OPTIONAL { ?b wdt:P2003 ?ig }\n"
                + "  SERVICE wikibase:label { bd:serviceParam wikibase:language \"en\". }\n"
                + "} LIMIT 400";
    }

    static List<Found> brandsIn(String json) throws IOException {
        Map<String, Found> out = new LinkedHashMap<>();
        for (JsonNode row : JSON.readTree(json).path("results").path("bindings")) {
            String id = row.path("b").path("value").asText("");
            String name = row.path("bLabel").path("value").asText("").strip();
            String site = row.path("site").path("value").asText("").strip();
            if (name.isEmpty() || name.matches("Q\\d+") || !site.matches("(?i)^https?://.+")) continue;
            String ig = row.path("ig").path("value").asText(null);
            Found old = out.get(id);
            if (old == null) out.put(id, new Found(name, site, ig, row.path("catLabel").path("value").asText("this category")));
            else if (old.instagram() == null && ig != null) out.put(id, new Found(old.name(), old.website(), ig, old.category()));
        }
        return new ArrayList<>(out.values());
    }

    /** One GET with the app's User-Agent. Demo mode replaces this so videos never depend on the internet. */
    protected String get(URI uri) throws IOException, InterruptedException {
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(40))
                .header("User-Agent", reader.userAgent())
                .header("Accept", "application/json").GET().build(), HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() != 200) throw new IOException("Wikidata answered " + r.statusCode());
        return r.body();
    }

    private static String clip(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
