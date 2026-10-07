package com.creatorcrm.web;

import com.creatorcrm.contacts.WebsiteContacts;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** "Find contacts on website": reads a brand's or lead's own website for published email addresses. No Claude. */
@RestController
@RequestMapping("/api")
public class WebsiteContactsController {
    private final WebsiteContacts website;

    public WebsiteContactsController(WebsiteContacts website) {
        this.website = website;
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
