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
        if (body.length() > MAX_CHARS) body = body.substring(0, MAX_CHARS) + "\n[...truncated]" + linksIn(body.substring(MAX_CHARS));
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
            sb.append("\n").append(l.length() > 400 ? l.substring(0, 400) + "…" : l);
            if (++count == 10) break;
        }
        return sb.isEmpty() ? "" : "\nLines with links further down:" + sb;
    }

    private static String escapeAttr(String name, String address) {
        String v = (name == null || name.isBlank() ? "" : name + " ") + (address == null ? "" : "<" + address + ">");
        return v.replace("\"", "'").replace("\n", " ");
    }
}
