package com.creatorcrm.web;

import com.creatorcrm.dms.DmInbox;
import java.util.List;
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

    private final DmInbox inbox;

    public DmsController(DmInbox inbox) {
        this.inbox = inbox;
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
