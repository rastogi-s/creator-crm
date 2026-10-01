package com.creatorcrm.channels.gmail;

import static org.assertj.core.api.Assertions.assertThat;

import com.creatorcrm.domain.Draft;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
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
    void stripsQuotedReplies() {
        String text = "Sounds great, rate works for us!\n\nOn Mon, Sep 28, 2026 at 9:00 AM Maya <maya@glow.co> wrote:\n> Hi, what's your rate?";
        assertThat(MailText.stripQuoted(text).strip()).isEqualTo("Sounds great, rate works for us!");
    }

    @Test
    void htmlIsReducedToText() {
        assertThat(MailText.htmlToText("<p>Hello <b>there</b></p><script>alert(1)</script>").strip()).isEqualTo("Hello there");
    }
}
