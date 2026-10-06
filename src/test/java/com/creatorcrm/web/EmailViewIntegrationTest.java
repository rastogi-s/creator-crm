package com.creatorcrm.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.creatorcrm.channels.gmail.GmailConnector;
import com.creatorcrm.domain.Conversation;
import com.creatorcrm.domain.Enums.Direction;
import com.creatorcrm.domain.Enums.Platform;
import com.creatorcrm.domain.Message;
import com.creatorcrm.repo.ConversationRepo;
import com.creatorcrm.repo.MessageRepo;
import jakarta.servlet.Filter;
import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/** An email shown in the app: fetched from Gmail once, cleaned, and framed only by the app itself. */
@SpringBootTest
@ActiveProfiles("test")
class EmailViewIntegrationTest {

    @MockitoSpyBean GmailConnector gmail;
    @Autowired EmailViewController view;
    @Autowired MessageRepo messages;
    @Autowired ConversationRepo conversations;
    @Autowired WebApplicationContext context;
    @Autowired @Qualifier("springSecurityFilterChain") Filter security;

    @BeforeEach
    void connected() {
        doReturn(true).when(gmail).isConnected();
    }

    Message message(String externalId, String content) {
        Conversation c = new Conversation();
        c.platform = Platform.EMAIL;
        c.externalId = "thread-" + UUID.randomUUID();
        c.createdAt = OffsetDateTime.now();
        Message m = new Message();
        m.conversationId = conversations.save(c).id;
        m.externalId = externalId;
        m.direction = Direction.INBOUND;
        m.sender = "brand@example.com";
        m.content = content;
        m.sentAt = OffsetDateTime.now();
        return messages.save(m);
    }

    static String hexId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    @Test
    void showsTheCleanedHtmlAndFetchesItOnlyOnce() throws Exception {
        String id = hexId();
        Message m = message("email:" + id, "Hi, apply here");
        doReturn("<p>Hi, <a href=\"https://brand.example/apply\">apply here</a><script>alert(1)</script></p>"
                + "<img src=\"https://brand.example/banner.png\">").when(gmail).emailHtml(id);
        assertThat(EmailViewController.hasFullEmail(m)).isTrue();

        String page = view.email(m.id).getBody();
        assertThat(page).contains("href=\"https://brand.example/apply\"").contains("banner.png").doesNotContain("<script>alert");
        view.email(m.id);
        verify(gmail, times(1)).emailHtml(id);
        assertThat(messages.findById(m.id).orElseThrow().htmlContent).contains("apply here");
    }

    @Test
    void fallsBackToTheTextWithClickableLinks() throws Exception {
        String id = hexId();
        Message noHtml = message("email:" + id, "Form: https://brand.example/form");
        doReturn("").when(gmail).emailHtml(id);
        assertThat(view.email(noHtml.id).getBody()).contains("<a href=\"https://brand.example/form\"");
        assertThat(EmailViewController.hasFullEmail(messages.findById(noHtml.id).orElseThrow())).isFalse();

        String offline = hexId();
        Message m = message("email:" + offline, "Offline text");
        doThrow(new IOException("no network")).when(gmail).emailHtml(offline);
        assertThat(view.email(m.id).getBody()).contains("Offline text");
        assertThat(messages.findById(m.id).orElseThrow().htmlContent).isNull(); // tried again next time

        Message dm = message("instagram:123", "A DM");
        assertThat(EmailViewController.hasFullEmail(dm)).isFalse();
    }

    @Test
    void onlyTheEmailPageMayBeFramedAndItCannotRunScripts() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(security).build();
        MockHttpServletResponse email = mvc.perform(get("/api/messages/1/email")).andReturn().getResponse();
        assertThat(email.getHeader("X-Frame-Options")).isEqualTo("SAMEORIGIN");
        assertThat(email.getHeader("Content-Security-Policy")).contains("default-src 'none'").contains("sandbox")
                .doesNotContain("script-src").doesNotContain("allow-scripts");

        MockHttpServletResponse app = mvc.perform(get("/api/opportunities")).andReturn().getResponse();
        assertThat(app.getHeader("X-Frame-Options")).isEqualTo("DENY");
        assertThat(app.getHeader("Content-Security-Policy")).contains("frame-ancestors 'none'");
        assertThat(mvc.perform(get("/api/messages/1/email/x")).andReturn().getResponse().getHeader("X-Frame-Options")).isEqualTo("DENY");
        verify(gmail, times(0)).emailHtml(anyString()); // nobody signed in: nothing is fetched
    }
}
