package com.creatorcrm.channels.gmail;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.api.services.gmail.model.MessagePart;
import com.google.api.services.gmail.model.MessagePartBody;
import com.google.api.services.gmail.model.MessagePartHeader;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;

class EmailHtmlTest {

    @Test
    void keepsLinksPicturesAndLayout() {
        String html = EmailHtml.clean("""
                <html><head><style>.cta{color:red}</style></head><body>
                <table width="600" bgcolor="#fff"><tr><td style="padding:8px">
                <img src="https://cdn.brand.example/banner.png" alt="Banner" width="600">
                <a class="cta" href="https://brand.example/apply">Apply here</a>
                </td></tr></table></body></html>""");
        assertThat(html)
                .contains("<style>.cta{color:red}</style>")
                .contains("<table width=\"600\" bgcolor=\"#fff\">")
                .contains("style=\"padding:8px\"")
                .contains("<img src=\"https://cdn.brand.example/banner.png\" alt=\"Banner\" width=\"600\">")
                .contains("href=\"https://brand.example/apply\"")
                .contains("target=\"_blank\"").contains("rel=\"noopener noreferrer\"");
    }

    @Test
    void removesAnythingThatCouldRunOrSubmit() {
        String html = EmailHtml.clean("""
                <p onclick="steal()">Hi</p><script>steal()</script>
                <img src="x" onerror="steal()"><a href="javascript:steal()">click</a>
                <iframe src="https://evil.example"></iframe><form action="https://evil.example"><input name="p"></form>
                <object data="x.swf"></object><svg onload="steal()"></svg><meta http-equiv="refresh" content="0;url=https://evil.example">
                <base href="https://evil.example/">""");
        assertThat(html.toLowerCase())
                .doesNotContain("script").doesNotContain("onclick").doesNotContain("onerror").doesNotContain("onload")
                .doesNotContain("javascript:").doesNotContain("iframe").doesNotContain("<form").doesNotContain("<input")
                .doesNotContain("object").doesNotContain("<svg").doesNotContain("refresh").doesNotContain("<base")
                .contains("hi");
    }

    @Test
    void dropsTrackingPixels() {
        String html = EmailHtml.clean("<p>Hi</p><img src=\"https://t.example/open.gif\" width=\"1\" height=\"1\">"
                + "<img src=\"https://t.example/o2.gif\" style=\"width: 1px; height: 1px\"><img src=\"https://brand.example/logo.png\" width=\"120\">");
        assertThat(html).doesNotContain("t.example").contains("logo.png");
    }

    @Test
    void foldsEarlierMessagesButKeepsForwardsOpen() {
        String reply = EmailHtml.clean("<div>Sounds good!</div><div class=\"gmail_quote\">On Mon, Ava wrote:"
                + "<blockquote class=\"gmail_quote\">Old text</blockquote></div>");
        assertThat(reply).contains("Sounds good!").contains("<details class=\"quoted\"><summary>Show earlier messages</summary>")
                .doesNotContain("<details class=\"quoted\" open");
        assertThat(reply.split("<details").length).isEqualTo(2); // the nested quote isn't folded twice

        String forward = EmailHtml.clean("<div>FYI</div><div class=\"gmail_quote\">---------- Forwarded message ---------<br>Brief</div>");
        assertThat(forward).contains("<details class=\"quoted\" open>");

        String onlyQuote = EmailHtml.clean("<blockquote type=\"cite\">Everything</blockquote>");
        assertThat(onlyQuote).contains("open");
    }

    @Test
    void textOnlyMessagesGetClickableLinks() {
        String page = EmailHtml.textPage("Apply: https://brand.example/a?x=1&y=2. \"https://q.example\" <b>bold</b>");
        assertThat(page)
                .contains("<a href=\"https://brand.example/a?x=1&amp;y=2\" target=\"_blank\"")
                .contains("</a>.")
                .contains("<a href=\"https://q.example\"")
                .contains("&lt;b&gt;bold&lt;/b&gt;");
    }

    @Test
    void inlinePicturesAreEmbeddedAndCharsetIsHonoured() {
        byte[] png = {(byte) 0x89, 'P', 'N', 'G'};
        MessagePart html = new MessagePart().setMimeType("text/html")
                .setHeaders(List.of(new MessagePartHeader().setName("Content-Type").setValue("text/html; charset=\"ISO-8859-1\"")))
                .setBody(new MessagePartBody().encodeData("<p>Caf\u00e9 <img src=\"cid:logo@brand\"></p>".getBytes(StandardCharsets.ISO_8859_1)));
        MessagePart logo = new MessagePart().setMimeType("image/png")
                .setHeaders(List.of(new MessagePartHeader().setName("Content-ID").setValue("<logo@brand>")))
                .setBody(new MessagePartBody().setAttachmentId("att1"));
        MessagePart root = new MessagePart().setMimeType("multipart/related").setParts(List.of(html, logo));

        String out = MailText.htmlOf(root, p -> "att1".equals(p.getBody().getAttachmentId()) ? png : null);
        assertThat(out).contains("Café").contains("src=\"data:image/png;base64," + Base64.getEncoder().encodeToString(png) + "\"");
        assertThat(EmailHtml.clean(out)).contains("data:image/png;base64,");
        assertThat(MailText.htmlOf(new MessagePart().setMimeType("text/plain"), p -> null)).isEmpty();
    }
}
