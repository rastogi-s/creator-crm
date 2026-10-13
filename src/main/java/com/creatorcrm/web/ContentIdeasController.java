package com.creatorcrm.web;

import com.creatorcrm.ideas.ContentIdeasService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Content ideas on a deal: the newest ones, and Refresh (with her note, or a brief she pasted). */
@RestController
@RequestMapping("/api/opportunities/{id}/ideas")
public class ContentIdeasController {

    public record Refresh(String guidelines, String note) {}

    private final ContentIdeasService ideas;

    public ContentIdeasController(ContentIdeasService ideas) {
        this.ideas = ideas;
    }

    /** 204 when the deal has no ideas yet. */
    @GetMapping
    public ResponseEntity<ContentIdeasService.View> latest(@PathVariable Long id) {
        return ideas.latest(id).map(ResponseEntity::ok).orElse(ResponseEntity.noContent().build());
    }

    @PostMapping
    public ContentIdeasService.View refresh(@PathVariable Long id, @RequestBody(required = false) Refresh body) {
        if (body != null && body.guidelines() != null && body.guidelines().length() > 60_000) {
            throw new IllegalArgumentException("That brief is too long; paste the main part");
        }
        return ideas.generate(id, body == null ? null : body.guidelines(), body == null ? null : body.note());
    }
}
