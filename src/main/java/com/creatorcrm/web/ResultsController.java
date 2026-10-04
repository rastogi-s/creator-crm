package com.creatorcrm.web;

import com.creatorcrm.domain.Draft;
import com.creatorcrm.results.CampaignResults;
import com.creatorcrm.results.ResultsPdf;
import com.creatorcrm.workflow.WorkflowEngine;
import com.creatorcrm.repo.OpportunityRepo;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Campaign wrap-up on a deal: the post, its numbers, the results PDF and the recap draft. */
@RestController
@RequestMapping("/api/opportunities/{id}/results")
public class ResultsController {

    public record PostLink(String url) {}

    private final CampaignResults results;
    private final OpportunityRepo opportunities;
    private final WorkflowEngine workflow;

    public ResultsController(CampaignResults results, OpportunityRepo opportunities, WorkflowEngine workflow) {
        this.results = results;
        this.opportunities = opportunities;
        this.workflow = workflow;
    }

    /** The results so far, or 204 No Content before the post is linked. */
    @GetMapping
    public ResponseEntity<CampaignResults.View> get(@PathVariable Long id) {
        return results.forDeal(id).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PostMapping("/link")
    public CampaignResults.View link(@PathVariable Long id, @RequestBody PostLink body) {
        return results.link(id, body.url());
    }

    @PostMapping("/find")
    public CampaignResults.View find(@PathVariable Long id) {
        return results.find(id);
    }

    @PutMapping("/numbers")
    public CampaignResults.View numbers(@PathVariable Long id, @RequestBody CampaignResults.Numbers body) {
        return results.saveNumbers(id, body);
    }

    @PostMapping("/fetch")
    public CampaignResults.View fetch(@PathVariable Long id) {
        return results.fetch(id);
    }

    @GetMapping("/pdf")
    public ResponseEntity<byte[]> pdf(@PathVariable Long id, @RequestParam(defaultValue = "false") boolean download) {
        String brand = opportunities.findById(id).map(workflow::brandName).orElse("Campaign");
        ContentDisposition cd = (download ? ContentDisposition.attachment() : ContentDisposition.inline())
                .filename(ResultsPdf.fileName(brand)).build();
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, cd.toString()).body(results.pdf(id));
    }

    @PostMapping("/recap")
    public Draft recap(@PathVariable Long id) {
        return results.draftRecap(id);
    }
}
