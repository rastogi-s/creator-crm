package com.creatorcrm.web;

import com.creatorcrm.domain.CreatorLink;
import com.creatorcrm.links.LinkService;
import com.creatorcrm.links.LinktreeImporter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Links page API: the creator's own profile, website and portfolio links. */
@RestController
@RequestMapping("/api/links")
public class LinksController {

    public record LinkInput(@Size(max = 100) String label, @Size(max = 1000) String url) {}

    public record LinktreeInput(@Size(max = 2000) String url) {}

    public record BulkInput(@Size(max = LinkService.MAX_LINKS) List<@Valid LinkInput> links) {}

    private final LinkService links;
    private final LinktreeImporter linktree;

    public LinksController(LinkService links, LinktreeImporter linktree) {
        this.links = links;
        this.linktree = linktree;
    }

    /** Read a Linktree page and return what it would add; nothing is saved yet. */
    @PostMapping("/linktree")
    public LinktreeImporter.Profile linktree(@Valid @RequestBody LinktreeInput in) {
        return linktree.fetch(in.url());
    }

    @PostMapping("/bulk")
    public LinkService.Added addAll(@Valid @RequestBody BulkInput in) {
        if (in.links() == null || in.links().isEmpty()) throw new IllegalArgumentException("Pick at least one link");
        return links.addAll(in.links().stream().map(l -> Map.entry(l.label() == null ? "" : l.label(), l.url() == null ? "" : l.url())).toList());
    }

    @GetMapping
    public List<CreatorLink> list() {
        return links.list();
    }

    @PostMapping
    public CreatorLink add(@Valid @RequestBody LinkInput in) {
        return links.add(in.label(), in.url());
    }

    @PutMapping("/{id}")
    public CreatorLink update(@PathVariable Long id, @Valid @RequestBody LinkInput in) {
        return links.update(id, in.label(), in.url());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        links.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/move")
    public List<CreatorLink> move(@PathVariable Long id, @RequestParam boolean up) {
        return links.move(id, up);
    }
}
