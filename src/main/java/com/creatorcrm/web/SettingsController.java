package com.creatorcrm.web;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.creatorcrm.channels.ChannelConnector;
import com.creatorcrm.channels.gmail.GmailOAuthController;
import com.creatorcrm.channels.instagram.InstagramConnector;
import com.creatorcrm.channels.instagram.InstagramOAuthController;
import com.creatorcrm.config.CrmProperties;
import com.creatorcrm.ingest.IngestionService;
import com.creatorcrm.repo.MessageRepo;
import com.creatorcrm.security.AppUser;
import com.creatorcrm.security.AppUserRepo;
import com.creatorcrm.security.CryptoService;
import com.creatorcrm.security.SecretName;
import com.creatorcrm.security.SecretStore;
import com.creatorcrm.security.SetupService;
import com.creatorcrm.settings.SettingsService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.security.Principal;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import com.creatorcrm.llm.ClassificationInput;
import com.creatorcrm.llm.LlmClient;
import com.creatorcrm.llm.LlmException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Settings page API: personal preferences, credentials (write-only), channel connections, MCP key.
 * Credential values are never returned, only whether each one is set.
 */
@RestController
@RequestMapping("/api/settings")
public class SettingsController {

    /** Credentials the user may type in. Tokens obtained via OAuth are managed by the connect flows. */
    private static final Set<SecretName> USER_ENTERED = EnumSet.of(
            SecretName.ANTHROPIC_API_KEY, SecretName.GOOGLE_CLIENT_ID, SecretName.GOOGLE_CLIENT_SECRET,
            SecretName.INSTAGRAM_APP_ID, SecretName.INSTAGRAM_APP_SECRET, SecretName.INSTAGRAM_ACCESS_TOKEN);

    public record PasswordChange(@NotBlank String currentPassword, @NotBlank @Size(max = 200) String newPassword) {}

    private final SettingsService settings;
    private final SecretStore secrets;
    private final CryptoService crypto;
    private final List<ChannelConnector> connectors;
    private final InstagramConnector instagram;
    private final GmailOAuthController gmailOAuth;
    private final InstagramOAuthController instagramOAuth;
    private final IngestionService ingestion;
    private final LlmClient llm;
    private final MessageRepo messages;
    private final AppUserRepo users;
    private final PasswordEncoder encoder;
    private final CrmProperties props;
    private final com.creatorcrm.security.SessionEpoch sessions;

    public SettingsController(SettingsService settings, SecretStore secrets, CryptoService crypto,
                              List<ChannelConnector> connectors, InstagramConnector instagram,
                              GmailOAuthController gmailOAuth, InstagramOAuthController instagramOAuth,
                              IngestionService ingestion, MessageRepo messages, AppUserRepo users,
                              PasswordEncoder encoder, CrmProperties props,
                              com.creatorcrm.security.SessionEpoch sessions, LlmClient llm) {
        this.sessions = sessions;
        this.llm = llm;
        this.settings = settings;
        this.secrets = secrets;
        this.crypto = crypto;
        this.connectors = connectors;
        this.instagram = instagram;
        this.gmailOAuth = gmailOAuth;
        this.instagramOAuth = instagramOAuth;
        this.ingestion = ingestion;
        this.messages = messages;
        this.users = users;
        this.encoder = encoder;
        this.props = props;
    }

