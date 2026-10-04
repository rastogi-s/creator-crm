package com.creatorcrm.channels.instagram;

import com.creatorcrm.channels.OAuthState;
import com.creatorcrm.config.CrmProperties;
import com.creatorcrm.security.SecretName;
import com.creatorcrm.security.SecretStore;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpSession;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * "Connect Facebook" with Facebook Login, for brand lookups only. Her Instagram account must be linked to a
 * Facebook Page she manages; the Page's token is stored (it doesn't expire while she stays an admin of the Page).
 */
@Controller
public class FacebookOAuthController {
    private static final Logger log = LoggerFactory.getLogger(FacebookOAuthController.class);
    static final String SCOPES = "instagram_basic,instagram_manage_comments,instagram_manage_insights,"
            + "pages_show_list,pages_read_engagement,business_management";

    private final SecretStore secrets;
    private final OAuthState oauthState;
    private final InstagramApi api;
    private final String redirectUri;

    private final FacebookConnection connection;

    public FacebookOAuthController(SecretStore secrets, OAuthState oauthState, InstagramApi api,
                                   FacebookConnection connection, CrmProperties props) {
        this.connection = connection;
        this.secrets = secrets;
        this.oauthState = oauthState;
        this.api = api;
        this.redirectUri = props.publicBaseUrl().replaceAll("/+$", "") + "/oauth/facebook/callback";
    }

    public String redirectUri() {
        return redirectUri;
    }

    @PostMapping("/oauth/facebook/start")
    @ResponseBody
    public Map<String, String> start(HttpSession session) {
        String url = "https://www.facebook.com/" + api.apiVersion() + "/dialog/oauth?client_id="
                + enc(secrets.require(SecretName.FACEBOOK_APP_ID))
                + "&redirect_uri=" + enc(redirectUri)
                + "&response_type=code&scope=" + enc(SCOPES)
                + "&state=" + enc(oauthState.issue(session, "facebook"));
        return Map.of("url", url);
    }

    @GetMapping("/oauth/facebook/callback")
    public String callback(@RequestParam(required = false) String code, @RequestParam(required = false) String state,
                           @RequestParam(required = false) String error, HttpSession session) {
        if (error != null || code == null) return "redirect:/#settings?facebook=denied";
        try {
            oauthState.verify(session, "facebook", state);
        } catch (IllegalArgumentException e) {
            return "redirect:/#settings?facebook=state-mismatch";
        }
        try {
            String appId = secrets.require(SecretName.FACEBOOK_APP_ID);
            String appSecret = secrets.require(SecretName.FACEBOOK_APP_SECRET);
            String shortToken = api.facebookExchangeCode(appId, appSecret, redirectUri, code).path("access_token").asText();
            String userToken = api.facebookLongLived(appId, appSecret, shortToken).path("access_token").asText();
            JsonNode pages = api.getFacebook(userToken, "/me/accounts",
                    Map.of("fields", "name,access_token,instagram_business_account{id,username}", "limit", "100"));
            JsonNode page = pickPage(pages, secrets.get(SecretName.INSTAGRAM_USERNAME).orElse(""));
            if (page == null) return "redirect:/#settings?facebook=no-page";
            secrets.put(SecretName.FACEBOOK_PAGE_TOKEN, page.path("access_token").asText());
            secrets.put(SecretName.FACEBOOK_PAGE_NAME, page.path("name").asText());
            secrets.put(SecretName.FACEBOOK_IG_USER_ID, page.path("instagram_business_account").path("id").asText());
            return "redirect:/#settings?facebook=connected";
        } catch (Exception e) {
            log.warn("Facebook connection failed: {}", e.getMessage());
            return "redirect:/#settings?facebook=failed";
        }
    }

    @PostMapping("/oauth/facebook/disconnect")
    @ResponseBody
    public Map<String, Boolean> disconnect() {
        connection.disconnect();
        return Map.of("disconnected", true);
    }

    /** The Page linked to her connected Instagram account, else the first Page with any Instagram account. */
    static JsonNode pickPage(JsonNode pages, String instagramUsername) {
        JsonNode first = null;
        for (JsonNode p : pages.path("data")) {
            JsonNode ig = p.path("instagram_business_account");
            if (ig.path("id").asText().isBlank() || p.path("access_token").asText().isBlank()) continue;
            if (!instagramUsername.isBlank() && instagramUsername.equalsIgnoreCase(ig.path("username").asText())) return p;
            if (first == null) first = p;
        }
        return first;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
