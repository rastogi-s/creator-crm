package com.creatorcrm.channels.gmail;

import com.creatorcrm.calendar.CalendarGateway;
import com.creatorcrm.channels.OAuthState;
import com.creatorcrm.config.CrmProperties;
import com.creatorcrm.security.SecretName;
import com.creatorcrm.security.SecretStore;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeRequestUrl;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeTokenRequest;
import com.google.api.client.googleapis.auth.oauth2.GoogleTokenResponse;
import jakarta.servlet.http.HttpSession;
import java.util.Arrays;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

/** "Connect Gmail": standard OAuth 2.0 authorization-code flow with the user's own Google OAuth client. */
@Controller
public class GmailOAuthController {
    private static final Logger log = LoggerFactory.getLogger(GmailOAuthController.class);

    private final SecretStore secrets;
    private final OAuthState oauthState;
    private final GmailConnector gmail;
    private final CalendarGateway calendar;
    private final String redirectUri;

    public GmailOAuthController(SecretStore secrets, OAuthState oauthState, GmailConnector gmail, CalendarGateway calendar,
                                CrmProperties props) {
        this.secrets = secrets;
        this.calendar = calendar;
        this.oauthState = oauthState;
        this.gmail = gmail;
        this.redirectUri = props.publicBaseUrl().replaceAll("/+$", "") + "/oauth/google/callback";
    }

    public String redirectUri() {
        return redirectUri;
    }

    // POST (CSRF-protected) so a third-party page can't start a connection on your behalf.
    @PostMapping("/oauth/google/start")
    @ResponseBody
    public Map<String, String> start(HttpSession session) {
        String url = new GoogleAuthorizationCodeRequestUrl(
                secrets.require(SecretName.GOOGLE_CLIENT_ID), redirectUri, GmailConnector.SCOPES)
                .setAccessType("offline")
                .setApprovalPrompt(null)
                .set("prompt", "consent")
                .setState(oauthState.issue(session, "google"))
                .build();
        return Map.of("url", url);
    }

    @GetMapping("/oauth/google/callback")
    public String callback(@RequestParam(required = false) String code, @RequestParam(required = false) String state,
                           @RequestParam(required = false) String error, HttpSession session) {
        if (error != null) return "redirect:/#settings?gmail=denied";
        try {
            oauthState.verify(session, "google", state);
        } catch (IllegalArgumentException e) {
            return "redirect:/#settings?gmail=state-mismatch";
        }
        try {
            GoogleTokenResponse token = new GoogleAuthorizationCodeTokenRequest(
                    GmailConnector.transport(), GmailConnector.JSON,
                    secrets.require(SecretName.GOOGLE_CLIENT_ID), secrets.require(SecretName.GOOGLE_CLIENT_SECRET),
                    code, redirectUri).execute();
            if (token.getRefreshToken() == null) {
                return "redirect:/#settings?gmail=no-refresh-token";
            }
            secrets.put(SecretName.GMAIL_REFRESH_TOKEN, token.getRefreshToken());
            secrets.put(SecretName.GMAIL_ADDRESS, gmail.gmail().users().getProfile("me").execute().getEmailAddress());
            calendar.recordGrant(calendarGranted(token.getScope()));
            return "redirect:/#settings?gmail=connected";
        } catch (Exception e) {
            log.warn("Gmail connection failed: {}", e.getMessage());
            return "redirect:/#settings?gmail=failed";
        }
    }

    /** Google lets her untick calendar access on its consent screen; the token says what she allowed. */
    static boolean calendarGranted(String scopes) {
        return scopes == null || Arrays.asList(scopes.split("\\s+")).contains(GmailConnector.CALENDAR_SCOPE);
    }

    @PostMapping("/oauth/google/disconnect")
    @ResponseBody
    public Map<String, Boolean> disconnect() {
        secrets.delete(SecretName.GMAIL_REFRESH_TOKEN, SecretName.GMAIL_ADDRESS);
        return Map.of("disconnected", true);
    }
}
