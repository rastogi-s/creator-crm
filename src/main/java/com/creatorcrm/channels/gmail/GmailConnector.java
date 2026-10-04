package com.creatorcrm.channels.gmail;

import com.creatorcrm.channels.ChannelConnector;
import com.creatorcrm.channels.NormalizedMessage;
import com.creatorcrm.channels.PartialFetchException;
import com.creatorcrm.config.CrmProperties;
import com.creatorcrm.domain.Draft;
import com.creatorcrm.domain.Enums.Direction;
import com.creatorcrm.domain.Enums.Platform;
import com.creatorcrm.security.SecretName;
import com.creatorcrm.security.SecretStore;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.client.http.HttpBackOffIOExceptionHandler;
import com.google.api.client.http.HttpBackOffUnsuccessfulResponseHandler;
import com.google.api.client.http.HttpRequestInitializer;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.client.util.ExponentialBackOff;
import com.google.api.services.gmail.Gmail;
import com.google.api.services.gmail.model.ListMessagesResponse;
import com.google.api.services.gmail.model.Message;
import com.google.api.services.gmail.model.MessagePart;
import com.google.api.services.gmail.model.MessagePartHeader;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.UserCredentials;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Gmail via the official API. Scopes are read-only + compose: the app can read mail and create/send
 * drafts, but cannot delete, archive or modify existing mail. The same Google sign-in also covers
 * {@link #CALENDAR_SCOPE}, which only reaches calendars the app creates itself.
 */
@Component
public class GmailConnector implements ChannelConnector {

    /** Create a calendar and manage the events on it, without access to any of her other calendars. */
    public static final String CALENDAR_SCOPE = "https://www.googleapis.com/auth/calendar.app.created";

    public static final List<String> SCOPES = List.of(
            "https://www.googleapis.com/auth/gmail.readonly",
            "https://www.googleapis.com/auth/gmail.compose",
            CALENDAR_SCOPE);

    public static final JsonFactory JSON = GsonFactory.getDefaultInstance();

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

    public static HttpTransport transport() throws Exception {
        return GoogleNetHttpTransport.newTrustedTransport();
    }

    /** The connected Google account's credentials, shared with the calendar. */
    public UserCredentials credentials() {
        return UserCredentials.newBuilder()
                .setClientId(secrets.require(SecretName.GOOGLE_CLIENT_ID))
                .setClientSecret(secrets.require(SecretName.GOOGLE_CLIENT_SECRET))
                .setRefreshToken(secrets.require(SecretName.GMAIL_REFRESH_TOKEN))
                .build();
    }

    Gmail gmail() throws Exception {
        return new Gmail.Builder(transport(), JSON, withRetries(new HttpCredentialsAdapter(credentials())))
                .setApplicationName("creator-crm")
                .build();
    }

    /** Keeps the token-refresh handling and adds exponential backoff on 429 (rate limit), 5xx and network errors. */
    public static HttpRequestInitializer withRetries(HttpCredentialsAdapter auth) {
        return request -> {
            auth.initialize(request);
            HttpBackOffUnsuccessfulResponseHandler backoff = new HttpBackOffUnsuccessfulResponseHandler(backOff())
                    .setBackOffRequired(r -> r.getStatusCode() == 429 || r.getStatusCode() / 100 == 5);
            request.setUnsuccessfulResponseHandler((req, res, retry) ->
                    auth.handleResponse(req, res, retry) || backoff.handleResponse(req, res, retry));
            request.setIOExceptionHandler(new HttpBackOffIOExceptionHandler(backOff()));
        };
    }

    private static ExponentialBackOff backOff() {
        return new ExponentialBackOff.Builder()
                .setInitialIntervalMillis(1_000)
                .setMaxIntervalMillis(30_000)
                .setMaxElapsedTimeMillis(60_000)
                .build();
    }

    @Override
    public List<NormalizedMessage> fetchSince(OffsetDateTime since) throws Exception {
        return fetchSince(since, id -> false);
    }

    @Override
    public List<NormalizedMessage> fetchSince(OffsetDateTime since, Predicate<String> known) throws Exception {
        List<NormalizedMessage> out = new ArrayList<>();
        try {
            fetchNew(since, known, out);
            return out;
        } catch (PartialFetchException e) {
            throw e;
        } catch (Exception e) {
            Optional<Instant> retryAt = rateLimitRetryAt(e, Instant.now());
            if (retryAt.isPresent()) {
                throw new PartialFetchException("Gmail rate limit (" + brief(e) + ")", out, e, retryAt.get());
            }
            if (!out.isEmpty()) throw new PartialFetchException("Stopped after " + out.size() + " messages: " + brief(e), out, e);
            throw e instanceof GoogleJsonResponseException ? new IOException(brief(e), e) : e;
        }
    }

    private static final Pattern RETRY_AFTER = Pattern.compile("(?i)retry after (\\d{4}-\\d{2}-\\d{2}T[0-9:.]+Z)");
    private static final Set<String> RATE_LIMIT_REASONS = Set.of("rateLimitExceeded", "userRateLimitExceeded");

    /**
     * If Gmail rate-limited us, when to try again: the "Retry after" time Gmail gives, or a few minutes from now.
     * Retrying sooner only extends the block.
     */
    static Optional<Instant> rateLimitRetryAt(Exception e, Instant now) {
        if (!(e instanceof GoogleJsonResponseException g)) return Optional.empty();
        boolean limited = g.getStatusCode() == 429 || (g.getStatusCode() == 403 && g.getDetails() != null
                && g.getDetails().getErrors() != null
                && g.getDetails().getErrors().stream().anyMatch(x -> RATE_LIMIT_REASONS.contains(x.getReason())));
        if (!limited) return Optional.empty();
        String msg = g.getDetails() != null && g.getDetails().getMessage() != null ? g.getDetails().getMessage() : "";
        Matcher m = RETRY_AFTER.matcher(msg);
        if (m.find()) {
            try {
                Instant at = Instant.parse(m.group(1));
                if (at.isAfter(now)) return Optional.of(at.plusSeconds(30));
            } catch (DateTimeParseException ignored) {
                // fall through to the default pause
            }
        }
        return Optional.of(now.plus(DEFAULT_PAUSE));
    }

    private static final Duration DEFAULT_PAUSE = Duration.ofMinutes(15);

    /** "429: User-rate limit exceeded..." instead of the full HTTP dump Google puts in getMessage(). */
    static String brief(Exception e) {
        if (e instanceof GoogleJsonResponseException g && g.getDetails() != null && g.getDetails().getMessage() != null) {
            return g.getStatusCode() + ": " + g.getDetails().getMessage();
        }
        String m = String.valueOf(e.getMessage());
        return m.lines().findFirst().orElse(m);
    }

    /** Gmail allows ~50 message downloads/second per account, shared by every app on it; stay well under. */
    private static final long MIN_MILLIS_BETWEEN_DOWNLOADS = 150;

    private void fetchNew(OffsetDateTime since, Predicate<String> known, List<NormalizedMessage> out) throws Exception {
        Gmail gmail = gmail();
        String me = gmail.users().getProfile("me").execute().getEmailAddress().toLowerCase();
        long after = since.toEpochSecond();
        int cap = config.maxMessagesPerSync();

        // Listing is cheap; downloading is not. Only download ids that aren't stored yet.
        Set<String> ids = new LinkedHashSet<>();
        boolean more = false;
        for (String q : List.of(config.inboxQuery() + " after:" + after, "in:sent after:" + after)) {
            String page = null;
            do {
                ListMessagesResponse r = gmail.users().messages().list("me").setQ(q).setPageToken(page)
                        .setMaxResults(100L).execute();
                if (r.getMessages() != null) {
                    r.getMessages().stream().map(m -> m.getId()).filter(id -> !known.test(id)).forEach(ids::add);
                }
                page = r.getNextPageToken();
            } while (page != null && ids.size() < cap);
            more |= page != null;
        }
        more |= ids.size() > cap;

        for (String id : ids.stream().limit(cap).toList()) {
            if (!out.isEmpty()) Thread.sleep(MIN_MILLIS_BETWEEN_DOWNLOADS);
            out.add(parse(gmail.users().messages().get("me", id).setFormat("full").execute(), me));
        }
        if (more) throw new PartialFetchException("More than " + cap + " new messages; continuing next sync", out, null);
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

    /** A file attached to an email. */
    public record FileAttachment(String fileName, byte[] data) {}

    /**
     * The PDF attachments of one message, for the contract check: at most {@code max} files, each at most
     * {@code maxBytes}. Needs only the read-only scope the app already has.
     */
    public List<FileAttachment> pdfAttachments(String messageId, int max, long maxBytes) throws Exception {
        Gmail gmail = gmail();
        Message m = gmail.users().messages().get("me", messageId).setFormat("full").execute();
        List<MessagePart> parts = new ArrayList<>();
        collectParts(m.getPayload(), parts);
        List<FileAttachment> out = new ArrayList<>();
        for (MessagePart p : parts) {
            if (out.size() >= max) break;
            String name = p.getFilename() == null ? "" : p.getFilename();
            boolean pdf = "application/pdf".equalsIgnoreCase(p.getMimeType()) || name.toLowerCase().endsWith(".pdf");
            if (!pdf || p.getBody() == null) continue;
            Integer size = p.getBody().getSize();
            if (size != null && size > maxBytes) continue;
            byte[] data = p.getBody().getAttachmentId() != null
                    ? gmail.users().messages().attachments().get("me", messageId, p.getBody().getAttachmentId()).execute().decodeData()
                    : p.getBody().decodeData();
            if (data != null && data.length > 0 && data.length <= maxBytes) out.add(new FileAttachment(name.isBlank() ? "contract.pdf" : name, data));
        }
        return out;
    }

    private static void collectParts(MessagePart part, List<MessagePart> out) {
        if (part == null) return;
        out.add(part);
        if (part.getParts() != null) part.getParts().forEach(p -> collectParts(p, out));
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

    /** A new plain-text email from the connected account (used for error reports), outside any CRM thread. */
    public void sendPlain(String to, String subject, String body) throws Exception {
        Draft d = new Draft();
        d.toAddress = to;
        d.subject = subject;
        d.body = body;
        gmail().users().messages().send("me", new Message().setRaw(MailText.rawMessage(d))).execute();
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
