package com.creatorcrm.contacts;

import static org.assertj.core.api.Assertions.assertThat;

import com.creatorcrm.domain.Brand;
import com.creatorcrm.domain.BrandContact;
import com.creatorcrm.domain.BrandContact.Role;
import com.creatorcrm.domain.BrandLead;
import com.creatorcrm.domain.ContactSource.Kind;
import com.creatorcrm.domain.Suppression;
import com.creatorcrm.repo.BrandContactRepo;
import com.creatorcrm.repo.BrandLeadRepo;
import com.creatorcrm.repo.BrandRepo;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/** The shareable directory holds brand role inboxes only: no named people, no paid-finder data, no do-not-email. */
@SpringBootTest
@ActiveProfiles("test")
class BrandDirectoryIntegrationTest {

    @Autowired BrandDirectory directory;
    @Autowired ContactService contacts;
    @Autowired BrandRepo brands;
    @Autowired BrandContactRepo contactRepo;
    @Autowired BrandLeadRepo leads;
    @Autowired Suppressions suppressions;

    private String tag;
    private String domain;
    private Brand brand;

    @BeforeEach
    void seed() {
        tag = UUID.randomUUID().toString().substring(0, 8);
        domain = "glow" + tag + ".com";
        brand = new Brand();
        brand.name = "Glow " + tag;
        brand.nameKey = Brand.key(brand.name);
        brand.website = "https://" + domain;
        brand.instagram = "@glow" + tag;
        brand.createdAt = OffsetDateTime.now();
        brands.save(brand);

        BrandLead lead = new BrandLead();
        lead.name = brand.name;
        lead.nameKey = brand.nameKey;
        lead.searchQuery = "vegan skincare";
        lead.status = BrandLead.Status.DRAFTED;
        lead.source = BrandLead.Source.WEB;
        lead.createdAt = OffsetDateTime.now();
        leads.save(lead);
    }

    private void add(String email, String name, Kind source) {
        contacts.add(brand.id, new ContactService.Found(email, name, null, null, null, null, null, null, source, null));
    }

    private List<BrandDirectory.Row> mine() {
        return directory.build().rows().stream().filter(r -> r.brand().equals(brand.name)).toList();
    }

    @Test
    void sharesRoleInboxesOnly() {
        add("collabs@" + domain, null, Kind.WEBSITE);
        add("pr@" + domain, null, Kind.GMAIL);
        add("priya@" + domain, "Priya Shah", Kind.GMAIL);
        add("partnerships@" + domain, null, Kind.HUNTER);
        add("marketing@" + domain, null, Kind.WEBSITE);
        contacts.doNotEmail("marketing@" + domain, Suppression.Reason.OPTED_OUT);
        add("collabs" + tag + "@gmail.com", null, Kind.WEBSITE);
        add("ugc@" + domain, null, Kind.GUESS);

        List<BrandDirectory.Row> rows = mine();
        assertThat(rows).extracting(BrandDirectory.Row::inbox)
                .containsExactlyInAnyOrder("collabs@" + domain, "pr@" + domain);
        BrandDirectory.Row r = rows.stream().filter(x -> x.inbox().startsWith("collabs@")).findFirst().orElseThrow();
        assertThat(r.niche()).isEqualTo("vegan skincare");
        assertThat(r.instagram()).isEqualTo("glow" + tag);
        assertThat(r.website()).isEqualTo("https://" + domain);
        assertThat(r.inboxType()).isEqualTo("Partnerships");
        assertThat(r.lastVerified()).isNotNull();

        String csv = directory.csv();
        assertThat(csv).startsWith("\"Brand\",\"Niche\",\"Website\",\"Instagram\",\"Collab inbox\"");
        assertThat(csv).contains("collabs@" + domain).doesNotContain("priya@" + domain).doesNotContain("Priya")
                .doesNotContain("partnerships@" + domain).doesNotContain("marketing@" + domain)
                .doesNotContain("collabs" + tag + "@gmail.com").doesNotContain("ugc@" + domain);
    }

    @Test
    void guessedInboxCountsOnceSomeoneAnswered() {
        add("ugc@" + domain, null, Kind.GUESS);
        assertThat(mine()).isEmpty();
        BrandContact c = contactRepo.findByEmail("ugc@" + domain).orElseThrow();
        c.replies = 1;
        c.lastRepliedAt = OffsetDateTime.now();
        contactRepo.save(c);
        assertThat(mine()).singleElement().satisfies(r -> {
            assertThat(r.answered()).isTrue();
            assertThat(r.inboxType()).isEqualTo("Partnerships");
        });
    }

    @Test
    void wholeDomainOnDoNotEmailIsLeftOut() {
        add("collabs@" + domain, null, Kind.WEBSITE);
        assertThat(mine()).hasSize(1);
        suppressions.add(domain, Suppression.Reason.MANUAL);
        assertThat(mine()).isEmpty();
    }

    @Test
    void namedRoleInboxIsStillAPerson() {
        contacts.add(brand.id, new ContactService.Found("collabs@" + domain, "Ana", null, Role.PARTNERSHIPS, null, null,
                null, null, Kind.GMAIL, null));
        assertThat(mine()).isEmpty();
    }
}
