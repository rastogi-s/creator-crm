package com.creatorcrm.shop;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Where an amzn.to or walmrt.us short link leads, so a pasted short link still gets the product's name. Only the
 * redirect address is read (never a page), and only redirects between the stores' own sites are followed.
 */
@Component
public class ShortLinks {
    private static final Logger log = LoggerFactory.getLogger(ShortLinks.class);
    private static final int MAX_HOPS = 4;

    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    /** The address the short link ends up at, or null when it can't be told (offline, not a store link). */
    public String expand(String url) {
        try {
            URI at = ProductLinks.uri(url);
            if (!ProductLinks.isShortHost(ProductLinks.host(at))) return null;
            for (int i = 0; i < MAX_HOPS; i++) {
                HttpResponse<Void> r = http.send(HttpRequest.newBuilder(at).timeout(Duration.ofSeconds(6))
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) CreatorCRM").GET().build(),
                        HttpResponse.BodyHandlers.discarding());
                String next = r.headers().firstValue("location").orElse(null);
                if (r.statusCode() / 100 != 3 || next == null) return i == 0 ? null : at.toString();
                URI to = at.resolve(next);
                if (!"https".equalsIgnoreCase(to.getScheme()) || to.getHost() == null
                        || !ProductLinks.isStoreHost(ProductLinks.host(to))) return null;
                at = to;
                if (!ProductLinks.isShortHost(ProductLinks.host(at))) return at.toString();
            }
            return at.toString();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            log.debug("Could not follow short link {}: {}", url, e.getMessage());
            return null;
        }
    }
}
