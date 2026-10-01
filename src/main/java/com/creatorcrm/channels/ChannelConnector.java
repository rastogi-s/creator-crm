package com.creatorcrm.channels;

import com.creatorcrm.domain.Draft;
import com.creatorcrm.domain.Enums.Platform;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * A messaging channel (Gmail, Instagram, ...). Add a new channel by implementing this interface as a
 * Spring bean; ingestion, drafts and the dashboard pick it up automatically.
 */
public interface ChannelConnector {

    Platform platform();

    /** Credentials present and usable. */
    boolean isConnected();

    /** Human-readable account label for the Settings page, e.g. the email address or @handle. */
    String accountLabel();

    /** New and sent messages since the given time (polling). Implementations may cap the count. */
    List<NormalizedMessage> fetchSince(OffsetDateTime since) throws Exception;

    /**
     * Why this draft cannot be sent through the API right now (e.g. Instagram's 24-hour window), or empty
     * if it can. The dashboard then offers copy-to-clipboard instead.
     */
    default Optional<String> sendBlockedReason(Draft draft, OffsetDateTime lastInboundAt) {
        return Optional.empty();
    }

    /** Optionally mirror the draft into the channel (e.g. Gmail Drafts) for review there. Returns its id. */
    default Optional<String> pushDraft(Draft draft) throws Exception {
        return Optional.empty();
    }

    /** Actually send. Only called after the creator approved this specific draft. */
    SentMessage send(Draft draft) throws Exception;

    record SentMessage(String externalId, String threadKey) {}
}
