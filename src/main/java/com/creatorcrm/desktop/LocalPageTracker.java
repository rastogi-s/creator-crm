package com.creatorcrm.desktop;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Notices the first request from a browser on this computer, so the app knows a tab is already open.
 * Requests from her phone don't count: Tailscale serve forwards them from 127.0.0.1 too, but adds X-Forwarded-For.
 */
@Component
@ConditionalOnProperty(name = "crm.desktop", havingValue = "true")
public class LocalPageTracker extends OncePerRequestFilter {

    private final CountDownLatch seen = new CountDownLatch(1);

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        if (seen.getCount() > 0 && isLocal(req.getRemoteAddr(), req.getHeader("X-Forwarded-For"))) seen.countDown();
        chain.doFilter(req, res);
    }

    /** True if a local page made a request before the timeout ran out. */
    public boolean awaitLocalPage(Duration timeout) throws InterruptedException {
        return seen.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    static boolean isLocal(String remoteAddr, String forwardedFor) {
        if (forwardedFor != null && !forwardedFor.isBlank()) return false;
        return remoteAddr != null && (remoteAddr.startsWith("127.") || remoteAddr.equals("::1") || remoteAddr.equals("0:0:0:0:0:0:0:1"));
    }
}
