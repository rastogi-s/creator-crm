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
        if (body.length() > MAX_CHARS) body = body.substring(0, MAX_CHARS) + "\n[...truncated]";
        return "<untrusted_message direction=\"" + m.direction + "\" from=\"" + escapeAttr(m.senderName, m.sender)
                + "\" date=\"" + m.sentAt.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME) + "\">\n"
                + (m.subject == null || m.subject.isBlank() ? "" : "Subject: " + escape(m.subject) + "\n")
                + escape(body) + "\n</untrusted_message>";
    }

    private static String escapeAttr(String name, String address) {
        String v = (name == null || name.isBlank() ? "" : name + " ") + (address == null ? "" : "<" + address + ">");
        return v.replace("\"", "'").replace("\n", " ");
    }
}
