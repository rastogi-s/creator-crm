package com.creatorcrm.practice;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/** Starts a real practice copy next to the app, signs in with its ticket, and closes it again. */
@SpringBootTest
@ActiveProfiles("test")
class PracticeModeIntegrationTest {

    @Autowired PracticeMode practice;

    private final CookieManager cookies = new CookieManager();
    private final HttpClient http = HttpClient.newBuilder().cookieHandler(cookies)
            .followRedirects(HttpClient.Redirect.NEVER).build();

    @Test
    void startsWithSampleBrandsSignsInAndCleansUp() throws Exception {
        assertThat(practice.status().state()).isEqualTo(PracticeMode.State.OFF);
        practice.start("localhost", "http://localhost:8080/#more");
        Instant deadline = Instant.now().plus(Duration.ofSeconds(90));
        while (practice.status().state() == PracticeMode.State.STARTING && Instant.now().isBefore(deadline)) Thread.sleep(200);
        PracticeMode.Status s = practice.status();
        assertThat(s.state()).isEqualTo(PracticeMode.State.READY);
        URI enter = URI.create(s.url());
        String base = "http://localhost:" + enter.getPort();

        try {
            // A wrong ticket goes back to the real app; the right one signs in.
            HttpResponse<String> wrong = http.send(HttpRequest.newBuilder(URI.create(base + "/practice/enter?ticket=nope")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(wrong.statusCode()).isEqualTo(302);
            assertThat(wrong.headers().firstValue("Location")).hasValue("http://localhost:8080/#more");

            HttpResponse<String> in = http.send(HttpRequest.newBuilder(enter).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(in.statusCode()).isEqualTo(302);
            assertThat(in.headers().allValues("Set-Cookie")).anyMatch(c -> c.startsWith("CRM_PRACTICE_SESSION="));

            HttpResponse<String> who = http.send(HttpRequest.newBuilder(URI.create(base + "/api/practice")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(who.statusCode()).isEqualTo(200);
            assertThat(who.body()).contains("\"inside\":true");

            HttpResponse<String> deals = http.send(HttpRequest.newBuilder(URI.create(base + "/api/pipeline")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(deals.body()).contains("Bloomleaf Tea");
        } finally {
            practice.stop();
        }
        assertThat(practice.status().state()).isEqualTo(PracticeMode.State.OFF);
        try (var left = Files.newDirectoryStream(Path.of(System.getProperty("java.io.tmpdir")), PracticeMode.FOLDER_PREFIX + "*")) {
            assertThat(left).isEmpty();
        }
    }
}
