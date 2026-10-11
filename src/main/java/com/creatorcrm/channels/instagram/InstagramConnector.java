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
import java.util.HashMap;
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
    private static final int MAX_PAGES = 10;
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
        List<JsonNode> convs = conversations(since);
        int seen = 0, recent = 0;
        OffsetDateTime newest = null;
        for (JsonNode conv : convs) {
            seen++;
            OffsetDateTime updated = parseTime(conv.path("updated_time").asText());
            if (updated != null && (newest == null || updated.isAfter(newest))) newest = updated;
            if (updated != null && updated.isBefore(since)) continue;
            recent++;
            JsonNode msgs = api.get(token(), "/" + conv.path("id").asText(), Map.of(
                    "fields", "messages{id,created_time,from,to,message}"));
            for (JsonNode listed : msgs.path("messages").path("data")) {
                OffsetDateTime at = parseTime(listed.path("created_time").asText());
                if (at == null || at.isBefore(since)) continue;
                // Meta documents the details per message; ask for them when the list came back with ids only.
                JsonNode m = listed.has("from") ? listed : details(listed.path("id").asText());
                if (m == null) continue;
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
        lastFetch = new FetchReport(OffsetDateTime.now(), since, seen, recent, newest, out.size());
        log.info("Instagram: {} conversations returned ({} active since {}), {} messages", seen, recent, since, out.size());
        return out;
    }

    /**
     * Her conversations, newest first, following Instagram's "next" pages. Instagram can send back an empty page that
     * still has a next page (it filters some chats out after paging), so an empty first page doesn't mean no DMs.
     * Stops at {@code maxConversations}, at a page whose last chat is older than {@code since}, or after MAX_PAGES.
     */
    private List<JsonNode> conversations(OffsetDateTime since) throws Exception {
        List<JsonNode> all = new ArrayList<>();
        String after = null;
        for (int page = 0; page < MAX_PAGES && all.size() < maxConversations; page++) {
            Map<String, String> q = new HashMap<>(Map.of("platform", "instagram", "fields", "id,updated_time",
                    "limit", String.valueOf(maxConversations)));
            if (after != null) q.put("after", after);
            JsonNode res = api.get(token(), "/me/conversations", q);
            JsonNode last = null;
            for (JsonNode conv : res.path("data")) {
                if (all.size() < maxConversations) all.add(conv);
                last = conv;
            }
            OffsetDateTime lastUpdated = last == null ? null : parseTime(last.path("updated_time").asText());
            if (lastUpdated != null && lastUpdated.isBefore(since)) break;
            String next = res.path("paging").has("next") ? res.path("paging").path("cursors").path("after").asText("") : "";
            if (next.isBlank() || next.equals(after)) break;
            after = next;
        }
        return all;
    }

    /** One message's sender and text; null when Instagram won't give it (only the 20 newest of a chat have details). */
    private JsonNode details(String messageId) {
        try {
            return api.get(token(), "/" + messageId, Map.of("fields", "id,created_time,from,to,message"));
        } catch (Exception e) {
            log.debug("Could not read Instagram message {}: {}", messageId, e.getMessage());
            return null;
        }
    }

    /**
     * What Instagram sent back on the last check, so the DMs page can say why it's empty: no conversations at all
     * usually means message access is off for the app on her Instagram account.
     */
    public record FetchReport(OffsetDateTime at, OffsetDateTime since, int conversations, int activeSince,
                              OffsetDateTime newestConversation, int messages) {}

    private volatile FetchReport lastFetch;

    public Optional<FetchReport> lastFetch() {
        return Optional.ofNullable(lastFetch);
    }

    @Override
    public Optional<String> sendBlockedReason(Draft draft, OffsetDateTime lastInboundAt) {
        if (draft.toAddress == null || !draft.toAddress.matches("\\d+")) {
            // A chat from her Instagram download: it has their username, not the id the API sends to.
            return Optional.of("This DM came from your Instagram download, so the app can't send it. "
                    + "Copy this draft and send it from the Instagram app.");
        }
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
