package com.creatorcrm.web;

import com.creatorcrm.contacts.CategorySearch;
import com.creatorcrm.contacts.WebsiteContacts;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** "Find contacts on website" and the free category search: no Claude. */
@RestController
@RequestMapping("/api")
public class WebsiteContactsController {
    public record Category(@Size(max = 100) String category, Integer count) {}

    private final WebsiteContacts website;
    private final CategorySearch categories;

    public WebsiteContactsController(WebsiteContacts website, CategorySearch categories) {
        this.website = website;
        this.categories = categories;
    }

    /** Free brand search by category (Wikidata open data); their websites are then read for emails. */
    @PostMapping("/leads/category")
    public CategorySearch.Result byCategory(@Valid @RequestBody Category c) {
        return categories.search(c.category(), c.count() == null ? 20 : c.count());
    }

    @PostMapping("/contacts/brand/{brandId}/website")
    public WebsiteContacts.Result forBrand(@PathVariable Long brandId) {
        return website.forBrand(brandId);
    }

    @PostMapping("/leads/{id}/website")
    public WebsiteContacts.Result forLead(@PathVariable Long id) {
        return website.forLead(id);
    }
}
