package com.creatorcrm.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class LocalPageTrackerTest {

    @Test
    void onlyABrowserOnThisComputerCounts() {
        assertThat(LocalPageTracker.isLocal("127.0.0.1", null)).isTrue();
        assertThat(LocalPageTracker.isLocal("0:0:0:0:0:0:0:1", null)).isTrue();
        assertThat(LocalPageTracker.isLocal("100.101.102.103", null)).isFalse();
        // Her phone through Tailscale serve: proxied from localhost, but forwarded.
        assertThat(LocalPageTracker.isLocal("127.0.0.1", "100.101.102.103")).isFalse();
    }

    @Test
    void reportsAPageOnceOneChecksIn() throws Exception {
        LocalPageTracker t = new LocalPageTracker();
        assertThat(t.awaitLocalPage(Duration.ofMillis(10))).isFalse();

        MockHttpServletRequest phone = new MockHttpServletRequest("GET", "/api/setup/status");
        phone.setRemoteAddr("127.0.0.1");
        phone.addHeader("X-Forwarded-For", "100.101.102.103");
        t.doFilter(phone, new MockHttpServletResponse(), new MockFilterChain());
        assertThat(t.awaitLocalPage(Duration.ofMillis(10))).isFalse();

        MockHttpServletRequest tab = new MockHttpServletRequest("GET", "/api/setup/status");
        tab.setRemoteAddr("127.0.0.1");
        t.doFilter(tab, new MockHttpServletResponse(), new MockFilterChain());
        assertThat(t.awaitLocalPage(Duration.ofMillis(10))).isTrue();
    }
}
