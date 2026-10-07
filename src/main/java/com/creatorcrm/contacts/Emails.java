package com.creatorcrm.contacts;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Cleaning and matching rules for email addresses and web domains. Plain code: no Claude, no network. */
public final class Emails {
    private Emails() {}

    private static final Pattern VALID = Pattern.compile("^[a-z0-9._%+'-]+@[a-z0-9-]+(\\.[a-z0-9-]+)*\\.[a-z]{2,24}$");
    /** Finds addresses inside free text, signatures and "Name <a@b.com>" headers. */
    static final Pattern IN_TEXT = Pattern.compile("[A-Za-z0-9._%+'-]+@[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)*\\.[A-Za-z]{2,24}");

    /** System and legal inboxes nobody reads for collaborations. */
    private static final Set<String> JUNK_LOCAL = Set.of(
            "noreply", "no-reply", "no_reply", "donotreply", "do-not-reply", "do_not_reply", "mailer-daemon", "postmaster",
            "abuse", "privacy", "dpo", "gdpr", "legal", "unsubscribe", "bounce", "bounces", "notifications", "notification",
            "alerts", "newsletter", "webmaster", "hostmaster", "security", "billing", "invoices", "accounts-payable");
    private static final Set<String> JUNK_DOMAINS = Set.of(
            "example.com", "example.org", "example.net", "domain.com", "email.com", "yourdomain.com", "yourcompany.com",
            "test.com", "sentry.io", "wixpress.com", "sentry.wixpress.com", "sentry-next.wixpress.com");
    private static final Set<String> FREE_MAIL = Set.of(
            "gmail.com", "googlemail.com", "yahoo.com", "yahoo.co.uk", "yahoo.co.in", "ymail.com", "outlook.com",
            "hotmail.com", "hotmail.co.uk", "live.com", "msn.com", "icloud.com", "me.com", "mac.com", "aol.com",
            "proton.me", "protonmail.com", "gmx.com", "gmx.de", "yandex.com", "zoho.com", "mail.com", "rediffmail.com");
    /** Two-label public suffixes, so shop.glowberry.co.uk resolves to glowberry.co.uk. */
    private static final Set<String> TWO_LABEL_SUFFIXES = Set.of(
            "co.uk", "org.uk", "ac.uk", "me.uk", "com.au", "net.au", "org.au", "co.in", "net.in", "org.in", "co.nz",
            "co.jp", "com.br", "com.mx", "co.za", "com.sg", "com.my", "co.kr", "com.cn", "com.hk", "com.tr", "co.id",
            "com.ar", "com.co", "com.ph", "com.vn", "co.th", "com.tw", "com.pk", "com.ng", "co.ke", "com.eg", "com.sa");

    /** A cleaned, lower-case address, or null when it isn't a usable contact (malformed, a system inbox, a placeholder). */
    public static String clean(String raw) {
        if (raw == null) return null;
        String e = raw.strip();
        if (e.regionMatches(true, 0, "mailto:", 0, 7)) e = e.substring(7);
        int q = e.indexOf('?');
        if (q >= 0) e = e.substring(0, q);
        int lt = e.lastIndexOf('<'), gt = e.lastIndexOf('>');
        if (lt >= 0 && gt > lt) e = e.substring(lt + 1, gt);
        e = e.replaceAll("^[\\s\"'(\\[]+|[\\s\"').,;:\\]]+$", "").toLowerCase(Locale.ROOT);
        if (e.length() > 320 || !VALID.matcher(e).matches()) return null;
        String local = e.substring(0, e.indexOf('@'));
        String domain = e.substring(e.indexOf('@') + 1);
        if (domain.equals("gmail.com") || domain.equals("googlemail.com")) {
            int plus = local.indexOf('+');
            if (plus > 0) local = local.substring(0, plus);
            e = local + "@" + domain;
        }
        if (JUNK_LOCAL.contains(local) || local.startsWith("noreply") || local.startsWith("no-reply")) return null;
        if (JUNK_DOMAINS.contains(domain) || domain.endsWith(".wixpress.com")) return null;
        // Image names that look like addresses: logo@2x.png
        if (domain.matches(".*\\.(png|jpe?g|gif|webp|svg|avif|css|js)$")) return null;
        // Long hex ids are tracking addresses, not people
        if (local.matches("[0-9a-f]{20,}")) return null;
        return e;
    }

    /** Every usable address in a header or text, cleaned, in order, without repeats. */
    public static List<String> findAll(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        Matcher m = IN_TEXT.matcher(text);
        while (m.find()) {
            String e = clean(m.group());
            if (e != null && !out.contains(e)) out.add(e);
        }
        return out;
    }

    public static String domainOf(String email) {
        return email == null || email.indexOf('@') < 0 ? null : email.substring(email.indexOf('@') + 1);
    }

    public static String localOf(String email) {
        return email == null || email.indexOf('@') < 0 ? "" : email.substring(0, email.indexOf('@'));
    }

    /** Personal mailbox providers: an address there never tells us which brand it belongs to. */
    public static boolean isFreeMail(String domain) {
        return domain != null && (FREE_MAIL.contains(domain) || domain.startsWith("yahoo.") || domain.startsWith("hotmail."));
    }

    /** The domain a brand registered: shop.glowberry.com and www.glowberry.com are both glowberry.com. */
    public static String registrable(String host) {
        if (host == null) return null;
        String h = host.strip().toLowerCase(Locale.ROOT);
        if (h.endsWith(".")) h = h.substring(0, h.length() - 1);
        if (h.isEmpty() || !h.contains(".") || h.matches("[0-9.]+")) return null;
        String[] parts = h.split("\\.");
        int keep = parts.length >= 3 && TWO_LABEL_SUFFIXES.contains(parts[parts.length - 2] + "." + parts[parts.length - 1]) ? 3 : 2;
        if (parts.length < keep) return null;
        return String.join(".", java.util.Arrays.copyOfRange(parts, parts.length - keep, parts.length));
    }

    /** The registrable domain of a website, or null. Accepts "glowberry.com", "https://www.glowberry.com/shop". */
    public static String domainOfUrl(String url) {
        if (url == null || url.isBlank()) return null;
        String u = url.strip();
        if (!u.matches("(?i)^[a-z][a-z0-9+.-]*://.*")) u = "https://" + u;
        try {
            return registrable(URI.create(u.replace(" ", "%20")).getHost());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** The brand domain an email points to, or null for personal mailboxes. */
    public static String brandDomain(String email) {
        String d = domainOf(email);
        return d == null || isFreeMail(d) ? null : registrable(d);
    }
}
