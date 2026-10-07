package com.creatorcrm.contacts;

import com.creatorcrm.llm.ContactCards.Card;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Reads contact cards (.vcf), as exported from a phone, Outlook, Gmail or iCloud. One card per person; someone with
 * two work emails becomes two rows that share a name, and the import merges them under one brand.
 */
final class VCards {
    private VCards() {}

    static List<Card> read(String text) {
        List<Card> out = new ArrayList<>();
        Builder cur = null;
        for (String line : unfold(text)) {
            int colon = colon(line);
            if (colon < 0) continue;
            String[] head = line.substring(0, colon).split(";");
            String key = head[0].toUpperCase(Locale.ROOT);
            int dot = key.indexOf('.');
            if (dot >= 0) key = key.substring(dot + 1); // "item1.EMAIL" (Apple groups)
            String params = line.substring(0, colon).toUpperCase(Locale.ROOT);
            String value = line.substring(colon + 1);
            if (params.contains("QUOTED-PRINTABLE")) value = quotedPrintable(value);
            if (key.equals("BEGIN") && value.equalsIgnoreCase("VCARD")) {
                cur = new Builder();
                continue;
            }
            if (cur == null) continue;
            switch (key) {
                case "END" -> {
                    out.addAll(cur.cards());
                    cur = null;
                }
                case "FN" -> cur.name = unescape(value);
                case "N" -> {
                    String[] n = value.split("(?<!\\\\);", -1);
                    String last = n.length > 0 ? unescape(n[0]) : "";
                    String first = n.length > 1 ? unescape(n[1]) : "";
                    cur.nParts = (first + " " + last).strip();
                }
                case "EMAIL" -> cur.emails.add(unescape(value).strip());
                case "ORG" -> cur.org = unescape(value.split("(?<!\\\\);")[0]);
                case "TITLE" -> cur.title = unescape(value);
                case "ROLE" -> { if (cur.title.isBlank()) cur.title = unescape(value); }
                case "TEL" -> { if (cur.phone.isBlank()) cur.phone = unescape(value).replaceFirst("(?i)^tel:", ""); }
                case "URL", "X-SOCIALPROFILE" -> cur.url(unescape(value), params);
                default -> { }
            }
        }
        if (cur != null) out.addAll(cur.cards()); // a file cut off before END:VCARD
        return out;
    }

    private static final class Builder {
        String name = "", nParts = "", org = "", title = "", phone = "", website = "", instagram = "", linkedin = "";
        final List<String> emails = new ArrayList<>();

        void url(String u, String params) {
            String l = u.toLowerCase(Locale.ROOT);
            if (l.contains("instagram.com/") || params.contains("INSTAGRAM")) {
                if (instagram.isBlank()) instagram = u.replaceFirst("(?i)^.*instagram\\.com/", "").replaceAll("[/?].*$", "");
            } else if (l.contains("linkedin.com/") || params.contains("LINKEDIN")) {
                if (linkedin.isBlank()) linkedin = u;
            } else if (website.isBlank()) {
                website = u;
            }
        }

        List<Card> cards() {
            String who = !name.isBlank() ? name : nParts;
            List<Card> out = new ArrayList<>();
            for (String e : emails) {
                if (!e.isBlank()) out.add(new Card(e, who, title, org, website, phone, instagram, linkedin));
            }
            return out;
        }
    }

    /** Long lines continue on the next line after a space or tab (RFC 6350), or after "=" in quoted-printable. */
    static List<String> unfold(String text) {
        List<String> out = new ArrayList<>();
        String body = text.startsWith("﻿") ? text.substring(1) : text;
        for (String raw : body.split("\r\n|\r|\n")) {
            if (!out.isEmpty() && !raw.isEmpty() && (raw.charAt(0) == ' ' || raw.charAt(0) == '\t')) {
                out.set(out.size() - 1, out.get(out.size() - 1) + raw.substring(1));
            } else if (!out.isEmpty() && out.get(out.size() - 1).endsWith("=")
                    && out.get(out.size() - 1).toUpperCase(Locale.ROOT).contains("QUOTED-PRINTABLE")) {
                String prev = out.get(out.size() - 1);
                out.set(out.size() - 1, prev.substring(0, prev.length() - 1) + raw);
            } else {
                out.add(raw);
            }
        }
        return out;
    }

    /** The colon ending the property name, skipping colons inside quoted parameters. */
    private static int colon(String line) {
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') quoted = !quoted;
            else if (c == ':' && !quoted) return i;
        }
        return -1;
    }

    static String unescape(String v) {
        return v.replace("\\n", " ").replace("\\N", " ").replace("\\,", ",").replace("\\;", ";").replace("\\\\", "\\").strip();
    }

    static String quotedPrintable(String v) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c == '=' && i + 2 < v.length()) {
                try {
                    out.write(Integer.parseInt(v.substring(i + 1, i + 3), 16));
                    i += 2;
                    continue;
                } catch (NumberFormatException e) {
                    // not an escape: keep the "="
                }
            }
            out.writeBytes(String.valueOf(c).getBytes(StandardCharsets.UTF_8));
        }
        return out.toString(StandardCharsets.UTF_8);
    }
}
