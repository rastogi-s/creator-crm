package com.creatorcrm.channels;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * A fetch that stopped early (rate limit, per-sync cap) but may still have got some messages. Ingestion stores
 * {@link #fetched()} and keeps the sync cursor where it was, so the next sync picks up the rest. When the
 * channel said how long to back off, {@link #retryAfter()} is set and ingestion skips the channel until then.
 */
public class PartialFetchException extends Exception {
    private final transient List<NormalizedMessage> fetched;
    private final Instant retryAfter;

    public PartialFetchException(String message, List<NormalizedMessage> fetched, Throwable cause) {
        this(message, fetched, cause, null);
    }

    public PartialFetchException(String message, List<NormalizedMessage> fetched, Throwable cause, Instant retryAfter) {
        super(message, cause);
        this.fetched = List.copyOf(fetched);
        this.retryAfter = retryAfter;
    }

    public List<NormalizedMessage> fetched() {
        return fetched;
    }

    public Optional<Instant> retryAfter() {
        return Optional.ofNullable(retryAfter);
    }
}
