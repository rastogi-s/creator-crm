package com.creatorcrm.web;

import com.creatorcrm.llm.ClaudeSpend;
import com.creatorcrm.llm.SearchDepth;
import com.creatorcrm.domain.BrandLead;
import com.creatorcrm.domain.Draft;
import com.creatorcrm.channels.instagram.FacebookConnection;
import com.creatorcrm.channels.instagram.InstagramConnector;
import com.creatorcrm.outreach.BrandDiscoveryService;
import com.creatorcrm.outreach.InstagramEngagementService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Outreach page API: find brands to pitch (web research, Instagram engagement, handle lookups) and turn them
 * into pitch drafts.
 */
@RestController
@RequestMapping("/api/leads")
public class DiscoveryController {

    public record Search(@Size(max = 300) String query, Integer count, @Size(max = 20) String depth) {}

    public record Contact(@Size(max = 320) String email, @Size(max = 200) String instagram) {}

    public record Lookup(@Size(max = 200) String handle) {}

    private final BrandDiscoveryService discovery;
    private final ClaudeSpend spend;
    private final InstagramEngagementService engagement;
    private final InstagramConnector instagram;
    private final FacebookConnection facebook;

    public DiscoveryController(BrandDiscoveryService discovery, ClaudeSpend spend, InstagramEngagementService engagement,
                               InstagramConnector instagram, FacebookConnection facebook) {
        this.discovery = discovery;
        this.spend = spend;
        this.engagement = engagement;
        this.instagram = instagram;
        this.facebook = facebook;
    }

    /** Accounts that tagged, mentioned or commented on her, and what the Outreach page can offer. */
    @GetMapping("/instagram")
    public Map<String, Object> instagram() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("instagramConnected", instagram.isConnected());
        out.put("facebookConnected", facebook.isConnected());
        out.put("error", engagement.lastError().orElse(null));
        out.put("accounts", engagement.open());
        return out;
    }

    /** Check her recent posts for new comments and tags now instead of at the next sync. */
    @PostMapping("/instagram/check")
    public Map<String, Object> checkInstagram() {
        if (!instagram.isConnected()) throw new IllegalStateException("Connect Instagram on the Settings page first");
        engagement.poll();
        return instagram();
    }

    @PostMapping("/instagram/{id}/lead")
    public BrandLead engagementToLead(@PathVariable Long id) {
        return engagement.makeLead(id);
    }

    @PostMapping("/instagram/{id}/dismiss")
    public Map<String, Boolean> dismissEngagement(@PathVariable Long id) {
        engagement.dismiss(id);
        return Map.of("dismissed", true);
    }

    /** Look up a brand's Instagram account by handle and keep it as a lead (needs the Facebook connection). */
    @PostMapping("/lookup")
    public BrandLead lookup(@Valid @RequestBody Lookup l) {
        try {
            return discovery.lookupHandle(l.handle());
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException("Couldn't reach Instagram: " + e.getMessage());
        }
    }

    @PostMapping("/{id}/instagram")
    public BrandLead refreshInstagram(@PathVariable Long id) {
        try {
            return discovery.refreshInstagram(id);
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException("Couldn't reach Instagram: " + e.getMessage());
        }
    }

    /** Quick / Standard / Thorough with their search caps and what a run costs (measured once there are runs). */
    @GetMapping("/search-options")
    public List<ClaudeSpend.DepthCost> searchOptions() {
        return spend.researchCosts();
    }

    @GetMapping
    public List<BrandLead> open() {
        return discovery.open();
    }

    /** Runs the web research synchronously; it can take a minute or two. */
    @PostMapping("/search")
    public List<BrandLead> search(@Valid @RequestBody Search s) {
        return discovery.discover(s.query(), s.count() == null ? 5 : s.count(), SearchDepth.parse(s.depth()));
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
