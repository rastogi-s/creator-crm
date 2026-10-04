package com.creatorcrm.channels.gmail;

import static org.assertj.core.api.Assertions.assertThat;

import com.creatorcrm.domain.Attachment;
import com.creatorcrm.domain.Draft;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;

class MailTextTest {

    @Test
    void headerValuesCannotInjectExtraHeaders() {
        Draft d = new Draft();
        d.toAddress = "brand@example.com\r\nBcc: attacker@evil.test";
        d.subject = "Hello\nBcc: attacker@evil.test";
        d.inReplyTo = "<id@x>\r\nX-Evil: 1";
        d.body = "Hi there";
        String raw = new String(Base64.getUrlDecoder().decode(MailText.rawMessage(d)), StandardCharsets.UTF_8);
        String headers = raw.substring(0, raw.indexOf("\r\n\r\n"));
        assertThat(headers.lines()).noneMatch(l -> l.startsWith("Bcc:") || l.startsWith("X-Evil:"));
    }

    @Test
    void plainDraftIsSingleTextPart() {
        Draft d = new Draft();
        d.toAddress = "brand@example.com";
        d.subject = "Hi";
        d.body = "Hello";
        String raw = new String(Base64.getUrlDecoder().decode(MailText.rawMessage(d)), StandardCharsets.UTF_8);
        assertThat(raw).contains("Content-Type: text/plain; charset=UTF-8").doesNotContain("multipart");
    }

    @Test
    void attachmentsMakeAMultipartMessage() {
        byte[] pdf = "%PDF-1.4 fake invoice".getBytes(StandardCharsets.US_ASCII);
        Draft d = new Draft();
        d.toAddress = "brand@example.com";
        d.subject = "Invoice INV-2026-001";
        d.body = "Invoice attached. Thanks!";
        d.attachments = List.of(new Attachment("INV-2026-001.pdf", "application/pdf", pdf),
                new Attachment("evil\"\r\nBcc: x@evil.test.pdf", "application/pdf", pdf));
        String raw = new String(Base64.getUrlDecoder().decode(MailText.rawMessage(d)), StandardCharsets.UTF_8);

        String headers = raw.substring(0, raw.indexOf("\r\n\r\n"));
        String boundary = headers.replaceAll("(?s).*boundary=\"([^\"]+)\".*", "$1");
        assertThat(headers).contains("Content-Type: multipart/mixed; boundary=\"" + boundary + "\"");
        String[] parts = raw.split("\r\n--" + java.util.regex.Pattern.quote(boundary));
        assertThat(parts).hasSize(5); // headers, text, pdf, renamed pdf, closing "--"
        assertThat(raw).contains("--" + boundary + "--");

        String text = parts[1];
        assertThat(text).contains("Content-Type: text/plain; charset=UTF-8");
        String textBody = text.substring(text.indexOf("\r\n\r\n") + 4).strip();
        assertThat(new String(Base64.getMimeDecoder().decode(textBody), StandardCharsets.UTF_8)).isEqualTo("Invoice attached. Thanks!");

        String att = parts[2];
        assertThat(att).contains("Content-Type: application/pdf; name=\"INV-2026-001.pdf\"",
                "Content-Disposition: attachment; filename=\"INV-2026-001.pdf\"", "Content-Transfer-Encoding: base64");
        String attBody = att.substring(att.indexOf("\r\n\r\n") + 4).strip();
        assertThat(Base64.getMimeDecoder().decode(attBody)).isEqualTo(pdf);

        assertThat(parts[3]).contains("filename=\"evil___Bcc__x_evil.test.pdf\"");
        assertThat(raw.lines()).noneMatch(l -> l.startsWith("Bcc:"));
    }

    @Test
    void stripsQuotedReplies() {
        String text = "Sounds great, rate works for us!\n\nOn Mon, Sep 28, 2026 at 9:00 AM Maya <maya@glow.co> wrote:\n> Hi, what's your rate?";
        assertThat(MailText.stripQuoted(text).strip()).isEqualTo("Sounds great, rate works for us!");
    }

    @Test
    void htmlIsReducedToText() {
        assertThat(MailText.htmlToText("<p>Hello <b>there</b></p><script>alert(1)</script>").strip()).isEqualTo("Hello there");
    }

    @Test
    void htmlLinksKeepTheirUrl() {
        String text = MailText.htmlToText("<p>Hi Ava!</p><p>Please <a class=\"btn\" href=\"https://forms.example/apply?x=1&amp;y=2\">"
                + "<b>fill in our form</b></a> by Friday.</p><a href='https://brand.example/'>https://brand.example/</a>"
                + "<a href=\"mailto:hi@brand.example\">email us</a>");
        assertThat(text).contains("fill in our form (https://forms.example/apply?x=1&y=2)")
                .contains("https://brand.example/")
                .doesNotContain("https://brand.example/ (https://brand.example/)")
                .contains("email us");
    }

    @Test
    void gmailLinkOnlyForGmailMessages() {
        assertThat(GmailLinks.url("email:18c2f0a9b3d4e5f6", "ava@gmail.com"))
                .isEqualTo("https://mail.google.com/mail/?authuser=ava%40gmail.com#all/18c2f0a9b3d4e5f6");
        assertThat(GmailLinks.url("email:18c2f0a9b3d4e5f6", "")).isEqualTo("https://mail.google.com/mail/#all/18c2f0a9b3d4e5f6");
        assertThat(GmailLinks.url("instagram:aWdfZAG93", "ava@gmail.com")).isNull();
        assertThat(GmailLinks.url("email:demo-1", "ava@gmail.com")).isNull();
        assertThat(GmailLinks.url(null, "")).isNull();
    }

    private static com.google.api.services.gmail.model.MessagePart part(String mime, String text) {
        return new com.google.api.services.gmail.model.MessagePart().setMimeType(mime)
                .setBody(new com.google.api.services.gmail.model.MessagePartBody().encodeData(text.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void applyHereLinkMissingFromPlainTextComesFromTheHtml() {
        var email = new com.google.api.services.gmail.model.MessagePart().setMimeType("multipart/alternative").setParts(List.of(
                part("text/plain", "Hi Ava!\nWe'd love you in our creator program. Apply here by Friday.\n\nUnsubscribe"),
                part("text/html", "<p>Hi Ava!</p><p>We'd love you in our creator program. Apply <a href=\"https://forms.bloom.example/apply?id=7\">here</a>"
                        + " by Friday.</p><p><a href=\"https://bloom.example/unsubscribe?u=1\">Unsubscribe</a></p>")));
        String body = MailText.bodyOf(email);
        assertThat(body).startsWith("Hi Ava!")
                .contains("Links in this email:\n- We'd love you in our creator program. Apply here (https://forms.bloom.example/apply?id=7) by Friday.")
                .doesNotContain("unsubscribe?u=1");
    }

    @Test
    void noLinkListWhenThePlainTextAlreadyHasTheUrl() {
        var email = new com.google.api.services.gmail.model.MessagePart().setMimeType("multipart/alternative").setParts(List.of(
                part("text/plain", "Apply here <https://forms.bloom.example/apply>"),
                part("text/html", "Apply <a href=\"https://forms.bloom.example/apply\">here</a>")));
        assertThat(MailText.bodyOf(email)).isEqualTo("Apply here <https://forms.bloom.example/apply>");
    }
}
