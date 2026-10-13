package com.creatorcrm.shop;

import com.creatorcrm.domain.Product;
import com.creatorcrm.repo.ProductRepo;
import com.creatorcrm.settings.SettingsService;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Her shop links: products from Amazon, Walmart or anywhere else, each with its affiliate link, tags and a discount
 * code. Pasting a link fills in the store, the product's name and her Amazon tag.
 */
@Service
public class ProductService {
    public static final int MAX_PRODUCTS = 500;
    static final int MAX_TAGS = 8;

    /** What the Products page shows. {@code warning}: why she might not earn from the link, or null. */
    public record View(Long id, String name, String store, String url, String itemId, String brand, List<String> tags, String code,
                       int timesUsed, OffsetDateTime lastUsedAt, String warning) {}

    public record Request(String url, String name, String brand, String tags, String code) {}

    /** What a pasted link turned out to be, before she saves it. {@code duplicateOf}: the saved product it already is. */
    public record Preview(String store, String url, String itemId, String name, String warning, String duplicateOf) {}

    public record Added(int added, int skipped, List<String> problems) {}

    private final ProductRepo products;
    private final SettingsService settings;
    private final ShortLinks shortLinks;

    public ProductService(ProductRepo products, SettingsService settings, ShortLinks shortLinks) {
        this.products = products;
        this.settings = settings;
        this.shortLinks = shortLinks;
    }

    /** Recently used first, then newest. */
    public List<View> list() {
        return products.findAllByOrderByIdDesc().stream()
                .sorted(Comparator.comparing((Product p) -> p.lastUsedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .map(this::view).toList();
    }

    public List<Product> byIds(Collection<Long> ids) {
        List<Product> found = products.findAllById(ids);
        return ids.stream().map(id -> found.stream().filter(p -> p.id.equals(id)).findFirst().orElse(null))
                .filter(Objects::nonNull).toList();
    }

    public Preview preview(String url) {
        ProductLinks.Parsed p = parse(url);
        return new Preview(p.store().name(), p.url(), p.itemId(), p.name(), p.warning(), duplicate(p, null).map(d -> d.name).orElse(null));
    }

    @Transactional
    public View add(Request r) {
        if (products.count() >= MAX_PRODUCTS) throw new IllegalArgumentException("You have " + MAX_PRODUCTS + " products, the most the app keeps. Delete some first.");
        ProductLinks.Parsed p = parse(r.url());
        duplicate(p, null).ifPresent(d -> { throw new IllegalArgumentException("That's already in your products as " + d.name); });
        Product saved = new Product();
        saved.createdAt = OffsetDateTime.now();
        fill(saved, p, r);
        return view(products.save(saved));
    }

    /** Several links at once (one per line); each gets its name from the link. Duplicates and bad lines are skipped. */
    @Transactional
    public Added addAll(List<String> urls) {
        int added = 0, skipped = 0;
        List<String> problems = new java.util.ArrayList<>();
        for (String u : urls) {
            if (u == null || u.isBlank()) continue;
            try {
                add(new Request(u, null, null, null, null));
                added++;
            } catch (IllegalArgumentException e) {
                skipped++;
                if (problems.size() < 5) problems.add(u.strip() + ": " + e.getMessage());
            }
        }
        return new Added(added, skipped, problems);
    }

    @Transactional
    public View update(Long id, Request r) {
        Product saved = products.findById(id).orElseThrow(() -> new IllegalArgumentException("That product was deleted"));
        ProductLinks.Parsed p = parse(r.url() == null || r.url().isBlank() ? saved.url : r.url());
        duplicate(p, id).ifPresent(d -> { throw new IllegalArgumentException("That link is already saved as " + d.name); });
        fill(saved, p, r);
        return view(products.save(saved));
    }

    @Transactional
    public void delete(Long id) {
        products.deleteById(id);
    }

    /** Products went into a post: they float to the top of her list. */
    @Transactional
    public void markUsed(Collection<Long> ids) {
        OffsetDateTime now = OffsetDateTime.now();
        for (Product p : products.findAllById(ids)) {
            p.timesUsed++;
            p.lastUsedAt = now;
            products.save(p);
        }
    }

    View view(Product p) {
        return new View(p.id, p.name, p.store.name(), p.url, p.itemId, p.brand, tags(p.tags), p.code, p.timesUsed, p.lastUsedAt, warning(p));
    }

    /** Checked again each time: she may have added her Amazon tag since. */
    String warning(Product p) {
        try {
            return ProductLinks.parse(p.url, null, null).warning();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private ProductLinks.Parsed parse(String url) {
        ProductLinks.Parsed p = ProductLinks.parse(url, settings.amazonTag(), null);
        if (p.shortLink() && p.name() == null) {
            String to = shortLinks.expand(p.url());
            if (to != null) p = ProductLinks.parse(url, settings.amazonTag(), to);
        }
        return p;
    }

    private void fill(Product saved, ProductLinks.Parsed p, Request r) {
        saved.store = p.store();
        saved.url = p.url();
        saved.itemId = p.itemId();
        String name = text(r.name(), 200);
        if (name == null) name = p.name();
        if (name == null) name = switch (p.store()) {
            case AMAZON -> p.itemId() == null ? "Amazon product" : "Amazon product " + p.itemId();
            case WALMART -> p.itemId() == null ? "Walmart product" : "Walmart product " + p.itemId();
            case OTHER -> "Product from " + ProductLinks.host(ProductLinks.uri(p.url()));
        };
        saved.name = name.length() > 200 ? name.substring(0, 200) : name;
        saved.brand = text(r.brand(), 200);
        List<String> tags = tags(r.tags());
        saved.tags = tags.isEmpty() ? null : String.join(", ", tags);
        String code = text(r.code(), 100);
        saved.code = code == null ? null : code.replaceAll("\\s+", " ");
    }

    private Optional<Product> duplicate(ProductLinks.Parsed p, Long except) {
        return products.findAll().stream().filter(x -> !x.id.equals(except))
                .filter(x -> x.url.equalsIgnoreCase(p.url()) || (p.itemId() != null && x.store == p.store() && p.itemId().equals(x.itemId)))
                .findFirst();
    }

    static List<String> tags(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        return Arrays.stream(raw.split("[,#\\n]+")).map(t -> t.strip().toLowerCase(Locale.ROOT)).filter(t -> !t.isEmpty())
                .map(t -> t.length() > 30 ? t.substring(0, 30).strip() : t).distinct().limit(MAX_TAGS).toList();
    }

    private static String text(String s, int max) {
        if (s == null || s.isBlank()) return null;
        String v = s.strip();
        return v.length() > max ? v.substring(0, max) : v;
    }
}
