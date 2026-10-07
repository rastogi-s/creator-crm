package com.creatorcrm.contacts;

import com.creatorcrm.llm.ContactCards.Card;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds people in loose text: what the computer read off a business card or screenshot, a PDF media list, a pasted
 * signature. Plain rules, no Claude. Every email address is one contact; the name, title, company, phone and links
 * around it are picked up by what they look like. It won't be perfect, so the import shows every row for her to fix
 * before anything is saved.
 */
final class TextContacts {
    private TextContacts() {}

    static final int MAX_CONTACTS = 500;

    private static final Pattern PHONE = Pattern.compile("(?<![\\w@/])\\+?\\(?\\d[\\d ().-]{5,}\\d(?![\\w@])");
    private static final Pattern LINKEDIN = Pattern.compile("(?i)(?:https?://)?(?:[a-z]{2,3}\\.)?linkedin\\.com/(?:in|company)/[^\\s,;|]+");
    private static final Pattern INSTAGRAM_URL = Pattern.compile("(?i)(?:https?://)?(?:www\\.)?instagram\\.com/([A-Za-z0-9._]{2,30})");
    private static final Pattern HANDLE = Pattern.compile("(?<![\\w.@])@([A-Za-z0-9._]{2,30})(?![\\w@])");
    private static final Pattern URL = Pattern.compile("(?i)(?<![\\w@.])(?:https?://)?(?:www\\.)?[a-z0-9-]+(?:\\.[a-z0-9-]+)*\\.[a-z]{2,24}(?:/[^\\s,;|]*)?");
    private static final Pattern TITLE = Pattern.compile("(?i)\\b(manager|director|head|lead|partnerships?|marketing|pr|public relations|"
            + "communications|founder|co-founder|ceo|coo|cmo|cfo|owner|president|vp|vice president|influencer|creator|"
            + "community|social|coordinator|specialist|executive|officer|associate|assistant|strategist|publicist|"
            + "producer|agent|talent|brand|growth|content|editor|account)\\b");
    private static final Pattern COMPANY = Pattern.compile("(?i)\\b(inc|llc|ltd|limited|gmbh|co|corp|company|studios?|labs?|"
            + "group|agency|brands?|beauty|skincare|cosmetics|botanics|apparel|foods?|pvt|plc|s\\.?a)\\.?$");
    private static final Pattern LABEL = Pattern.compile("(?i)^(e-?mail|mail|email address|contact|tel|phone|mobile|cell|"
            + "m|p|t|e|w|web|website|site|ig|instagram|insta|linkedin)\\s*[:.]?\\s*$");
    private static final Pattern SPLIT = Pattern.compile("\\s*(?:[,;|\\t•·]|\\s{2,}|\\s[-–—]\\s)\\s*");

    static List<Card> read(String text) {
        List<String> lines = new ArrayList<>();
        for (String l : (text == null ? "" : text).split("\\r?\\n|\\r")) {
            String c = tidy(l);
            if (!c.isEmpty()) lines.add(c);
        }
        List<int[]> at = new ArrayList<>(); // {line, start, end} of each email
        for (int i = 0; i < lines.size(); i++) {
            Matcher m = Emails.IN_TEXT.matcher(lines.get(i));
            while (m.find() && at.size() < MAX_CONTACTS) at.add(new int[] {i, m.start(), m.end()});
        }
        List<Card> out = new ArrayList<>();
        int taken = -1; // last line already used by an earlier contact
        for (int k = 0; k < at.size(); k++) {
            int line = at.get(k)[0];
            String email = lines.get(line).substring(at.get(k)[1], at.get(k)[2]);
            boolean sameLineAsNeighbour = (k > 0 && at.get(k - 1)[0] == line) || (k + 1 < at.size() && at.get(k + 1)[0] == line);
            String rest = withoutEmails(lines.get(line));
            if (sameLineAsNeighbour || hasWords(rest)) {
                // A list: one person per line ("Ana Ruiz, Partnerships, ana@brand.com").
                out.add(fromParts(email, sameLineAsNeighbour ? List.of() : parts(rest)));
                taken = line;
                continue;
            }
            // A card or signature: the lines around the email belong to it.
            int from = Math.max(taken + 1, line - 8);
            int to = line;
            int next = k + 1 < at.size() ? at.get(k + 1)[0] : lines.size();
            while (to + 1 < next && to + 1 <= line + 8 && (next == lines.size() || onlyDetails(lines.get(to + 1)))) to++;
            List<String> parts = new ArrayList<>();
            for (int i = from; i <= to; i++) if (i != line) parts.add(lines.get(i));
            out.add(fromParts(email, parts));
            taken = to;
        }
        return out;
    }

