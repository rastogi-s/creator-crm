package com.creatorcrm.web;

import com.creatorcrm.progress.DealProgress;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Deal progress: the step diagram on a deal the brand said yes to, and its "it happened" button. */
@RestController
@RequestMapping("/api/opportunities/{id}")
public class ProgressController {

    public record AdvanceRequest(String to) {}

    private final DealProgress progress;

    public ProgressController(DealProgress progress) {
        this.progress = progress;
    }

    @GetMapping("/progress")
    public DealProgress.View progress(@PathVariable Long id) {
        return progress.view(id);
    }

    @PostMapping("/progress")
    public DealProgress.View advance(@PathVariable Long id, @RequestBody AdvanceRequest r) {
        return progress.advance(id, r.to());
    }
}
