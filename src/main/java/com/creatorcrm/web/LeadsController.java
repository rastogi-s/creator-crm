package com.creatorcrm.web;

import com.creatorcrm.scoring.BatchDecline;
import java.util.List;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Batch decline: polite no-thanks drafts for the leads she picks, and sending them all after she's read them. */
@RestController
@RequestMapping("/api")
public class LeadsController {

    public record DeclineRequest(List<Long> ids) {}

    private final BatchDecline batch;

    public LeadsController(BatchDecline batch) {
        this.batch = batch;
    }

    @PostMapping("/opportunities/decline")
    public BatchDecline.Result decline(@RequestBody DeclineRequest r) {
        return batch.draftDeclines(r.ids());
    }

    @PostMapping("/drafts/send-declines")
    public BatchDecline.Result sendDeclines() {
        return batch.sendDeclines();
    }
}
