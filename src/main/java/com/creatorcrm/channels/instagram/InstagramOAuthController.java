package com.creatorcrm.channels.instagram;

import com.creatorcrm.channels.OAuthState;
import com.creatorcrm.config.CrmProperties;
import com.creatorcrm.security.SecretName;
import com.creatorcrm.security.SecretStore;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpSession;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * "Connect Instagram" with Instagram Login. Meta requires an HTTPS redirect URI, so this flow needs
 * PUBLIC_BASE_URL to be an https:// address (deployment or tunnel). Alternatively, paste a token
 * generated in the Meta App Dashboard on the Settings page.
 */
@Controller
public class InstagramOAuthController {
    private static final Logger log = LoggerFactory.getLogger(InstagramOAuthController.class);
    static final String SCOPES = "instagram_business_basic,instagram_business_manage_messages";

    private final SecretStore secrets;
    private final OAuthState oauthState;
    private final InstagramApi api;
    private final InstagramConnector connector;
    private final String redirectUri;

    public InstagramOAuthController(SecretStore secrets, OAuthState oauthState, InstagramApi api,
                                    InstagramConnector connector, CrmProperties props) {
        this.secrets = secrets;
        this.oauthState = oauthState;
        this.api = api;
        this.connector = connector;
        this.redirectUri = props.publicBaseUrl().replaceAll("/+$", "") + "/oauth/instagram/callback";
    }

    public String redirectUri() {
        return redirectUri;
    }

    @PostMapping("/oauth/instagram/start")
    @ResponseBody
    public Map<String, String> start(HttpSession session) {
        String url = "https://www.instagram.com/oauth/authorize?client_id="
                + enc(secrets.require(SecretName.INSTAGRAM_APP_ID))
                + "&redirect_uri=" + enc(redirectUri)
                + "&response_type=code&scope=" + enc(SCOPES)
                + "&state=" + enc(oauthState.issue(session, "instagram"));
        return Map.of("url", url);
    }

    @GetMapping("/oauth/instagram/callback")
    public String callback(@RequestParam(required = false) String code, @RequestParam(required = false) String state,
                           @RequestParam(required = false) String error, HttpSession session) {
        if (error != null) return "redirect:/#settings?instagram=denied";
        try {
            oauthState.verify(session, "instagram", state);
        } catch (IllegalArgumentException e) {
            return "redirect:/#settings?instagram=state-mismatch";
        }
        try {
            String appSecret = secrets.require(SecretName.INSTAGRAM_APP_SECRET);
            // Instagram appends "#_" to the code in some flows.
            JsonNode shortLived = api.exchangeCode(secrets.require(SecretName.INSTAGRAM_APP_ID), appSecret,
                    redirectUri, code.replaceAll("#_$", ""));
            JsonNode longLived = api.longLived(appSecret, shortLived.path("access_token").asText());
            storeToken(longLived);
            connector.refreshIdentity();
            return "redirect:/#settings?instagram=connected";
        } catch (Exception e) {
            log.warn("Instagram connection failed: {}", e.getMessage());
            return "redirect:/#settings?instagram=failed";
        }
    }

    @PostMapping("/oauth/instagram/disconnect")
    @ResponseBody
    public Map<String, Boolean> disconnect() {
        secrets.delete(SecretName.INSTAGRAM_ACCESS_TOKEN, SecretName.INSTAGRAM_TOKEN_EXPIRES_AT,
                SecretName.INSTAGRAM_USER_ID, SecretName.INSTAGRAM_USERNAME);
        return Map.of("disconnected", true);
    }

    /** Long-lived tokens last 60 days; refresh daily once within 10 days of expiry. */
    @Scheduled(cron = "0 17 3 * * *")
    public void refreshTokenIfNeeded() {
        if (!connector.isConnected()) return;
        OffsetDateTime expires = secrets.get(SecretName.INSTAGRAM_TOKEN_EXPIRES_AT).map(OffsetDateTime::parse).orElse(null);
        if (expires != null && expires.isAfter(OffsetDateTime.now().plusDays(10))) return;
        try {
            storeToken(api.refresh(secrets.require(SecretName.INSTAGRAM_ACCESS_TOKEN)));
            log.info("Instagram access token refreshed.");
        } catch (Exception e) {
            log.warn("Instagram token refresh failed: {}", e.getMessage());
        }
    }

    public void storeToken(JsonNode token) {
        secrets.put(SecretName.INSTAGRAM_ACCESS_TOKEN, token.path("access_token").asText());
        long expiresIn = token.path("expires_in").asLong(0);
        if (expiresIn > 0) {
            secrets.put(SecretName.INSTAGRAM_TOKEN_EXPIRES_AT, OffsetDateTime.now().plusSeconds(expiresIn).toString());
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