    @GetMapping
    public Map<String, Object> get() {
        Map<String, Object> channels = new LinkedHashMap<>();
        for (ChannelConnector c : connectors) {
            Map<String, Object> ch = new LinkedHashMap<>();
            ch.put("connected", c.isConnected());
            ch.put("account", c.accountLabel());
            ch.put("lastSync", ingestion.read("sync." + c.platform()).orElse(null));
            ch.put("lastError", ingestion.read("sync." + c.platform() + ".error").orElse(null));
            channels.put(c.platform().name(), ch);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("preferences", settings.all());
        out.put("credentials", secrets.presence());
        out.put("channels", channels);
        out.put("instagramTokenExpiresAt", secrets.get(SecretName.INSTAGRAM_TOKEN_EXPIRES_AT).orElse(null));
        out.put("googleRedirectUri", gmailOAuth.redirectUri());
        out.put("instagramRedirectUri", instagramOAuth.redirectUri());
        out.put("instagramWebhookUrl", props.publicBaseUrl().replaceAll("/+$", "") + "/webhooks/instagram");
        out.put("mcpUrl", props.publicBaseUrl().replaceAll("/+$", "") + "/mcp");
        out.put("mcpAllowSend", props.mcp().allowSend());
        out.put("pendingAnalysis", messages.findByAiProcessedFalseAndFilteredReasonIsNullOrderBySentAtAsc().size());
        return out;
    }

    @PutMapping("/preferences")
    public Map<String, String> updatePreferences(@RequestBody Map<String, String> values) {
        settings.update(values);
        return settings.all();
    }

    /** Write-only. Send a blank value to remove a credential. */
    @PutMapping("/credentials")
    public Map<SecretName, Boolean> updateCredentials(@RequestBody Map<String, String> values) throws Exception {
        for (Map.Entry<String, String> e : values.entrySet()) {
            SecretName name = SecretName.valueOf(e.getKey());
            if (!USER_ENTERED.contains(name)) throw new IllegalArgumentException(name + " cannot be set directly");
            if (e.getValue() != null && e.getValue().length() > 4000) throw new IllegalArgumentException(name + " is too long");
            secrets.put(name, e.getValue());
            if (name == SecretName.ANTHROPIC_API_KEY && e.getValue() != null && !e.getValue().isBlank()) {
                ingestion.processPendingAsync(); // analyze what's waiting now, not at the next sync
            }
            if (name == SecretName.INSTAGRAM_ACCESS_TOKEN && e.getValue() != null && !e.getValue().isBlank()) {
                instagram.refreshIdentity(); // validates the pasted token
            }
        }
        return secrets.presence();
    }

    @PostMapping("/test-anthropic")
    public Map<String, Object> testAnthropic() {
        AnthropicClient c = AnthropicOkHttpClient.builder()
                .apiKey(secrets.require(SecretName.ANTHROPIC_API_KEY)).maxRetries(0).build();
        try {
            c.models().retrieve(settings.writerModel());
        } catch (Exception e) {
            return Map.of("ok", false, "error", "The API key was rejected or the model is unavailable.");
        } finally {
            c.close();
        }
        try {
            // Then one real analysis of a sample email: the same path every synced message takes.
            llm.classify(new ClassificationInput(settings.today(), "EMAIL", "INBOUND", "", "", List.of(),
                    "From: maya@example.com\nSubject: Paid collab?\n\nHi! Could you share your rates for one UGC video?"));
            ingestion.processPendingAsync();
            return Map.of("ok", true);
        } catch (LlmException | LinkageError e) {
            return Map.of("ok", false, "error", "The key works, but analysis failed: " + e.getMessage());
        }
    }

    /** New MCP API key. Returned once; only its hash is stored. Replaces any previous key. */
    @PostMapping("/mcp-key")
    public Map<String, String> newMcpKey() {
        String key = "crm_" + crypto.randomToken(32);
        secrets.put(SecretName.MCP_API_KEY_HASH, CryptoService.sha256Hex(key));
        return Map.of("apiKey", key);
    }

    @PostMapping("/mcp-key/revoke")
    public Map<String, Boolean> revokeMcpKey() {
        secrets.delete(SecretName.MCP_API_KEY_HASH);
        return Map.of("revoked", true);
    }

    /** New webhook verify token, to paste into the Meta App Dashboard. */
    @PostMapping("/instagram-webhook-token")
    public Map<String, String> newWebhookToken() {
        String token = crypto.randomToken(24);
        secrets.put(SecretName.INSTAGRAM_WEBHOOK_VERIFY_TOKEN, token);
        return Map.of("verifyToken", token);
    }

    @PostMapping("/password")
    public Map<String, Boolean> changePassword(@Valid @RequestBody PasswordChange r, Principal principal,
                                               jakarta.servlet.http.HttpSession session) {
        AppUser u = users.findByUsernameIgnoreCase(principal.getName()).orElseThrow();
        if (!encoder.matches(r.currentPassword(), u.passwordHash)) throw new IllegalArgumentException("Current password is wrong");
        if (r.newPassword().length() < SetupService.MIN_PASSWORD_LENGTH) {
            throw new IllegalArgumentException("Password must be at least " + SetupService.MIN_PASSWORD_LENGTH + " characters");
        }
        u.passwordHash = encoder.encode(r.newPassword());
        users.save(u);
        sessions.signOutOthers(session); // the new password applies everywhere; other devices must sign in again
        return Map.of("changed", true);
    }
}
