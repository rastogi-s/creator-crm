package com.creatorcrm.shop;

import com.creatorcrm.domain.Product;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a pasted shop link: which store it is, the product's ASIN or item number, a name from the address, and
 * whether she earns from it. Amazon product links are tidied to {@code amazon.com/dp/ASIN?tag=her-tag}, adding her
 * Associates tag when the link has none. Nothing here touches the network.
 */
public final class ProductLinks {

    /** What a link turned out to be. {@code warning}: why she might not earn from it, or null. */
    public record Parsed(Product.Store store, String url, String itemId, String name, String warning, boolean shortLink) {}

    private static final Pattern AMAZON_HOST = Pattern.compile("(^|\\.)amazon\\.(com|ca|co\\.uk|de|fr|it|es|in|com\\.au|com\\.mx|co\\.jp|ae|sa|nl|se|pl|sg|com\\.br|com\\.tr|eg|com\\.be)$");
    private static final Pattern AMAZON_SHORT = Pattern.compile("^(amzn\\.to|a\\.co|amzn\\.eu|amzn\\.asia)$");
    private static final Pattern WALMART_HOST = Pattern.compile("(^|\\.)walmart\\.(com|ca)$");
    private static final Pattern WALMART_SHORT = Pattern.compile("^(walmrt\\.us|wmt\\.co)$");
    private static final Pattern ASIN = Pattern.compile("/(?:dp|gp/product|gp/aw/d|exec/obidos/asin|o/ASIN)/([A-Z0-9]{10})(?=[/?#]|$)");
    private static final Pattern SLUG_BEFORE_DP = Pattern.compile("^/([^/]+)/(?:dp|gp/product)/");
    private static final Pattern WALMART_ITEM = Pattern.compile("/ip/(?:([^/?#]+)/)?(\\d{5,})");
    private static final Pattern TAG = Pattern.compile("(?:^|&)tag=([^&#]+)");
    private static final Pattern WALMART_AFFILIATE = Pattern.compile("(?i)(wmlspartner|affiliates_ad_id|sourceid=imp|veh=aff|affp1|campaign_id=\\d)");
    private static final Pattern GOTO_TARGET = Pattern.compile("(?:^|&)u=([^&#]+)");
    public static final Pattern TAG_FORMAT = Pattern.compile("[A-Za-z0-9_-]{2,40}");

    private ProductLinks() {}

    /** Store hosts whose short links are worth following to learn the product (see {@link ShortLinks}). */
    public static boolean isShortHost(String host) {
        return AMAZON_SHORT.matcher(host).matches() || WALMART_SHORT.matcher(host).matches();
    }

    /** Hosts a short link may lead to: the stores themselves, or another of their short links. */
    static boolean isStoreHost(String host) {
        return AMAZON_HOST.matcher(host).find() || WALMART_HOST.matcher(host).find() || isShortHost(host);
    }

    /**
     * {@code raw}: what she pasted. {@code amazonTag}: her Associates tag from Shop settings (blank if none).
     * {@code expanded}: where a short link leads, when known (null otherwise); used only for the name and item id.
     */
    public static Parsed parse(String raw, String amazonTag, String expanded) {
        URI uri = uri(raw);
        String host = host(uri);
        String tag = amazonTag == null || amazonTag.isBlank() ? null : amazonTag.strip();
        if (tag != null && !TAG_FORMAT.matcher(tag).matches()) tag = null;

        if (AMAZON_SHORT.matcher(host).matches()) {
            Parsed target = expanded == null ? null : parse(expanded, null, null);
            boolean amazon = target != null && target.store() == Product.Store.AMAZON;
            return new Parsed(Product.Store.AMAZON, uri.toString(), amazon ? target.itemId() : null, amazon ? target.name() : null, null, true);
        }
        if (WALMART_SHORT.matcher(host).matches()) {
            Parsed target = expanded == null ? null : parse(expanded, null, null);
            boolean walmart = target != null && target.store() == Product.Store.WALMART;
            return new Parsed(Product.Store.WALMART, uri.toString(), walmart ? target.itemId() : null, walmart ? target.name() : null, null, true);
        }
        if (AMAZON_HOST.matcher(host).find()) return amazon(uri, host, tag);
        if (WALMART_HOST.matcher(host).find()) return walmart(uri, host);
        return new Parsed(Product.Store.OTHER, uri.toString(), null, otherName(uri), null, false);
    }

