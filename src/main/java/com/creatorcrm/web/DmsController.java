package com.creatorcrm.web;

import com.creatorcrm.channels.instagram.InstagramConnector;
import com.creatorcrm.dms.DmInbox;
import com.creatorcrm.domain.Enums.Platform;
import com.creatorcrm.ingest.IngestionService;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Instagram DMs: the list she sorts, each conversation, and tagging one as a lead or not. */
@RestController
@RequestMapping("/api/dms")
public class DmsController {

    /** {@code tag}: LEAD, NOT_LEAD, or blank for "let Claude decide". */
    public record TagRequest(String tag) {}

    /**
     * Where Instagram stands, for the page's empty state: connected as whom, when it last checked, any error, and
     * what Instagram sent back last time ({@code fetch} is null until a check runs after the app starts).
     */
    public record Status(boolean connected, String account, String lastCheck, String error,
                         InstagramConnector.FetchReport fetch, String importingSince) {}

    public record ImportRequest(int days) {}

    private final DmInbox inbox;
    private final InstagramConnector instagram;
    private final IngestionService ingestion;
    private final TaskExecutor executor;

    public DmsController(DmInbox inbox, InstagramConnector instagram, IngestionService ingestion,
                         @Qualifier("applicationTaskExecutor") TaskExecutor executor) {
        this.inbox = inbox;
        this.instagram = instagram;
        this.ingestion = ingestion;
        this.executor = executor;
    }

    @GetMapping("/status")
    public Status status() {
        String key = "sync." + Platform.INSTAGRAM;
        return new Status(instagram.isConnected(), instagram.accountLabel(), ingestion.read(key).orElse(null),
                ingestion.read(key + ".error").filter(e -> !e.isBlank()).orElse(null),
                instagram.lastFetch().orElse(null), ingestion.read(key + ".import").filter(e -> !e.isBlank()).orElse(null));
    }

    /** Bring in older DMs: the next check reaches back this many days, then runs now in the background. */
    @PostMapping("/import")
    public Status importOlder(@RequestBody ImportRequest r) {
        ingestion.startImport(Platform.INSTAGRAM, r.days());
        executor.execute(ingestion::syncAll);
        return status();
    }

    @GetMapping
    public List<DmInbox.Row> list() {
        return inbox.list();
    }

    @GetMapping("/{id}")
    public DmInbox.Detail detail(@PathVariable Long id) {
        return inbox.detail(id);
    }

    @PostMapping("/{id}/tag")
    public DmInbox.Row tag(@PathVariable Long id, @RequestBody TagRequest r) {
        return inbox.tag(id, r.tag());
    }
}
