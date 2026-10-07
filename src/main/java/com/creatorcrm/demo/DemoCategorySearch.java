package com.creatorcrm.demo;

import com.creatorcrm.contacts.CategorySearch;
import com.creatorcrm.contacts.WebsiteContacts;
import com.creatorcrm.contacts.WebsiteReader;
import com.creatorcrm.repo.BrandDomainRepo;
import com.creatorcrm.repo.BrandLeadRepo;
import com.creatorcrm.repo.BrandRepo;
import java.net.URI;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Demo mode only: made-up Wikidata answers, so the category search works without the internet. */
@Component
@Primary
@Profile("demo")
public class DemoCategorySearch extends CategorySearch {

    public DemoCategorySearch(WebsiteReader reader, WebsiteContacts website, BrandLeadRepo leads, BrandRepo brands, BrandDomainRepo domains) {
        super(reader, website, leads, brands, domains);
    }

    @Override
    protected String get(URI uri) {
        if (uri.getHost().equals("www.wikidata.org")) {
            return "{\"search\":[{\"id\":\"Q2095\",\"label\":\"snack food\"}]}";
        }
        return "{\"results\":{\"bindings\":["
                + row("Q1", "Peak Provisions", "https://peakprovisions.example", "peakprovisions")
                + "," + row("Q2", "Summit Bars", "https://summitbars.example", null)
                + "]}}";
    }

    private static String row(String id, String name, String site, String ig) {
        return "{\"b\":{\"value\":\"http://www.wikidata.org/entity/" + id + "\"},\"bLabel\":{\"value\":\"" + name + "\"},"
                + "\"site\":{\"value\":\"" + site + "\"},\"catLabel\":{\"value\":\"snack food\"}"
                + (ig == null ? "" : ",\"ig\":{\"value\":\"" + ig + "\"}") + "}";
    }
}
