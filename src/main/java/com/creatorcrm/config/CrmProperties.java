package com.creatorcrm.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Install-level defaults. Everything user-specific (credentials, name, voice, cadence, models) is set in
 * the app's Settings page and stored in the database; these values only seed a fresh install.
 */
@ConfigurationProperties(prefix = "crm")
public record CrmProperties(
        String dataDir,
        /** URL the browser uses to reach the app; OAuth redirect URIs are derived from it. */
        String publicBaseUrl,
        Defaults defaults,
        Gmail gmail,
        Instagram instagram,
        Mcp mcp,
        Schedule schedule,
        Updates updates) {

    public record Defaults(String creatorName, List<Integer> followupCadenceDays, String classifierModel,
                           String classifierEffort, String writerModel, String writerEffort, String timezone) {}

    public record Gmail(String inboxQuery, int initialLookbackDays, int maxMessagesPerSync,
                        boolean pushDraftsToGmail) {}

    public record Instagram(String apiVersion, int maxConversations) {}

    /** MCP can read and update the CRM; sending messages through MCP stays off unless explicitly enabled. */
    public record Mcp(boolean allowSend) {}

    public record Schedule(String syncCron, String morningCheckCron) {}

    /**
     * In-app updates: the app reads the latest GitHub release of {@code repo} and can install it (Windows).
     *
     * @param simulateLatest demo/screen recordings only: pretend this version ("next" = one minor up) is the latest, without the network
     * @param localVideoDir  demo/screen recordings only: serve walkthrough videos from this folder instead of the release
     */
    public record Updates(boolean enabled, String repo, String apiBaseUrl, String downloadBaseUrl, String checkCron,
                          String simulateLatest, String localVideoDir) {}
}
