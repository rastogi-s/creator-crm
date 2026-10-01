package com.creatorcrm.channels.instagram;

import com.creatorcrm.channels.ChannelConnector;
import com.creatorcrm.channels.NormalizedMessage;
import com.creatorcrm.config.CrmProperties;
import com.creatorcrm.domain.Draft;
import com.creatorcrm.domain.Enums.Direction;
import com.creatorcrm.domain.Enums.Platform;
import com.creatorcrm.security.SecretName;
import com.creatorcrm.security.SecretStore;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Instagram DMs for a professional (Business/Creator) account via the official API.
 * Conversations are keyed by the other party's Instagram-scoped id ("ig:&lt;id&gt;") so that polling and
 * webhooks land in the same conversation.
 */
@Component
public class InstagramConnector implements ChannelConnector {
    private static final Logger log = LoggerFactory.getLogger(InstagramConnector.class);
    /** Meta only allows replies within 24 hours of the user's last message. */
    public static final Duration MESSAGING_WINDOW = Duration.ofHours(24);
    private static final DateTimeFormatter IG_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssZ");

    private final SecretStore secrets;
    private final InstagramApi api;
    private final int maxConversations;

    public InstagramConnector(SecretStore secrets, InstagramApi api, CrmProperties props) {
        this.secrets = secrets;
        this.api = api;
        this.maxConversations = props.instagram().maxConversations();
    }

    @Override
    public Platform platform() {
        return Platform.INSTAGRAM;
    }

    @Override
    public boolean isConnected() {
        return secrets.has(SecretName.INSTAGRAM_ACCESS_TOKEN);
    }

    @Override
    public String accountLabel() {
        return secrets.get(SecretName.INSTAGRAM_USERNAME).map(u -> "@" + u).orElse("");
    }

    public static String threadKey(String otherPartyId) {
        return "ig:" + otherPartyId;
    }

    /** Identify the connected account and remember its id/username. */
    public void refreshIdentity() throws Exception {
        JsonNode me = api.get(token(), "/me", Map.of("fields", "user_id,username"));
        secrets.put(SecretName.INSTAGRAM_USER_ID, me.path("user_id").asText(me.path("id").asText()));
        secrets.put(SecretName.INSTAGRAM_USERNAME, me.path("username").asText());
    }

    @Override
    public List<NormalizedMessage> fetchSince(OffsetDateTime since) throws Exception {
        if (!secrets.has(SecretName.INSTAGRAM_USER_ID)) refreshIdentity();
        String myId = secrets.require(SecretName.INSTAGRAM_USER_ID);
        String myUsername = secrets.get(SecretName.INSTAGRAM_USERNAME).orElse("");

        List<NormalizedMessage> out = new ArrayList<>();
        JsonNode convs = api.get(token(), "/me/conversations", Map.of(
                "platform", "instagram", "fields", "id,updated_time", "limit", String.valueOf(maxConversations)));
        for (JsonNode conv : convs.path("data")) {
            OffsetDateTime updated = parseTime(conv.path("updated_time").asText());
            if (updated != null && updated.isBefore(since)) continue;
            JsonNode msgs = api.get(token(), "/" + conv.path("id").asText(), Map.of(
                    "fields", "messages{id,created_time,from,to,message}"));
            for (JsonNode m : msgs.path("messages").path("data")) {
                OffsetDateTime at = parseTime(m.path("created_time").asText());
                if (at == null || at.isBefore(since)) continue;
                String fromId = m.path("from").path("id").asText();
                String fromName = m.path("from").path("username").asText();
                boolean outbound = fromId.equals(myId) || (!myUsername.isBlank() && fromName.equalsIgnoreCase(myUsername));
                JsonNode to = m.path("to").path("data").path(0);
                String otherId = outbound ? to.path("id").asText() : fromId;
                String otherName = outbound ? to.path("username").asText() : fromName;
                out.add(new NormalizedMessage(Platform.INSTAGRAM, m.path("id").asText(), threadKey(otherId),
                        outbound ? Direction.OUTBOUND : Direction.INBOUND,
                        outbound ? myUsername : fromName, outbound ? myUsername : fromName,
                        outbound ? otherName : myUsername, otherId, "",
                        textOrPlaceholder(m.path("message").asText("")), "", "", at, false));
            }
        }
        return out;
    }

    @Override
    public Optional<String> sendBlockedReason(Draft draft, OffsetDateTime lastInboundAt) {
        if (lastInboundAt == null || lastInboundAt.isBefore(OffsetDateTime.now().minus(MESSAGING_WINDOW))) {
            return Optional.of("Instagram only allows API replies within 24 hours of the brand's last message. "
                    + "Copy this draft and send it from the Instagram app.");
        }
        return Optional.empty();
    }

    @Override
    public SentMessage send(Draft draft) throws Exception {
        JsonNode res = api.sendText(token(), draft.toAddress, draft.body);
        return new SentMessage(res.path("message_id").asText(), threadKey(draft.toAddress));
    }

    /** Best-effort username lookup for a webhook sender. */
    public String usernameOf(String igsid) {
        try {
            return api.get(token(), "/" + igsid, Map.of("fields", "username")).path("username").asText("");
        } catch (Exception e) {
            log.debug("Could not look up Instagram username: {}", e.getMessage());
            return "";
        }
    }

    static String textOrPlaceholder(String text) {
        return text == null || text.isBlank() ? "[attachment or non-text message]" : text;
    }

    private String token() {
        return secrets.require(SecretName.INSTAGRAM_ACCESS_TOKEN);
    }

    private static OffsetDateTime parseTime(String s) {
        try {
            return OffsetDateTime.parse(s, IG_TIME);
        } catch (Exception e) {
            try {
                return OffsetDateTime.parse(s);
            } catch (Exception e2) {
                return null;
            }
        }
    }
}
