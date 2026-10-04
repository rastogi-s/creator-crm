package com.creatorcrm.web;

import com.creatorcrm.domain.CreatorLink;
import com.creatorcrm.links.LinkService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.util.List;
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

    private final LinkService links;

    public LinksController(LinkService links) {
        this.links = links;
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
