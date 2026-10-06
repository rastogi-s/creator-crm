package com.creatorcrm.diagnostics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.creatorcrm.channels.gmail.GmailConnector;
import com.creatorcrm.security.SecretName;
import com.creatorcrm.security.SecretStore;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** Errors logged by the app become one redacted GitHub issue per kind, with repeats as comments. */
@SpringBootTest
@ActiveProfiles("test")
class ErrorReporterIntegrationTest {
    record Call(String method, String path, String body) {}

    static final List<Call> calls = new CopyOnWriteArrayList<>();
    static volatile boolean privateRepo = true;
    static final HttpServer github = start();

    static HttpServer start() {
        try {
            HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            s.createContext("/repos/acme/crm", ex -> { // the repo itself: is it public?
                byte[] b = ("{\"private\":" + privateRepo + "}").getBytes(StandardCharsets.UTF_8);
                ex.sendResponseHeaders(200, b.length);
                ex.getResponseBody().write(b);
                ex.close();
            });
            s.createContext("/repos/acme/crm/issues", ex -> {
                String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                calls.add(new Call(ex.getRequestMethod(), ex.getRequestURI().getPath(), body));
                String path = ex.getRequestURI().getPath();
                String reply = path.endsWith("/comments") ? "{}"
                        : "{\"number\":7,\"html_url\":\"https://github.test/acme/crm/issues/7\",\"state\":\"open\"}";
                byte[] b = reply.getBytes(StandardCharsets.UTF_8);
                ex.sendResponseHeaders(path.equals("/repos/acme/crm/issues") ? 201 : 200, b.length);
                ex.getResponseBody().write(b);
                ex.close();
            });
            s.start();
            return s;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> "jdbc:h2:mem:diagtest;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1");
        r.add("crm.diagnostics.repo", () -> "acme/crm");
        r.add("crm.diagnostics.api-base-url", () -> "http://127.0.0.1:" + github.getAddress().getPort());
        r.add("crm.diagnostics.initial-delay", () -> "PT1H"); // the test sends by hand
    }

    @AfterAll
    static void stop() {
        github.stop(0);
    }

    @Autowired ErrorReporter reporter;
    @Autowired SecretStore secrets;
    @MockitoBean GmailConnector gmail;

    @Test
    void reportsErrorsGroupedAndRedacted() throws Exception {
        assertThatThrownBy(() -> reporter.reportProblem("hi")).hasMessageContaining("aren't set up");
        reporter.sendPending(); // no token: nothing goes out
        assertThat(calls).isEmpty();

        secrets.put(SecretName.ERROR_REPORT_TOKEN, "github_pat_test_token_1234567890");
        secrets.put(SecretName.GMAIL_ADDRESS, "priya.real@gmail.com");
        org.slf4j.Logger log = LoggerFactory.getLogger("com.creatorcrm.ingest.IngestionService");
        for (int i = 0; i < 3; i++) {
            log.error("Sync failed for {}", "priya.real@gmail.com", new IllegalStateException("bad reply for priya.real@gmail.com"));
        }
        log.warn("Network down", new java.net.UnknownHostException("gmail.googleapis.com")); // offline: ignored

        reporter.sendPending();
        assertThat(calls).hasSize(1);
        Call created = calls.get(0);
        assertThat(created.path()).isEqualTo("/repos/acme/crm/issues");
        assertThat(created.body()).contains("[auto-report] IllegalStateException in IngestionService", "crm-fingerprint",
                "**Times:** 3", "Sync failed for [redacted]", "at com.creatorcrm.diagnostics.ErrorReporterIntegrationTest")
                .doesNotContain("priya.real", "github_pat_test");
        assertThat(reporter.status().recent()).singleElement().satisfies(r -> {
            assertThat(r.count()).isEqualTo(3);
            assertThat(r.issueUrl()).isEqualTo("https://github.test/acme/crm/issues/7");
        });

        reporter.sendPending(); // nothing new
        assertThat(calls).hasSize(1);

        ErrorReporter.Sent sent = reporter.reportProblem("Drafts page is blank, my email is priya.real@gmail.com");
        assertThat(sent.url()).isEqualTo("https://github.test/acme/crm/issues/7");
        assertThat(sent.emailed()).isFalse();
        Call report = calls.get(calls.size() - 1);
        assertThat(report.body()).contains("[user-report] Drafts page is blank", "IllegalStateException")
                .doesNotContain("priya.real");

        // Email as well, sent from the connected Gmail account.
        Mockito.when(gmail.isConnected()).thenReturn(true);
        assertThatThrownBy(() -> reporter.setEmailTo("not an address")).isInstanceOf(IllegalArgumentException.class);
        reporter.setEmailTo("dev@example.com");
        LoggerFactory.getLogger("com.creatorcrm.drafts.DraftService").error("Draft failed", new IllegalArgumentException("boom"));
        reporter.sendPending();
        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        Mockito.verify(gmail).sendPlain(Mockito.eq("dev@example.com"), subject.capture(), body.capture());
        assertThat(subject.getValue()).startsWith("Creator CRM error: IllegalArgumentException in DraftService");
        assertThat(body.getValue()).contains("Where:", "--- Stack trace ---", "GitHub issue: https://github.test/acme/crm/issues/7")
                .doesNotContain("<details>", "```", "github_pat_test");

        // Email only: GitHub token removed, reports still go out.
        secrets.put(SecretName.ERROR_REPORT_TOKEN, "");
        int before = calls.size();
        assertThat(reporter.reportProblem("Still broken").emailed()).isTrue();
        assertThat(calls).hasSize(before);

        // Public repo: anyone can read the issues, so messages, her note and log lines go by email only.
        privateRepo = false;
        reporter.forgetRepoVisibility();
        secrets.put(SecretName.ERROR_REPORT_TOKEN, "github_pat_test_token_1234567890");
        LoggerFactory.getLogger("com.creatorcrm.rebook.WinBack").info("Drafting a re-pitch to Glowberry Skin");
        LoggerFactory.getLogger("com.creatorcrm.rebook.WinBack").error("Could not draft a re-pitch to {}", "Glowberry Skin",
                new IllegalStateException("Glowberry Skin has no contact"));
        reporter.sendPending();
        Call publicIssue = calls.get(calls.size() - 1);
        assertThat(publicIssue.body()).contains("[auto-report] IllegalStateException in WinBack\"", "java.lang.IllegalStateException",
                "at com.creatorcrm.diagnostics.ErrorReporterIntegrationTest", "repository is public")
                .doesNotContain("Glowberry");
        Mockito.verify(gmail, Mockito.atLeastOnce()).sendPlain(Mockito.eq("dev@example.com"),
                Mockito.contains("Glowberry"), Mockito.contains("Glowberry"));

        reporter.reportProblem("Glowberry Skin's invoice won't send");
        Call publicReport = calls.get(calls.size() - 1);
        assertThat(publicReport.body()).contains("[user-report] Problem reported from the app", "IllegalStateException in WinBack")
                .doesNotContain("Glowberry");
    }

    @Test
    void codeOnlyKeepsFramesAndDropsMessages() {
        String trace = "java.lang.IllegalStateException: Glowberry failed\n\tat com.x.A.run(A.java:3)\n"
                + "Caused by: java.io.IOException: 401 for brand\n\tat com.x.B.go(B.java:9)\n\t... 4 more";
        assertThat(ErrorReporter.codeOnly(trace)).isEqualTo("java.lang.IllegalStateException\n\tat com.x.A.run(A.java:3)\n"
                + "Caused by: java.io.IOException\n\tat com.x.B.go(B.java:9)\n\t... 4 more");
    }
}
