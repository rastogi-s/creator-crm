package com.creatorcrm.practice;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * In the practice copy, keeps out everything that would reach past the sample data: connecting real accounts,
 * entering keys, contact finders (they spend real credits), backups and restores (they'd write to her real backup folder), updates, Start with Windows and
 * problem reports. Also notes each visit, so an unused practice copy is closed after a while.
 */
@Component
@ConditionalOnProperty(name = "crm.practice", havingValue = "true")
public class PracticeFence extends OncePerRequestFilter {
    static final String MESSAGE = "That isn't available in practice mode. Leave practice to do it for real.";

    /** Path prefixes that may not be changed in practice mode (reading them is fine). */
    static final List<String> BLOCKED = List.of(
            "/oauth/",
            "/api/backup/inspect", "/api/backup/restore", "/api/backup/auto",
            "/api/updates/",
            "/api/desktop/",
            "/api/diagnostics/",
            "/api/settings/credentials", "/api/settings/test-anthropic", "/api/settings/mcp-key",
            "/api/settings/password", "/api/settings/instagram-",
            "/api/finders/");

    static boolean blocked(String method, String path) {
        if ("GET".equals(method) || "HEAD".equals(method) || "OPTIONS".equals(method)) return false;
        return BLOCKED.stream().anyMatch(path::startsWith);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        PracticeTicket.touch();
        if (blocked(req.getMethod(), req.getRequestURI())) {
            res.setStatus(HttpServletResponse.SC_CONFLICT);
            res.setContentType("application/json");
            res.getWriter().write("{\"error\":\"" + MESSAGE + "\"}");
            return;
        }
        chain.doFilter(req, res);
    }
}
