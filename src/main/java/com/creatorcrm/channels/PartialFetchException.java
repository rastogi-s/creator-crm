package com.creatorcrm.channels;

import java.util.List;

/**
 * A fetch that stopped early (rate limit, per-sync cap) but still got some messages. Ingestion stores
 * {@link #fetched()} and keeps the sync cursor where it was, so the next sync picks up the rest.
 */
public class PartialFetchException extends Exception {
    private final transient List<NormalizedMessage> fetched;

    public PartialFetchException(String message, List<NormalizedMessage> fetched, Throwable cause) {
        super(message, cause);
        this.fetched = List.copyOf(fetched);
    }

    public List<NormalizedMessage> fetched() {
        return fetched;
    }
}
