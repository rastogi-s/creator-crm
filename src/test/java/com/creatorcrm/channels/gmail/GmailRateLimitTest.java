package com.creatorcrm.channels.gmail;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.api.client.googleapis.json.GoogleJsonError;
import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.client.http.HttpHeaders;
import com.google.api.client.http.HttpResponseException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class GmailRateLimitTest {

    private final Instant now = Instant.parse("2026-10-01T04:00:00Z");

    private static GoogleJsonResponseException error(int status, String reason, String message) {
        GoogleJsonError.ErrorInfo info = new GoogleJsonError.ErrorInfo();
        info.setReason(reason);
        GoogleJsonError details = new GoogleJsonError();
        details.setCode(status);
        details.setMessage(message);
        details.setErrors(List.of(info));
        return new GoogleJsonResponseException(
                new HttpResponseException.Builder(status, "x", new HttpHeaders()), details);
    }

    @Test
    void usesGmailsRetryAfterTime() {
        var e = error(429, "rateLimitExceeded", "User-rate limit exceeded.  Retry after 2026-10-01T04:16:12.345Z");
        assertThat(GmailConnector.rateLimitRetryAt(e, now))
                .contains(Instant.parse("2026-10-01T04:16:12.345Z").plusSeconds(30));
        assertThat(GmailConnector.brief(e)).isEqualTo("429: User-rate limit exceeded.  Retry after 2026-10-01T04:16:12.345Z");
    }

    @Test
    void defaultsWhenNoRetryTimeGiven() {
        var e = error(403, "userRateLimitExceeded", "User Rate Limit Exceeded");
        assertThat(GmailConnector.rateLimitRetryAt(e, now)).contains(now.plus(Duration.ofMinutes(15)));
    }

    @Test
    void otherErrorsAreNotRateLimits() {
        assertThat(GmailConnector.rateLimitRetryAt(error(403, "insufficientPermissions", "nope"), now)).isEmpty();
        assertThat(GmailConnector.rateLimitRetryAt(error(404, "notFound", "gone"), now)).isEmpty();
        assertThat(GmailConnector.rateLimitRetryAt(new RuntimeException("boom"), now)).isEmpty();
    }
}
