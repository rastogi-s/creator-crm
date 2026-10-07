package com.creatorcrm.contacts;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Pulls a job title and phone number out of an email signature. Conservative: unsure means nothing. */
public final class SignatureParser {
    private SignatureParser() {}

    public record Signature(String title, String phone) {}

    private static final Pattern PHONE = Pattern.compile("(\\+?\\d[\\d ().-]{7,18}\\d)");
    /** A title must name a job; this keeps sign-offs ("Thanks!") and company names out. */
    private static final Pattern JOB_WORDS = Pattern.compile("(?i)\\b(manager|director|head|lead|coordinator|specialist|founder|"
            + "co-founder|ceo|cmo|owner|president|executive|associate|marketing|partnerships?|pr|communications|brand|"
            + "influencer|social|creative|officer|vp|chief|strategist|assistant|producer|agent|talent|affiliate|growth)\\b");
    private static final Pattern QUOTE_START = Pattern.compile("^(On .+wrote:|-----Original Message-----|From: .+|>.*)$");

    public static Signature parse(String body, String senderName) {
        if (body == null || body.isBlank() || senderName == null || senderName.isBlank()) return new Signature(null, null);
        List<String> lines = new ArrayList<>();
        for (String raw : body.split("\\R")) {
            String l = raw.strip();
            if (QUOTE_START.matcher(l).matches()) break; // the quoted earlier email starts here
            if (!l.isEmpty()) lines.add(l);
        }
        String first = senderName.strip().split("\\s+")[0].toLowerCase(Locale.ROOT);
        if (first.length() < 2) return new Signature(null, null);
        int from = Math.max(0, lines.size() - 12);
        int nameAt = -1;
        for (int i = lines.size() - 1; i >= from; i--) {
            String l = lines.get(i);
            if (l.length() <= 80 && l.toLowerCase(Locale.ROOT).startsWith(first)) {
                nameAt = i;
                break;
            }
        }
        if (nameAt < 0) return new Signature(null, null);

        String title = null;
        String nameLine = lines.get(nameAt);
        Matcher sep = Pattern.compile("\\s*(\\||–|—| - |,)\\s*").matcher(nameLine);
        if (sep.find() && sep.end() < nameLine.length()) title = plausibleTitle(nameLine.substring(sep.end()));
        if (title == null && nameAt + 1 < lines.size()) title = plausibleTitle(lines.get(nameAt + 1));

        String phone = null;
        for (int i = nameAt; i < Math.min(lines.size(), nameAt + 6) && phone == null; i++) {
            Matcher m = PHONE.matcher(lines.get(i));
            while (m.find()) {
                String digits = m.group(1).replaceAll("\\D", "");
                if (digits.length() >= 8 && digits.length() <= 15 && !m.group(1).matches("\\d{4}-\\d{2}-\\d{2}.*")) {
                    phone = m.group(1).strip();
                    break;
                }
            }
        }
        return new Signature(title, phone);
    }

    private static String plausibleTitle(String s) {
        String t = s.strip();
        if (t.length() < 2 || t.length() > 60 || t.contains("@") || t.toLowerCase(Locale.ROOT).contains("http")
                || t.contains("www.") || t.split("\\s+").length > 8 || t.matches(".*\\d{3,}.*") || t.contains(". ")) return null;
        return JOB_WORDS.matcher(t).find() ? t : null;
    }
}
