package com.creatorcrm.diagnostics;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Removes personal data from log text before it leaves the computer: the app's own credentials and account names
 * (matched exactly), then anything that looks like an email address, phone number, token, Instagram handle, URL
 * query string or user folder. Stack traces and class names survive, since those are what a fix needs.
 * It errs on the side of removing too much.
 */
public final class Redactor {

    private record Rule(Pattern pattern, String replacement) {}

    private static final List<Rule> RULES = List.of(
            new Rule(Pattern.compile("(?i)\\b(bearer\\s+)(?!\\[)[^\\s\"',;)]{4,}"), "$1[redacted]"),
            // key=value, key: value, "key": "value"
            new Rule(Pattern.compile("(?i)\\b(token|access_token|refresh_token|id_token|code|client_secret|"
                    + "api[_-]?key|x-api-key|password|passwd|secret|authorization|signature|verify_token)"
                    + "(\\s*[=:]\\s*\"?|\"\\s*:\\s*\")(?!\\[)[^\\s&\"',;)]{4,}"), "$1$2[redacted]"),
            new Rule(Pattern.compile("(?is)(setup code.{0,80}?:\\s*)[A-Za-z0-9]{8,}"), "$1[redacted]"), // first-run code
            new Rule(Pattern.compile("sk-ant-[A-Za-z0-9_\\-]{8,}"), "[api-key]"),
            new Rule(Pattern.compile("\\b(?:gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,})"), "[github-token]"),
            new Rule(Pattern.compile("\\bcrm_[A-Za-z0-9_\\-]{16,}"), "[mcp-key]"),
            new Rule(Pattern.compile("\\beyJ[A-Za-z0-9_\\-]{8,}\\.[A-Za-z0-9_\\-]{8,}\\.[A-Za-z0-9_\\-]+"), "[token]"),
            new Rule(Pattern.compile("\\b(?:ya29\\.[A-Za-z0-9_\\-.]{10,}|1//[A-Za-z0-9_\\-]{20,}|(?:EAA|IGQ|IGA)[A-Za-z0-9_\\-]{20,})"), "[token]"),
            new Rule(Pattern.compile("(https?://[^\\s?\"'<>]+)\\?[^\\s\"'<>]+"), "$1?[query]"),
            new Rule(Pattern.compile("[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}"), "[email]"),
            new Rule(Pattern.compile("(?<![\\w@.])@[A-Za-z0-9_.]{2,30}"), "@[handle]"),
            new Rule(Pattern.compile("(?<![\\w.:\\-])\\+\\d[\\d\\s().\\-]{7,}\\d"), "[phone]"),
            new Rule(Pattern.compile("\\(\\d{3}\\)\\s?\\d{3}[\\s.\\-]\\d{4}\\b"), "[phone]"),
            new Rule(Pattern.compile("\\b\\d{3}[.\\-\\s]\\d{3}[.\\-\\s]\\d{4}\\b"), "[phone]"),
            new Rule(Pattern.compile("\\b\\d{5}\\s\\d{5}\\b"), "[phone]"),
            new Rule(Pattern.compile("(?i)\\b([A-Z]:\\\\(?:Users|Documents and Settings)\\\\)[^\\\\\\s\"']+"), "$1[user]"),
            new Rule(Pattern.compile("(/(?:home|Users)/)[^/\\s\"']+"), "$1[user]"));

    private final List<String> knownValues;

    /**
     * @param knownValues exact strings to remove wherever they appear (credentials, her email address, usernames).
     *                    Very short values are ignored so common words aren't blanked out.
     */
    public Redactor(Collection<String> knownValues) {
        this.knownValues = knownValues.stream()
                .filter(v -> v != null && v.trim().length() >= 4)
                .map(String::trim)
                .distinct()
                .sorted(Comparator.comparingInt(String::length).reversed())
                .toList();
    }

    public String redact(String text) {
        if (text == null || text.isEmpty()) return text;
        String s = text;
        for (String v : knownValues) {
            // Whole words only, so a name like "Creator" doesn't eat "com.creatorcrm" in stack traces.
            s = Pattern.compile("(?<![A-Za-z0-9])" + Pattern.quote(v) + "(?![A-Za-z0-9])", Pattern.CASE_INSENSITIVE)
                    .matcher(s).replaceAll("[redacted]");
        }
        for (Rule r : RULES) {
            Matcher m = r.pattern().matcher(s);
            s = m.replaceAll(r.replacement());
        }
        return s;
    }
}
