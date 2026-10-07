package com.creatorcrm.contacts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.naming.NameNotFoundException;
import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.NoInitialContextException;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.DirContext;
import javax.naming.directory.InitialDirContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Free check that a domain can receive email at all: does it publish a mail server (MX record)? Catches typos
 * and dead brands before an email bounces and hurts her Gmail. Says nothing about whether one mailbox exists;
 * Hunter's verifier does that. Plain DNS: no Claude, no credits.
 *
 * <p>Uses the computer's own DNS through Java's built-in resolver. The bundled Windows runtime may leave that
 * resolver out, so then it asks Cloudflare's public DNS over HTTPS instead (only the domain name is sent).
 */
@Component
public class MailServers {
    private static final Logger log = LoggerFactory.getLogger(MailServers.class);

    public enum Result {
        /** The domain has a mail server. */
        ACCEPTS_MAIL,
        /** The domain doesn't exist, or says it takes no email. Sending would bounce. */
        NO_MAIL,
        /** Couldn't tell (offline, DNS timed out). Never treated as bad. */
        UNKNOWN
    }

    private static final Duration KEEP = Duration.ofHours(6);
    private static final ObjectMapper JSON = new ObjectMapper();
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private volatile boolean jndiMissing;

    private record Cached(Result result, Instant at) {}

    /** Whether the domain can receive email. Answers are kept for a few hours, so a big batch asks DNS once per domain. */
    public Result check(String domain) {
        if (domain == null || domain.isBlank()) return Result.UNKNOWN;
        String d = domain.strip().toLowerCase(Locale.ROOT).replaceAll("\\.$", "");
        Cached c = cache.get(d);
        if (c != null && c.at().plus(KEEP).isAfter(Instant.now())) return c.result();
        Result r = lookup(d);
        if (r != Result.UNKNOWN) cache.put(d, new Cached(r, Instant.now()));
        return r;
    }

    Result lookup(String domain) {
        try {
            List<String> mx = records(domain, "MX");
            if (!mx.isEmpty()) return interpretMx(mx);
            // No MX: mail goes to the domain's own address, if it has one (RFC 5321 "implicit MX").
            if (!records(domain, "A").isEmpty() || !records(domain, "AAAA").isEmpty()) return Result.ACCEPTS_MAIL;
            return Result.NO_MAIL;
        } catch (NameNotFoundException e) {
            return Result.NO_MAIL;
        } catch (Exception e) {
            log.debug("Mail server check for {} failed: {}", domain, e.toString());
            return Result.UNKNOWN;
        }
    }

    /** A single "0 ." record is a "null MX": the domain says it never accepts email (RFC 7505). */
    static Result interpretMx(List<String> mx) {
        boolean nullMx = mx.size() == 1 && mx.get(0).strip().matches("0\\s+\\.?");
        return nullMx ? Result.NO_MAIL : Result.ACCEPTS_MAIL;
    }

    /**
     * DNS records of one type, as text ("10 mx.example.com."); empty when there are none. Throws
     * {@link NameNotFoundException} when the domain doesn't exist. Overridden in demo mode and tests.
     */
    protected List<String> records(String domain, String type) throws Exception {
        if (!jndiMissing) {
            try {
                return viaJndi(domain, type);
            } catch (NoInitialContextException e) {
                jndiMissing = true;
                log.info("Java's DNS resolver isn't bundled here; checking mail servers over HTTPS instead");
            }
        }
        return viaHttps(domain, type);
    }

    private static List<String> viaJndi(String domain, String type) throws NamingException {
        Hashtable<String, String> env = new Hashtable<>();
        env.put(DirContext.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.dns.DnsContextFactory");
        env.put("com.sun.jndi.dns.timeout.initial", "3000");
        env.put("com.sun.jndi.dns.timeout.retries", "1");
        DirContext ctx = new InitialDirContext(env);
        try {
            Attributes attrs = ctx.getAttributes(domain, new String[] {type});
            Attribute a = attrs.get(type);
            List<String> out = new ArrayList<>();
            if (a == null) return out;
            NamingEnumeration<?> all = a.getAll();
            while (all.hasMore()) out.add(String.valueOf(all.next()));
            return out;
        } finally {
            ctx.close();
        }
    }

    private List<String> viaHttps(String domain, String type) throws Exception {
        String url = "https://cloudflare-dns.com/dns-query?name=" + URLEncoder.encode(domain, StandardCharsets.UTF_8)
                + "&type=" + type;
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(8))
                .header("Accept", "application/dns-json").GET().build(), HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() != 200) throw new IllegalStateException("DNS over HTTPS answered " + r.statusCode());
        JsonNode j = JSON.readTree(r.body());
        int status = j.path("Status").asInt(-1);
        if (status == 3) throw new NameNotFoundException(domain); // NXDOMAIN
        if (status != 0) throw new IllegalStateException("DNS status " + status);
        int wanted = switch (type) {
            case "MX" -> 15;
            case "A" -> 1;
            case "AAAA" -> 28;
            default -> -1;
        };
        List<String> out = new ArrayList<>();
        for (JsonNode a : j.path("Answer")) {
            if (a.path("type").asInt() == wanted) out.add(a.path("data").asText());
        }
        return out;
    }
}
