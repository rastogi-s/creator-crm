package com.creatorcrm.channels.gmail;

import com.creatorcrm.channels.ChannelConnector;
import com.creatorcrm.channels.NormalizedMessage;
import com.creatorcrm.config.CrmProperties;
import com.creatorcrm.domain.Draft;
import com.creatorcrm.domain.Enums.Direction;
import com.creatorcrm.domain.Enums.Platform;
import com.creatorcrm.security.SecretName;
import com.creatorcrm.security.SecretStore;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.gmail.Gmail;
import com.google.api.services.gmail.model.ListMessagesResponse;
import com.google.api.services.gmail.model.Message;
import com.google.api.services.gmail.model.MessagePart;
import com.google.api.services.gmail.model.MessagePartHeader;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.UserCredentials;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import org.springframework.stereotype.Component;

/**
 * Gmail via the official API. Scopes are read-only + compose: the app can read mail and create/send
 * drafts, but cannot delete, archive or modify existing mail.
 */
@Component
public class GmailConnector implements ChannelConnector {

    public static final List<String> SCOPES = List.of(
            "https://www.googleapis.com/auth/gmail.readonly",
            "https://www.googleapis.com/auth/gmail.compose");

    static final JsonFactory JSON = GsonFactory.getDefaultInstance();

    private final SecretStore secrets;
    private final CrmProperties.Gmail config;

    public GmailConnector(SecretStore secrets, CrmProperties props) {
        this.secrets = secrets;
        this.config = props.gmail();
    }

    @Override
    public Platform platform() {
        return Platform.EMAIL;
    }

    @Override
    public boolean isConnected() {
        return secrets.has(SecretName.GOOGLE_CLIENT_ID) && secrets.has(SecretName.GOOGLE_CLIENT_SECRET)
                && secrets.has(SecretName.GMAIL_REFRESH_TOKEN);
    }

    @Override
    public String accountLabel() {
        return secrets.get(SecretName.GMAIL_ADDRESS).orElse("");
    }

    static HttpTransport transport() throws Exception {
        return GoogleNetHttpTransport.newTrustedTransport();
    }

    Gmail gmail() throws Exception {
        UserCredentials creds = UserCredentials.newBuilder()
                .setClientId(secrets.require(SecretName.GOOGLE_CLIENT_ID))
                .setClientSecret(secrets.require(SecretName.GOOGLE_CLIENT_SECRET))
                .setRefreshToken(secrets.require(SecretName.GMAIL_REFRESH_TOKEN))
                .build();
        return new Gmail.Builder(transport(), JSON, new HttpCredentialsAdapter(creds))
                .setApplicationName("creator-crm")
                .build();
    }

    @Override
    public List<NormalizedMessage> fetchSince(OffsetDateTime since) throws Exception {
        Gmail gmail = gmail();
        String me = gmail.users().getProfile("me").execute().getEmailAddress().toLowerCase();
        long after = since.toEpochSecond();

        Set<String> ids = new LinkedHashSet<>();
        for (String q : List.of(config.inboxQuery() + " after:" + after, "in:sent after:" + after)) {
            String page = null;
            do {
                ListMessagesResponse r = gmail.users().messages().list("me").setQ(q).setPageToken(page)
                        .setMaxResults(100L).execute();
                if (r.getMessages() != null) r.getMessages().forEach(m -> ids.add(m.getId()));
                page = r.getNextPageToken();
            } while (page != null && ids.size() < config.maxMessagesPerSync());
        }

        List<NormalizedMessage> out = new ArrayList<>();
        for (String id : ids.stream().limit(config.maxMessagesPerSync()).toList()) {
            out.add(parse(gmail.users().messages().get("me", id).setFormat("full").execute(), me));
        }
        return out;
    }

    static NormalizedMessage parse(Message m, String me) {
        Map<String, String> h = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        if (m.getPayload() != null && m.getPayload().getHeaders() != null) {
            for (MessagePartHeader header : m.getPayload().getHeaders()) h.putIfAbsent(header.getName(), header.getValue());
        }
        Address from = Address.parse(h.getOrDefault("From", ""));
        boolean outbound = from.email().equalsIgnoreCase(me)
                || (m.getLabelIds() != null && m.getLabelIds().contains("SENT"));
        String to = h.getOrDefault("To", "");
        Address replyTo = Address.parse(h.getOrDefault("Reply-To", ""));
        String counterparty = outbound ? Address.parse(to).email()
                : (replyTo.email().isBlank() ? from.email() : replyTo.email());
        boolean bulk = h.containsKey("List-Unsubscribe") || h.getOrDefault("Precedence", "").matches("(?i)bulk|list|junk");

        return new NormalizedMessage(
                Platform.EMAIL,
                m.getId(),
                m.getThreadId(),
                outbound ? Direction.OUTBOUND : Direction.INBOUND,
                from.email(),
                from.name(),
                to,
                counterparty,
                h.getOrDefault("Subject", ""),
                MailText.bodyOf(m.getPayload()),
                h.getOrDefault("Message-ID", h.getOrDefault("Message-Id", "")),
                replyTo.email(),
                OffsetDateTime.ofInstant(Instant.ofEpochMilli(m.getInternalDate()), ZoneId.systemDefault()),
                bulk);
    }

    @Override
    public Optional<String> pushDraft(Draft draft) throws Exception {
        if (!config.pushDraftsToGmail()) return Optional.empty();
        com.google.api.services.gmail.model.Draft d = new com.google.api.services.gmail.model.Draft()
                .setMessage(new Message().setRaw(MailText.rawMessage(draft)).setThreadId(draft.gmailThreadId));
        return Optional.of(gmail().users().drafts().create("me", d).execute().getId());
    }

    @Override
    public SentMessage send(Draft draft) throws Exception {
        Gmail gmail = gmail();
        Message sent;
        if (draft.gmailDraftId != null && !draft.gmailDraftId.isBlank()) {
            // Update the Gmail draft with the (possibly edited) text, then send it.
            com.google.api.services.gmail.model.Draft d = new com.google.api.services.gmail.model.Draft()
                    .setId(draft.gmailDraftId)
                    .setMessage(new Message().setRaw(MailText.rawMessage(draft)).setThreadId(draft.gmailThreadId));
            gmail.users().drafts().update("me", draft.gmailDraftId, d).execute();
            sent = gmail.users().drafts().send("me", d).execute();
        } else {
            sent = gmail.users().messages().send("me",
                    new Message().setRaw(MailText.rawMessage(draft)).setThreadId(draft.gmailThreadId)).execute();
        }
        return new SentMessage(sent.getId(), sent.getThreadId());
    }

    /** Minimal "Name &lt;email&gt;" parser. */
    record Address(String name, String email) {
        static Address parse(String raw) {
            if (raw == null || raw.isBlank()) return new Address("", "");
            String first = raw.split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)")[0].trim();
            int lt = first.lastIndexOf('<');
            int gt = first.lastIndexOf('>');
            if (lt >= 0 && gt > lt) {
                return new Address(first.substring(0, lt).replace("\"", "").trim(), first.substring(lt + 1, gt).trim());
            }
            return new Address("", first);
        }
    }

    static String decode(MessagePart part) {
        return part.getBody() == null || part.getBody().getData() == null ? ""
                : new String(part.getBody().decodeData(), StandardCharsets.UTF_8);
    }
}