    /** OCR quirks: "maya @ brand.com", "maya[at]brand.com", stray bullets and labels. */
    static String tidy(String line) {
        String l = line.replace(' ', ' ').strip();
        l = l.replaceAll("(?i)\\s*[\\[(]\\s*at\\s*[\\])]\\s*", "@").replaceAll("(?i)\\s*[\\[(]\\s*dot\\s*[\\])]\\s*", ".");
        l = l.replaceAll("(?<=[A-Za-z0-9._-])\\s*@\\s+(?=[A-Za-z0-9-]+\\.[A-Za-z]{2,})", "@");
        l = l.replaceAll("(?<=[A-Za-z0-9._-])\\s+@(?=[A-Za-z0-9-]+\\.[A-Za-z]{2,})", "@");
        l = l.replaceAll("^[•·*>|-]+\\s*", "");
        return l.strip();
    }

    private static String withoutEmails(String line) {
        return Emails.IN_TEXT.matcher(line).replaceAll(" ").strip();
    }

    /** Whether the rest of a line holds something besides labels, phone numbers and links. */
    private static boolean hasWords(String rest) {
        for (String p : parts(rest)) {
            if (isDetail(p)) continue;
            if (p.replaceAll("[^\\p{L}]", "").length() >= 3) return true;
        }
        return false;
    }

    private static boolean onlyDetails(String line) {
        if (Emails.IN_TEXT.matcher(line).find()) return false;
        for (String p : parts(line)) if (!isDetail(p)) return false;
        return true;
    }

    private static boolean isDetail(String p) {
        String s = p.strip();
        return s.isEmpty() || LABEL.matcher(s).matches() || LINKEDIN.matcher(s).find() || INSTAGRAM_URL.matcher(s).find()
                || HANDLE.matcher(s).find() || isPhone(s) || isUrl(s) || s.replaceAll("[^\\p{L}]", "").isEmpty();
    }

    private static List<String> parts(String text) {
        List<String> out = new ArrayList<>();
        for (String p : SPLIT.split(text)) {
            String s = p.strip().replaceFirst("(?i)^(e-?mail|tel|phone|mobile|cell|web|website|ig|instagram)\\s*[:.]\\s*", "")
                    .replaceFirst("^[:.,\\s]+", "").replaceFirst("[:,\\s]+$", "");
            if (!s.isEmpty() && !LABEL.matcher(s).matches()) out.add(s);
        }
        return out;
    }

    private static boolean isPhone(String s) {
        Matcher m = PHONE.matcher(s);
        if (!m.find()) return false;
        int digits = m.group().replaceAll("\\D", "").length();
        return digits >= 7 && digits <= 15;
    }

    private static boolean isUrl(String s) {
        Matcher m = URL.matcher(s);
        return m.find() && m.group().length() >= s.strip().length() - 2 && !s.contains(" ");
    }

