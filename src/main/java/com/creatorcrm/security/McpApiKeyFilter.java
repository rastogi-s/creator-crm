package com.creatorcrm.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/** Authenticates MCP clients with {@code Authorization: Bearer <MCP API key>}; only the key's hash is stored. */
public class McpApiKeyFilter extends OncePerRequestFilter {
    private final SecretStore secrets;

    public McpApiKeyFilter(SecretStore secrets) {
        this.secrets = secrets;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String header = req.getHeader("Authorization");
        String expectedHash = secrets.get(SecretName.MCP_API_KEY_HASH).orElse(null);
        if (header != null && header.startsWith("Bearer ") && expectedHash != null
                && CryptoService.constantTimeEquals(expectedHash, CryptoService.sha256Hex(header.substring(7).trim()))) {
            SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                    "mcp-client", null, List.of(new SimpleGrantedAuthority("ROLE_MCP"))));
        }
        chain.doFilter(req, res);
    }
}
