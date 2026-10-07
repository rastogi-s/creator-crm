package com.creatorcrm.contacts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.creatorcrm.domain.Brand;
import com.creatorcrm.domain.BrandContact;
import com.creatorcrm.domain.BrandLead;
import com.creatorcrm.domain.ContactSource;
import com.creatorcrm.domain.Suppression;
import com.creatorcrm.repo.BrandContactRepo;
import com.creatorcrm.repo.BrandDomainRepo;
import com.creatorcrm.repo.BrandLeadRepo;
import com.creatorcrm.repo.BrandRepo;
import com.creatorcrm.repo.ContactSourceRepo;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;

/** Website contacts go through the contacts database: cleaned, merged, marked as found on the website. */
@SpringBootTest
@ActiveProfiles("test")
class WebsiteContactsIntegrationTest {

    @TestConfiguration
    static class Config {
        @Bean
        @Primary
        WebsiteReaderTest.FakeSite fakeSite() {
            return new WebsiteReaderTest.FakeSite();
        }
    }

    @Autowired WebsiteReaderTest.FakeSite site;
    @Autowired WebsiteContacts website;
    @Autowired ContactService contacts;
    @Autowired BrandRepo brands;
    @Autowired BrandLeadRepo leads;
    @Autowired BrandContactRepo contactRepo;
    @Autowired ContactSourceRepo sources;
    @Autowired BrandDomainRepo domains;

    private Brand brand(String name, String web) {
        Brand b = new Brand();
        b.name = name;
        b.nameKey = Brand.key(name);
        b.website = web;
        b.createdAt = OffsetDateTime.now();
        return brands.save(b);
    }

    private void serve(String domain) {
        site.page("https://" + domain + "/", "<a href='/pages/creators'>Creators</a><p>hello@" + domain + "</p>")
                .page("https://" + domain + "/pages/creators", "<a href='mailto:collabs@" + domain + "'>Write to us</a>"
                        + " <a href='mailto:gone@" + domain + "'>x</a>");
    }

    @Test
    void savesEveryAddressWithTheirPageAndPicksTheBestAsMainContact() {
        String t = UUID.randomUUID().toString().substring(0, 6);
        String domain = "glow" + t + ".example";
        serve(domain);
        Brand b = brand("Glow " + t, "https://" + domain);
        contacts.doNotEmail("gone@" + domain, Suppression.Reason.FORGET_ME);

        WebsiteContacts.Result r = website.forBrand(b.id);

        assertThat(r.added()).isEqualTo(2);
        assertThat(r.addresses()).extracting(WebsiteContacts.Address::email)
                .containsExactly("hello@" + domain, "collabs@" + domain);
        assertThat(r.message()).contains("Found 2 email addresses");
        BrandContact collabs = contactRepo.findByEmail("collabs@" + domain).orElseThrow();
        assertThat(collabs.brandId).isEqualTo(b.id);
        assertThat(collabs.role).isEqualTo(BrandContact.Role.PARTNERSHIPS);
        assertThat(sources.findByContactIdOrderByFoundAtAsc(collabs.id)).singleElement().satisfies(s -> {
            assertThat(s.source).isEqualTo(ContactSource.Kind.WEBSITE);
            assertThat(s.sourceUrl).isEqualTo("https://" + domain + "/pages/creators");
        });
        assertThat(contactRepo.findByEmail("gone@" + domain)).isEmpty(); // asked to be forgotten
        assertThat(brands.findById(b.id).orElseThrow().contactEmail).isEqualTo("collabs@" + domain);
        assertThat(domains.findByDomain(domain).orElseThrow().crawledAt).isNotNull();
        assertThat(website.brandsToRead(OffsetDateTime.now().minusDays(60))).doesNotContain(b.id);

        // A second visit adds nothing new and keeps one source row per page
        WebsiteContacts.Result again = website.forBrand(b.id);
        assertThat(again.added()).isZero();
        assertThat(again.message()).contains("already had them all");
        assertThat(sources.findByContactIdOrderByFoundAtAsc(collabs.id)).hasSize(1);
    }

    @Test
    void categorySearchAddsNewBrandsOnly() {
        String t = UUID.randomUUID().toString().substring(0, 6);
        brand("Known " + t, "https://known" + t + ".example");
        CategorySearch search = new CategorySearch(site, website, leads, brands, domains) {
            @Override
            protected String get(java.net.URI uri) {
                if (uri.getHost().equals("www.wikidata.org")) return "{\"search\":[{\"id\":\"Q2095\",\"label\":\"snack food\"}]}";
                return "{\"results\":{\"bindings\":[" + row("Q1", "Known " + t, "https://known" + t + ".example")
                        + "," + row("Q2", "Peak " + t, "https://peak" + t + ".example")
                        + "," + row("Q3", "Insta " + t, "https://www.instagram.com/insta" + t) + "]}}";
            }
        };
        CategorySearch.Result r = search.search("  snack   food ", 10);
        assertThat(r.added()).extracting(l -> l.name).containsExactly("Peak " + t);
        BrandLead lead = r.added().get(0);
        assertThat(lead.source).isEqualTo(BrandLead.Source.CATEGORY);
        assertThat(lead.searchQuery).isEqualTo("snack food");
        assertThat(lead.website).isEqualTo("https://peak" + t + ".example");
        assertThat(r.message()).contains("1 new brand");
        assertThat(search.search("snack food", 10).added()).isEmpty(); // already a suggestion
        assertThatThrownBy(() -> search.search("ab", 10)).hasMessageContaining("Type a category");
    }

    private static String row(String id, String name, String site) {
        return "{\"b\":{\"value\":\"e/" + id + "\"},\"bLabel\":{\"value\":\"" + name + "\"},\"site\":{\"value\":\"" + site
                + "\"},\"catLabel\":{\"value\":\"snack food\"}}";
    }

    @Test
    void nightlyPicksBrandsAndLeadsWithoutContacts() {
        String t = UUID.randomUUID().toString().substring(0, 6);
        Brand none = brand("Bare " + t, "bare" + t + ".example");
        Brand noSite = brand("Nosite " + t, null);
        assertThat(website.brandsToRead(OffsetDateTime.now().minusDays(60))).contains(none.id).doesNotContain(noSite.id);
        assertThatThrownBy(() -> website.forBrand(noSite.id)).hasMessageContaining("website first");

        BrandLead lead = new BrandLead();
        lead.name = "Lead " + t;
        lead.nameKey = Brand.key(lead.name);
        lead.website = "https://lead" + t + ".example";
        lead.status = BrandLead.Status.NEW;
        lead.searchQuery = "test";
        lead.createdAt = OffsetDateTime.now();
        lead = leads.save(lead);
        assertThat(website.leadsToRead(OffsetDateTime.now().minusDays(60))).extracting(l -> l.id).contains(lead.id);

        serve("lead" + t + ".example");
        WebsiteContacts.Result r = website.forLead(lead.id);
        BrandLead after = leads.findById(lead.id).orElseThrow();
        assertThat(after.contactEmail).isEqualTo("collabs@lead" + t + ".example"); // partnerships beats hello@
        assertThat(after.contactSourceUrl).isEqualTo("https://lead" + t + ".example/pages/creators");
        assertThat(after.websiteCheckedAt).isNotNull();
        assertThat(r.message()).contains("Using collabs@");
        assertThat(website.leadsToRead(OffsetDateTime.now().minusDays(60))).extracting(l -> l.id).doesNotContain(lead.id);
    }
}
