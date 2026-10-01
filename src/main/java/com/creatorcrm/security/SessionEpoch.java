package com.creatorcrm.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * "Sign out everywhere". Every signed-in session is stamped with the current epoch; bumping the epoch
 * (after a restore or a password change) invalidates all sessions stamped earlier.
 */
@Component
public class SessionEpoch {
    static final String ATTR = "crm.sessionEpoch";

    private final AtomicLong epoch = new AtomicLong(System.nanoTime());

    /** Sign out every session (used after restoring a backup). */
    public void signOutEveryone() {
        epoch.set(System.nanoTime());
    }

    /** Sign out all other sessions but keep this one (used after a password change). */
    public void signOutOthers(HttpSession current) {
        long next = System.nanoTime();
        epoch.set(next);
        if (current != null) current.setAttribute(ATTR, next);
    }

    public OncePerRequestFilter filter() {
        return new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
                    throws ServletException, IOException {
                HttpSession session = req.getSession(false);
                Authentication auth = SecurityContextHolder.getContext().getAuthentication();
                if (session != null && auth != null && auth.isAuthenticated() && !(auth instanceof AnonymousAuthenticationToken)) {
                    Object stamp = session.getAttribute(ATTR);
                    if (stamp == null) {
                        session.setAttribute(ATTR, epoch.get());
                    } else if ((Long) stamp < epoch.get()) {
                        session.invalidate();
                        SecurityContextHolder.clearContext();
                    }
                }
                chain.doFilter(req, res);
            }
        };
    }
}
