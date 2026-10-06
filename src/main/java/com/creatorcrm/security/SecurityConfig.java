package com.creatorcrm.security;

import com.creatorcrm.channels.gmail.EmailHtml;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.header.writers.ContentSecurityPolicyHeaderWriter;
import org.springframework.security.web.header.writers.DelegatingRequestMatcherHeaderWriter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.header.writers.frameoptions.XFrameOptionsHeaderWriter;
import org.springframework.security.web.header.writers.frameoptions.XFrameOptionsHeaderWriter.XFrameOptionsMode;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * Three zones:
 * <ol>
 *   <li>{@code /mcp/**} – stateless, MCP API key (Bearer) required.</li>
 *   <li>{@code /webhooks/**} – public, but every request is HMAC-verified by the controller.</li>
 *   <li>Everything else – session login (dashboard, REST API, OAuth callbacks), CSRF-protected.
 *       Before the admin account exists only the setup page and its endpoint are reachable.</li>
 * </ol>
 */
@Configuration
public class SecurityConfig {

    private static final String CSP = "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; "
            + "connect-src 'self'; frame-ancestors 'none'; form-action 'self' https://accounts.google.com "
            + "https://www.instagram.com https://www.facebook.com; base-uri 'none'; object-src 'none'";

    /** The page that shows one email: {@code /api/messages/{id}/email}. */
    static final RequestMatcher EMAIL_PAGE = req -> req.getRequestURI().matches("/api/messages/\\d+/email");

    @Bean
    public PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder(); // bcrypt by default
    }

    @Bean
    @Order(1)
    public SecurityFilterChain mcpChain(HttpSecurity http, SecretStore secrets) throws Exception {
        http.securityMatcher("/mcp/**", "/mcp")
                .csrf(c -> c.disable()) // no cookies/sessions here, so CSRF does not apply
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(new McpApiKeyFilter(secrets), AnonymousAuthenticationFilter.class)
                .authorizeHttpRequests(a -> a.anyRequest().hasRole("MCP"))
                .exceptionHandling(e -> e.authenticationEntryPoint(
                        (req, res, ex) -> res.sendError(HttpServletResponse.SC_UNAUTHORIZED, "MCP API key required")));
        return http.build();
    }

    @Bean
    @Order(2)
    public SecurityFilterChain webhookChain(HttpSecurity http) throws Exception {
        http.securityMatcher("/webhooks/**")
                .csrf(c -> c.disable()) // Meta can't send CSRF tokens; signature verification replaces it
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a.anyRequest().permitAll());
        return http.build();
    }

    @Bean
    @Order(3)
    public SecurityFilterChain appChain(HttpSecurity http, SetupService setup, SessionEpoch sessions) throws Exception {
        RequestMatcher setupPending = req -> !setup.isSetupComplete();
        RequestMatcher appPage = req -> !EMAIL_PAGE.matches(req);
        http.authorizeHttpRequests(a -> a
                        .requestMatchers("/setup.html", "/setup.js", "/login.html", "/login.js", "/app.css", "/favicon.ico", "/favicon.png", "/error").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/setup/status").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/setup/admin").access((auth, ctx) ->
                                new org.springframework.security.authorization.AuthorizationDecision(!setup.isSetupComplete()))
                        .anyRequest().hasRole("ADMIN"))
                .formLogin(f -> f.loginPage("/login.html").loginProcessingUrl("/login")
                        .defaultSuccessUrl("/", true).failureUrl("/login.html?error").permitAll())
                .logout(l -> l.logoutUrl("/logout").logoutSuccessUrl("/login.html?logout"))
                .csrf(c -> c.spa())
                .addFilterAfter(sessions.filter(), SecurityContextHolderFilter.class)
                .sessionManagement(s -> s.sessionFixation(f -> f.changeSessionId()))
                .exceptionHandling(e -> e
                        .defaultAuthenticationEntryPointFor(
                                (req, res, ex) -> res.sendRedirect("/setup.html"), setupPending)
                        .defaultAuthenticationEntryPointFor(
                                (req, res, ex) -> res.sendError(HttpServletResponse.SC_UNAUTHORIZED),
                                req -> req.getRequestURI().startsWith("/api/")))
                .headers(h -> h
                        // A brand's email is shown inside the app in a sandboxed frame, with a CSP of its own;
                        // every other page keeps the app's CSP and may not be framed at all.
                        .frameOptions(f -> f.disable())
                        .addHeaderWriter(new DelegatingRequestMatcherHeaderWriter(appPage, new ContentSecurityPolicyHeaderWriter(CSP)))
                        .addHeaderWriter(new DelegatingRequestMatcherHeaderWriter(appPage, new XFrameOptionsHeaderWriter(XFrameOptionsMode.DENY)))
                        .addHeaderWriter(new DelegatingRequestMatcherHeaderWriter(EMAIL_PAGE, new ContentSecurityPolicyHeaderWriter(EmailHtml.CSP)))
                        .addHeaderWriter(new DelegatingRequestMatcherHeaderWriter(EMAIL_PAGE, new XFrameOptionsHeaderWriter(XFrameOptionsMode.SAMEORIGIN)))
                        .referrerPolicy(r -> r.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER)));
        return http.build();
    }
}
