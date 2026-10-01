package com.creatorcrm.channels.instagram;

import com.creatorcrm.channels.NormalizedMessage;
import com.creatorcrm.domain.Enums.Direction;
import com.creatorcrm.domain.Enums.Platform;
import com.creatorcrm.ingest.IngestionService;
import com.creatorcrm.security.CryptoService;
import com.creatorcrm.security.SecretName;
import com.creatorcrm.security.SecretStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Real-time Instagram DMs. Public endpoint, so every POST must carry a valid X-Hub-Signature-256
 * (HMAC-SHA256 of the raw body with the app secret); anything else is rejected before parsing.
 */
@RestController
@RequestMapping("/webhooks/instagram")
public class InstagramWebhookController {
    private static final Logger log = LoggerFactory.getLogger(InstagramWebhookController.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_BODY_BYTES = 512 * 1024;

    private final SecretStore secrets;
    private final InstagramConnector connector;
    private final IngestionService ingestion;

    public InstagramWebhookController(SecretStore secrets, InstagramConnector connector, IngestionService ingestion) {
        this.secrets = secrets;
        this.connector = connector;
        this.ingestion = ingestion;
    }

    /** Subscription handshake from the Meta App Dashboard. */
    @GetMapping
    public ResponseEntity<String> verify(@RequestParam("hub.mode") String mode,
                                         @RequestParam("hub.verify_token") String token,
                                         @RequestParam("hub.challenge") String challenge) {
        String expected = secrets.get(SecretName.INSTAGRAM_WEBHOOK_VERIFY_TOKEN).orElse(null);
        if ("subscribe".equals(mode) && CryptoService.constantTimeEquals(expected, token)) {
            return ResponseEntity.ok(challenge.replaceAll("[^A-Za-z0-9_-]", ""));
        }
        return ResponseEntity.status(403).build();
    }

    @PostMapping
    public ResponseEntity<Void> receive(@RequestBody byte[] body,
                                        @RequestHeader(value = "X-Hub-Signature-256", required = false) String signature) {
        if (body.length > MAX_BODY_BYTES || !validSignature(body, signature)) {
            return ResponseEntity.status(401).build();
        }
        try {
            List<NormalizedMessage> messages = parse(JSON.readTree(body));
            if (ingestion.store(messages) > 0) ingestion.processPendingAsync();
        } catch (Exception e) {
            log.warn("Could not process Instagram webhook: {}", e.getMessage());
        }
        return ResponseEntity.ok().build(); // always 200 after a valid signature so Meta doesn't retry-storm
    }

    boolean validSignature(byte[] body, String header) {
        String secret = secrets.get(SecretName.INSTAGRAM_APP_SECRET).orElse(null);
        if (secret == null || header == null || !header.startsWith("sha256=")) return false;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String expected = HexFormat.of().formatHex(mac.doFinal(body));
            return CryptoService.constantTimeEquals(expected, header.substring(7).toLowerCase());
        } catch (Exception e) {
            return false;
        }
    }

    List<NormalizedMessage> parse(JsonNode root) {
        List<NormalizedMessage> out = new ArrayList<>();
        if (!"instagram".equals(root.path("object").asText())) return out;
        for (JsonNode entry : root.path("entry")) {
            String accountId = entry.path("id").asText();
            for (JsonNode ev : entry.path("messaging")) {
                JsonNode msg = ev.path("message");
                if (msg.isMissingNode() || msg.path("is_deleted").asBoolean(false)) continue;
                String sender = ev.path("sender").path("id").asText();
                String recipient = ev.path("recipient").path("id").asText();
                boolean outbound = msg.path("is_echo").asBoolean(false) || sender.equals(accountId);
                String other = outbound ? recipient : sender;
                String otherName = connector.usernameOf(other);
                OffsetDateTime at = OffsetDateTime.ofInstant(
                        Instant.ofEpochMilli(ev.path("timestamp").asLong(System.currentTimeMillis())), ZoneId.systemDefault());
                String me = connector.accountLabel().replace("@", "");
                out.add(new NormalizedMessage(Platform.INSTAGRAM, msg.path("mid").asText(),
                        InstagramConnector.threadKey(other), outbound ? Direction.OUTBOUND : Direction.INBOUND,
                        outbound ? me : otherName, outbound ? me : otherName, outbound ? otherName : me, other, "",
                        InstagramConnector.textOrPlaceholder(msg.path("text").asText("")), "", "", at, false));
            }
        }
        return out;
    }
}
