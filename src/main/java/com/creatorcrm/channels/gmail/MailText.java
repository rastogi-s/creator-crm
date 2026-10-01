package com.creatorcrm.channels.gmail;

import com.creatorcrm.domain.Draft;
import com.google.api.services.gmail.model.MessagePart;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** Body extraction and RFC 822 message building for Gmail. */
final class MailText {
    private static final int MAX_BODY = 20_000;

    private MailText() {}

    /** Prefer text/plain; fall back to text/html with tags stripped. Quoted replies are trimmed. */
    static String bodyOf(MessagePart payload) {
        if (payload == null) return "";
        String plain = find(payload, "text/plain");
        String text = plain != null ? plain : htmlToText(find(payload, "text/html"));
        text = stripQuoted(text == null ? "" : text).strip();
        return text.length() > MAX_BODY ? text.substring(0, MAX_BODY) : text;
    }

    private static String find(MessagePart part, String mime) {
        if (part.getMimeType() != null && part.getMimeType().startsWith(mime)) {
            String s = GmailConnector.decode(part);
            if (!s.isBlank()) return s;
        }
        if (part.getParts() != null) {
            for (MessagePart p : part.getParts()) {
                String s = find(p, mime);
                if (s != null) return s;
            }
        }
        return null;
    }

    static String htmlToText(String html) {
        if (html == null) return null;
        return html.replaceAll("(?is)<(script|style)[^>]*>.*?</\\1>", " ")
                .replaceAll("(?i)<br\\s*/?>|</p>|</div>|</li>", "\n")
                .replaceAll("<[^>]+>", " ")
                .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'")
                .replaceAll("[ \\t]+", " ")
                .replaceAll("\\n\\s*\\n+", "\n\n");
    }

    /** Cut the quoted history ("On Mon, X wrote:" / "> ...") so we only keep the new part of each message. */
    static String stripQuoted(String text) {
        String[] markers = {"\nOn ", "\n-----Original Message-----", "\nFrom: ", "\n________________________________"};
        int cut = text.length();
        for (String m : markers) {
            int i = text.indexOf(m);
            while (i >= 0) {
                String rest = text.substring(i, Math.min(text.length(), i + 400));
                boolean looksQuoted = !m.equals("\nOn ") || rest.matches("(?s)\\nOn .{0,300}wrote:.*");
                if (looksQuoted) {
                    cut = Math.min(cut, i);
                    break;
                }
                i = text.indexOf(m, i + 1);
            }
        }
        StringBuilder sb = new StringBuilder();
        for (String line : text.substring(0, cut).split("\n", -1)) {
            if (!line.startsWith(">")) sb.append(line).append('\n');
        }
        return sb.toString();
    }

    static String rawMessage(Draft d) {
        StringBuilder sb = new StringBuilder();
        sb.append("To: ").append(headerSafe(d.toAddress)).append("\r\n");
        sb.append("Subject: ").append(encodeHeader(d.subject == null ? "" : d.subject)).append("\r\n");
        if (d.inReplyTo != null && !d.inReplyTo.isBlank()) {
            sb.append("In-Reply-To: ").append(headerSafe(d.inReplyTo)).append("\r\n");
            sb.append("References: ").append(headerSafe(d.inReplyTo)).append("\r\n");
        }
        sb.append("MIME-Version: 1.0\r\n");
        sb.append("Content-Type: text/plain; charset=UTF-8\r\n");
        sb.append("Content-Transfer-Encoding: base64\r\n\r\n");
        sb.append(Base64.getMimeEncoder().encodeToString(d.body.getBytes(StandardCharsets.UTF_8)));
        return Base64.getUrlEncoder().encodeToString(sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** Prevent header injection via CR/LF in values that came from third-party mail. */
    static String headerSafe(String v) {
        return v == null ? "" : v.replaceAll("[\\r\\n]+", " ").trim();
    }

    private static String encodeHeader(String v) {
        String safe = headerSafe(v);
        boolean ascii = safe.chars().allMatch(c -> c >= 32 && c < 127);
        return ascii ? safe : "=?UTF-8?B?" + Base64.getEncoder().encodeToString(safe.getBytes(StandardCharsets.UTF_8)) + "?=";
    }
}
