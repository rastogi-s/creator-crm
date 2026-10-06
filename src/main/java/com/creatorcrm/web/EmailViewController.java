package com.creatorcrm.web;

import com.creatorcrm.channels.gmail.EmailHtml;
import com.creatorcrm.channels.gmail.GmailConnector;
import com.creatorcrm.channels.gmail.GmailLinks;
import com.creatorcrm.domain.Message;
import com.creatorcrm.repo.MessageRepo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * One email as the brand sent it: links, pictures and layout. The HTML is fetched from Gmail the first time and
 * kept; it is cleaned by {@link EmailHtml} every time it's shown, and the page has its own strict CSP (see
 * SecurityConfig). Emails without an HTML version, or when Gmail can't be reached, show the text the app already has.
 */
@RestController
public class EmailViewController {
    private static final Logger log = LoggerFactory.getLogger(EmailViewController.class);

    private final MessageRepo messages;
    private final GmailConnector gmail;

    public EmailViewController(MessageRepo messages, GmailConnector gmail) {
        this.messages = messages;
        this.gmail = gmail;
    }

    /** Whether the UI should show this message's page rather than its text: it has, or may have, an HTML version. */
    static boolean hasFullEmail(Message m) {
        return m.htmlContent == null ? GmailLinks.gmailId(m.externalId) != null : !m.htmlContent.isEmpty();
    }

    @GetMapping("/api/messages/{id}/email")
    public ResponseEntity<String> email(@PathVariable Long id) {
        Message m = messages.findById(id).orElse(null);
        if (m == null) return ResponseEntity.notFound().build();
        String html = html(m);
        return ResponseEntity.ok()
                .contentType(new MediaType(MediaType.TEXT_HTML, java.nio.charset.StandardCharsets.UTF_8))
                .cacheControl(CacheControl.noStore())
                .body(html.isEmpty() ? EmailHtml.textPage(m.content) : EmailHtml.page(html));
    }

    private String html(Message m) {
        if (m.htmlContent != null) return m.htmlContent;
        String gmailId = GmailLinks.gmailId(m.externalId);
        if (gmailId == null || !gmail.isConnected()) return "";
        try {
            String html = gmail.emailHtml(gmailId);
            messages.saveHtml(m.id, html);
            return html;
        } catch (Exception e) {
            log.warn("Could not load the email for message {}: {}", m.id, GmailConnector.brief(e));
            return ""; // tried again next time it's opened
        }
    }
}
