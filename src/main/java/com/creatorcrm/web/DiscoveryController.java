package com.creatorcrm.web;

import com.creatorcrm.domain.BrandLead;
import com.creatorcrm.domain.Draft;
import com.creatorcrm.outreach.BrandDiscoveryService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Outreach page API: find brands to pitch and turn them into pitch drafts. */
@RestController
@RequestMapping("/api/leads")
public class DiscoveryController {

    public record Search(@Size(max = 300) String query, Integer count) {}

    public record Contact(@Size(max = 320) String email, @Size(max = 200) String instagram) {}

    private final BrandDiscoveryService discovery;

    public DiscoveryController(BrandDiscoveryService discovery) {
        this.discovery = discovery;
    }

    @GetMapping
    public List<BrandLead> open() {
        return discovery.open();
    }

    /** Runs the web research synchronously; it can take a minute or two. */
    @PostMapping("/search")
    public List<BrandLead> search(@Valid @RequestBody Search s) {
        return discovery.discover(s.query(), s.count() == null ? 5 : s.count());
    }

    @PutMapping("/{id}/contact")
    public BrandLead contact(@PathVariable Long id, @Valid @RequestBody Contact c) {
        return discovery.updateContact(id, c.email(), c.instagram());
    }

    @PostMapping("/{id}/dismiss")
    public BrandLead dismiss(@PathVariable Long id) {
        return discovery.dismiss(id);
    }

    @PostMapping("/{id}/pitch")
    public Draft pitch(@PathVariable Long id) {
        return discovery.draftPitch(id);
    }
}
