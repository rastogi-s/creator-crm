package com.creatorcrm.llm;

import com.creatorcrm.domain.Message;
import java.time.format.DateTimeFormatter;

/**
 * Wraps third-party text so the model can tell data from instructions, and so a message can't close the
 * wrapper tag early to smuggle in fake instructions.
 */
public final class Untrusted {
    private static final int MAX_CHARS = 6_000;

    private Untrusted() {}

    public static String escape(String s) {
        if (s == null) return "";
        return s.replace("<untrusted_message", "&lt;untrusted_message")
                .replace("</untrusted_message", "&lt;/untrusted_message")
                .replace("<conversation_summary", "&lt;conversation_summary")
                .replace("</conversation_summary", "&lt;/conversation_summary");
    }

    public static String wrap(Message m) {
        String body = m.content == null ? "" : m.content;
        if (body.length() > MAX_CHARS) {
            String head = cut(body, MAX_CHARS);
            body = head + "\n[...truncated]" + linksIn(body.substring(head.length()));
        }
        return "<untrusted_message direction=\"" + m.direction + "\" from=\"" + escapeAttr(m.senderName, m.sender)
                + "\" date=\"" + m.sentAt.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME) + "\">\n"
                + (m.subject == null || m.subject.isBlank() ? "" : "Subject: " + escape(m.subject) + "\n")
                + escape(body) + "\n</untrusted_message>";
    }

    private static final java.util.regex.Pattern URL = java.util.regex.Pattern.compile("https?://\\S+");

    /** Lines with a link from the cut-off part of a long message, so an "Apply here" near the end isn't lost. */
    static String linksIn(String rest) {
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (String line : rest.split("\n")) {
            String l = line.strip();
            if (!URL.matcher(l).find() || l.toLowerCase(java.util.Locale.ROOT).contains("unsubscribe")) continue;
            sb.append("\n").append(l.length() > 400 ? cut(l, 400) + "…" : l);
            if (++count == 10) break;
        }
        return sb.isEmpty() ? "" : "\nLines with links further down:" + sb;
    }

    /** The first {@code max} chars of {@code s}, one shorter if that would split an emoji (a surrogate pair) in half. */
    static String cut(String s, int max) {
        if (s.length() <= max) return s;
        return s.substring(0, Character.isHighSurrogate(s.charAt(max - 1)) ? max - 1 : max);
    }

    /**
     * {@code s} with any half of an emoji (a lone surrogate) replaced by U+FFFD. The JSON writer sends a lone
     * surrogate as a bare {@code \\uD83D} escape, which the Claude API rejects as invalid JSON ("unexpected end of
     * hex escape"), failing the whole request.
     */
    public static String wellFormed(String s) {
        if (s == null) return null;
        StringBuilder sb = null;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean lone = Character.isHighSurrogate(c)
                    ? i + 1 >= s.length() || !Character.isLowSurrogate(s.charAt(i + 1))
                    : Character.isLowSurrogate(c) && (i == 0 || !Character.isHighSurrogate(s.charAt(i - 1)));
            if (lone && sb == null) sb = new StringBuilder(s.length()).append(s, 0, i);
            if (sb != null) sb.append(lone ? '\uFFFD' : c);
        }
        return sb == null ? s : sb.toString();
    }

    private static String escapeAttr(String name, String address) {
        String v = (name == null || name.isBlank() ? "" : name + " ") + (address == null ? "" : "<" + address + ">");
        return v.replace("\"", "'").replace("\n", " ");
    }
}
