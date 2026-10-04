package com.creatorcrm.update;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** The update check and video fetch against a stand-in for GitHub. */
@SpringBootTest
@ActiveProfiles("test")
class UpdateIntegrationTest {
    static final String RELEASE = """
            {"tag_name":"v99.1.0","name":"Creator CRM 99.1.0: Invoices","body":"notes","html_url":"https://example.test/r",
             "assets":[{"name":"Creator-CRM-99.1.0-windows-x64.msi","browser_download_url":"https://example.test/a.msi"},
                       {"name":"SHA256SUMS.txt","browser_download_url":"https://example.test/s.txt"}]}""";
    static final HttpServer github = start();
    static int videoRequests;
    static final Path dataDir = tempDir();

    static Path tempDir() {
        try {
            return Files.createTempDirectory("crm-update-test");
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static HttpServer start() {
        try {
            HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            s.createContext("/repos/acme/crm/releases/latest", ex -> {
                byte[] b = RELEASE.getBytes(StandardCharsets.UTF_8);
                ex.sendResponseHeaders(200, b.length);
                ex.getResponseBody().write(b);
                ex.close();
            });
            s.createContext("/acme/crm/releases/download/v1.1.0/updates.webm", ex -> {
                videoRequests++;
                byte[] b = "fake-video".getBytes(StandardCharsets.UTF_8);
                ex.sendResponseHeaders(200, b.length);
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
        String base = "http://127.0.0.1:" + github.getAddress().getPort();
        r.add("spring.datasource.url", () -> "jdbc:h2:mem:updtest;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1");
        r.add("crm.updates.enabled", () -> "true");
        r.add("crm.updates.repo", () -> "acme/crm");
        r.add("crm.updates.api-base-url", () -> base);
        r.add("crm.updates.download-base-url", () -> base);
        r.add("crm.data-dir", () -> dataDir.toString()); // fresh each run, so the video cache starts empty
    }

    @AfterAll
    static void stop() {
        github.stop(0);
    }

    @Autowired UpdateService updates;
    @Autowired WhatsNewService whatsNew;
    @Autowired VideoCache videos;

    @Test
    void findsTheNewerReleaseButOnlyOffersADownloadOutsideTheWindowsApp() {
        UpdateService.Status s = updates.check();
        assertThat(s.currentVersion()).isNotBlank();
        assertThat(s.latestVersion()).isEqualTo("99.1.0");
        assertThat(s.updateAvailable()).isTrue();
        assertThat(s.releaseName()).isEqualTo("Creator CRM 99.1.0: Invoices");
        assertThat(s.canInstall()).isFalse();
        assertThat(s.installHint()).isNotBlank();
        assertThatThrownBy(updates::install).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void autoCheckCanBeTurnedOff() {
        updates.setAutoCheck(false);
        assertThat(updates.status().autoCheck()).isFalse();
        updates.setAutoCheck(true);
        assertThat(updates.status().autoCheck()).isTrue();
    }

    @Test
    void whatsNewListsShippedNotesUntilSeen() {
        WhatsNewService.View v = whatsNew.view();
        assertThat(v.all()).isNotEmpty();
        assertThat(v.all()).allSatisfy(e -> assertThat(Versions.compare(e.version(), v.currentVersion().replaceFirst("-.*", ""))).isLessThanOrEqualTo(0));
        whatsNew.markSeen();
        assertThat(whatsNew.view().unseen()).isEmpty();
    }

    @Test
    void videosAreFetchedOnceAndOnlyWhenTheReleaseNotesNameThem() throws Exception {
        Path first = videos.get("1.1.0", "updates.webm");
        Path again = videos.get("1.1.0", "updates.webm");
        assertThat(Files.readString(again)).isEqualTo("fake-video");
        assertThat(again).isEqualTo(first);
        assertThat(videoRequests).isEqualTo(1);
        assertThatThrownBy(() -> videos.get("1.1.0", "../../secret.webm")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> videos.get("1.1.0", "unlisted.webm")).isInstanceOf(IllegalArgumentException.class);
    }
}