    private static Parsed amazon(URI uri, String host, String herTag) {
        String path = uri.getRawPath() == null ? "" : uri.getRawPath();
        String query = uri.getRawQuery() == null ? "" : uri.getRawQuery();
        Matcher t = TAG.matcher(query);
        String linkTag = t.find() ? decode(t.group(1)) : null;
        String site = host.replaceFirst("^(www|m|smile)\\.", "");
        Matcher asin = ASIN.matcher(path);
        if (asin.find()) {
            String tag = linkTag != null ? linkTag : herTag;
            String url = "https://www." + site + "/dp/" + asin.group(1) + (tag == null ? "" : "?tag=" + tag);
            Matcher slug = SLUG_BEFORE_DP.matcher(path);
            String name = slug.find() ? slugName(slug.group(1)) : null;
            return new Parsed(Product.Store.AMAZON, url, asin.group(1), name, tag == null ? NO_TAG : null, false);
        }
        // A storefront or idea list (amazon.com/shop/...) earns through her influencer page, so it needs no tag.
        if (path.startsWith("/shop/")) {
            String[] parts = path.split("/");
            String name = parts.length > 2 ? "Amazon storefront: " + decode(parts[2]) : "Amazon storefront";
            return new Parsed(Product.Store.AMAZON, uri.toString(), null, name, null, false);
        }
        String url = uri.toString();
        if (linkTag == null && herTag != null) url = withParam(uri, "tag=" + herTag);
        return new Parsed(Product.Store.AMAZON, url, null, null, linkTag == null && herTag == null ? NO_TAG : null, false);
    }

    static final String NO_TAG = "This Amazon link has no Associates tag, so you won't earn from it. Add your tag (it looks like ava-20) "
            + "in Shop settings, or copy the link from the SiteStripe bar at the top of Amazon.";
    static final String PLAIN_WALMART = "This is a plain Walmart link, so you won't earn from it. Make the link with the Walmart "
            + "Creator button or app, and paste that one instead.";

    private static Parsed walmart(URI uri, String host) {
        String query = uri.getRawQuery() == null ? "" : uri.getRawQuery();
        URI product = uri;
        boolean affiliate = host.startsWith("goto.") || WALMART_AFFILIATE.matcher(query).find();
        if (host.startsWith("goto.")) {
            // goto.walmart.com/c/.../?u=<the product page>: read the product from where it leads.
            Matcher u = GOTO_TARGET.matcher(query);
            if (u.find()) {
                try {
                    product = uri(decode(u.group(1)));
                } catch (IllegalArgumentException e) {
                    product = uri;
                }
            }
        }
        Matcher item = WALMART_ITEM.matcher(product.getRawPath() == null ? "" : product.getRawPath());
        String id = null, name = null;
        if (item.find()) {
            id = item.group(2);
            name = item.group(1) == null ? null : slugName(item.group(1));
        }
        return new Parsed(Product.Store.WALMART, uri.toString(), id, name, affiliate ? null : PLAIN_WALMART, false);
    }

    /** A name from the last word-like part of the address, e.g. shop.glowhaus.com/products/dew-serum -> "dew serum". */
    private static String otherName(URI uri) {
        String path = uri.getRawPath() == null ? "" : uri.getRawPath();
        String[] parts = path.split("/");
        for (int i = parts.length - 1; i >= 0; i--) {
            String p = decode(parts[i]).replaceFirst("\\.(html?|php|aspx?)$", "");
            if (p.matches(".*[A-Za-z]{3,}.*") && p.length() <= 120 && !p.matches("(?i)products?|shop|p|item|collections?")) return slugName(p);
        }
        return null;
    }

    /** "CeraVe-Hydrating-Facial-Cleanser" -> "CeraVe Hydrating Facial Cleanser". */
    static String slugName(String slug) {
        String s = decode(slug).replaceAll("[-_+]+", " ").replaceAll("\\s+", " ").strip();
        if (s.isEmpty() || !s.matches(".*\\p{L}.*")) return null;
        return s.length() > 120 ? s.substring(0, 120).strip() : s;
    }

    /** The pasted text as an http(s) address; "amzn.to/x" gets https:// in front. */
    public static URI uri(String raw) {
        String v = raw == null ? "" : raw.strip();
        if (v.isEmpty()) throw new IllegalArgumentException("Paste a product link first");
        if (!v.matches("(?i)^https?://.*")) {
            if (v.matches("(?i)^[a-z][a-z0-9+.-]*:.*")) throw new IllegalArgumentException("Only web links (https://…) can be saved");
            v = "https://" + v;
        }
        if (v.length() > 2000) throw new IllegalArgumentException("That link is too long");
        try {
            URI uri = new URI(v.replace(" ", "%20"));
            if (uri.getHost() == null || !uri.getHost().contains(".")) throw new IllegalArgumentException("That doesn't look like a web link");
            return uri;
        } catch (java.net.URISyntaxException e) {
            throw new IllegalArgumentException("That doesn't look like a web link");
        }
    }

    public static String host(URI uri) {
        return uri.getHost().toLowerCase(Locale.ROOT).replaceFirst("^www\\.", "");
    }

    private static String withParam(URI uri, String param) {
        String s = uri.toString();
        int hash = s.indexOf('#');
        String frag = hash < 0 ? "" : s.substring(hash);
        String base = hash < 0 ? s : s.substring(0, hash);
        return base + (uri.getRawQuery() == null ? "?" : "&") + param + frag;
    }

    private static String decode(String s) {
        try {
            return URLDecoder.decode(s, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return s;
        }
    }
}
