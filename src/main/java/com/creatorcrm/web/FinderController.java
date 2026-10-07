package com.creatorcrm.web;

import com.creatorcrm.contacts.ContactFinder;
import com.creatorcrm.contacts.FinderCredits;
import com.creatorcrm.contacts.FinderCredits.Provider;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Contact finders: "Find more people at this brand" (Hunter, then Apollo), address checks, and the monthly credit
 * counter in Settings. Keys are saved through Settings' credentials like every other secret.
 */
@RestController
@RequestMapping("/api/finders")
public class FinderController {

    public record Limits(Integer hunter, Integer apollo) {}

    public record Status(FinderCredits.Status hunter, FinderCredits.Status apollo) {}

    private final ContactFinder finder;
    private final FinderCredits credits;

    public FinderController(ContactFinder finder, FinderCredits credits) {
        this.finder = finder;
        this.credits = credits;
    }

    @GetMapping
    public Status status() {
        return new Status(credits.status(Provider.HUNTER, finder.hunterConnected()),
                credits.status(Provider.APOLLO, finder.apolloConnected()));
    }

    /** Asks Hunter for her plan and what's left (free), then returns the counter. */
    @PostMapping("/refresh")
    public Status refresh() {
        if (finder.hunterConnected()) finder.refreshHunterAccount();
        return status();
    }

    @PutMapping("/limits")
    public Status limits(@RequestBody Limits in) {
        if (in.hunter() != null) credits.setLimit(Provider.HUNTER, in.hunter());
        if (in.apollo() != null) credits.setLimit(Provider.APOLLO, in.apollo());
        return status();
    }

    @PostMapping("/brand/{brandId}/find")
    public ContactFinder.FindResult find(@PathVariable Long brandId, @RequestParam(defaultValue = "false") boolean again) {
        return finder.findMore(brandId, again);
    }

    @PostMapping("/brand/{brandId}/check")
    public ContactFinder.BrandCheck checkBrand(@PathVariable Long brandId) {
        return finder.checkBrand(brandId);
    }

    @PostMapping("/contact/{id}/check")
    public Map<String, Object> check(@PathVariable Long id) {
        ContactFinder.CheckResult r = finder.check(id);
        return Map.of("contact", r.contact(), "message", r.message(), "credits", r.credits());
    }
}