    private static Card fromParts(String email, List<String> lines) {
        String name = "", title = "", brand = "", website = "", phone = "", instagram = "", linkedin = "";
        String stem = domainStem(email);
        List<String> names = new ArrayList<>();
        for (String line : lines) {
            for (String p : parts(line)) {
                Matcher li = LINKEDIN.matcher(p);
                if (li.find()) {
                    if (linkedin.isEmpty()) linkedin = li.group();
                    continue;
                }
                Matcher ig = INSTAGRAM_URL.matcher(p);
                if (ig.find()) {
                    if (instagram.isEmpty()) instagram = ig.group(1);
                    continue;
                }
                Matcher h = HANDLE.matcher(p);
                if (h.find() && p.strip().startsWith("@")) {
                    if (instagram.isEmpty()) instagram = h.group(1);
                    continue;
                }
                if (isPhone(p)) {
                    if (phone.isEmpty()) {
                        Matcher m = PHONE.matcher(p);
                        m.find();
                        phone = m.group().strip();
                    }
                    continue;
                }
                if (isUrl(p)) {
                    String d = Emails.domainOfUrl(p);
                    if (website.isEmpty() && d != null && !Emails.isFreeMail(d)) website = p.strip();
                    continue;
                }
                if (p.replaceAll("[^\\p{L}]", "").length() < 2) continue;
                if (brand.isEmpty() && stem != null && matchesStem(p, stem)) {
                    brand = p.strip();
                } else if (looksLikeName(p)) {
                    names.add(p.strip());
                } else if (title.isEmpty() && TITLE.matcher(p).find()) {
                    title = p.strip();
                } else if (brand.isEmpty() && COMPANY.matcher(p.strip()).find()) {
                    brand = p.strip();
                } else if (title.isEmpty() && TITLE.matcher(p).find()) {
                    title = p.strip();
                }
            }
        }
        name = bestName(names, Emails.localOf(email.toLowerCase(Locale.ROOT)));
        return new Card(email, unshout(name), title, unshout(brand), website, phone, instagram, linkedin);
    }

    /** Cards print names in capitals: "SUNLEAF BOTANICS" → "Sunleaf Botanics". Short words (LLC, UK) stay as they are. */
    static String unshout(String s) {
        if (s.isEmpty() || !s.equals(s.toUpperCase(Locale.ROOT)) || s.replaceAll("[^\\p{L}]", "").length() < 4) return s;
        StringBuilder out = new StringBuilder();
        for (String w : s.split(" ", -1)) {
            if (out.length() > 0) out.append(' ');
            out.append(w.length() <= 3 ? w : w.charAt(0) + w.substring(1).toLowerCase(Locale.ROOT));
        }
        return out.toString();
    }

    /** "Sunleaf Botanics" and sunleafbotanics.com: the company line on a card. */
    private static boolean matchesStem(String p, String stem) {
        String letters = p.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        if (letters.length() < 3) return false;
        return letters.equals(stem) || stem.startsWith(letters) || letters.startsWith(stem);
    }

    private static String domainStem(String email) {
        String d = Emails.domainOf(email.toLowerCase(Locale.ROOT));
        if (d == null || Emails.isFreeMail(d)) return null;
        String r = Emails.registrable(d);
        if (r == null) r = d;
        int dot = r.indexOf('.');
        String s = (dot > 0 ? r.substring(0, dot) : r).replaceAll("[^a-z0-9]", "");
        return s.length() >= 3 ? s : null;
    }

    /** 2 to 4 capitalised words of letters: "Maya Chen", "MAYA CHEN", "Anne-Marie O'Neil". */
    static boolean looksLikeName(String p) {
        String s = p.strip();
        if (s.matches(".*[\\d@/:_].*") || TITLE.matcher(s).find() || COMPANY.matcher(s).find()) return false;
        String[] words = s.split("\\s+");
        if (words.length < 2 || words.length > 4) return false;
        for (String w : words) {
            if (!w.matches("\\p{Lu}[\\p{L}'’.-]*")) return false;
        }
        return true;
    }

    /** Of the name-like lines, the one that best matches the email ("maya.chen@" → "Maya Chen"), else the first. */
    private static String bestName(List<String> names, String local) {
        if (names.isEmpty()) return "";
        String best = names.get(0);
        int bestScore = 0;
        for (String n : names) {
            int score = 0;
            for (String w : n.toLowerCase(Locale.ROOT).split("\\s+")) {
                String letters = w.replaceAll("[^\\p{L}]", "");
                if (letters.length() >= 2 && local != null && local.contains(letters)) score += letters.length();
            }
            if (score > bestScore) {
                best = n;
                bestScore = score;
            }
        }
        return best;
    }
}
