package com.creatorcrm.outreach;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.creatorcrm.FakeLlm;
import com.creatorcrm.domain.Brand;
import com.creatorcrm.domain.BrandLead;
import com.creatorcrm.domain.Draft;
import com.creatorcrm.domain.Enums.DraftType;
import com.creatorcrm.domain.Enums.FollowUpStatus;
import com.creatorcrm.domain.Enums.OpportunityStatus;
import com.creatorcrm.domain.Enums.Origin;
import com.creatorcrm.domain.Enums.Platform;
import com.creatorcrm.domain.Opportunity;
import com.creatorcrm.drafts.DraftService;
import com.creatorcrm.llm.BrandLeads;
import com.creatorcrm.repo.BrandLeadRepo;
import com.creatorcrm.repo.BrandRepo;
import com.creatorcrm.repo.FollowUpRepo;
import com.creatorcrm.repo.OpportunityRepo;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;

/** Web-researched brand leads: sanitized, de-duplicated, and turned into pitch drafts that follow the normal flow. */
@SpringBootTest
@ActiveProfiles("test")
class BrandDiscoveryIntegrationTest {

    @TestConfiguration
    static class Config {
        @Bean
        @Primary
        FakeLlm fakeLlm() {
            return new FakeLlm();
        }
    }

    @Autowired FakeLlm llm;
    @Autowired BrandDiscoveryService discovery;
    @Autowired BrandLeadRepo leads;
    @Autowired BrandRepo brands;
    @Autowired OpportunityRepo opportunities;
    @Autowired FollowUpRepo followUps;
    @Autowired DraftService drafts;

    private static String tag() {
        return UUID.randomUUID().toString().substring(0, 6);
    }

    private static BrandLeads.Lead lead(String name, String website, String ig, String email, String source) {
        return new BrandLeads.Lead(name, website, ig, email, source, "Works with UGC creators", "Morning routine reel");
    }

    @Test
    void leadsAreSanitizedAndDeduplicated() {
        String t = tag();
        Brand existing = new Brand();
        existing.name = "Known " + t;
        existing.nameKey = Brand.key(existing.name);
        existing.createdAt = OffsetDateTime.now();
        brands.save(existing);
        llm.nextLeads = new BrandLeads(List.of(
                lead("Glow " + t, "glow.example", "@glow" + t, "Collabs@Glow.example", "https://glow.example/contact"),
                lead("Fern " + t, "javascript:alert(1)", "https://www.instagram.com/fern_" + t + "/", "info at fern", "https://x"),
                lead("Known " + t, "https://known.example", "", "", ""),
                lead("Glow " + t, "https://dupe.example", "", "", "")));

        List<BrandLead> added = discovery.discover("clean skincare brands", 5);

        assertThat(added).extracting(l -> l.name).containsExactly("Glow " + t, "Fern " + t);
        BrandLead glow = added.get(0);
        assertThat(glow.website).isEqualTo("https://glow.example");
        assertThat(glow.instagram).isEqualTo("glow" + t);
        assertThat(glow.contactEmail).isEqualTo("collabs@glow.example");
        assertThat(glow.contactSourceUrl).isEqualTo("https://glow.example/contact");
        BrandLead fern = added.get(1);
        assertThat(fern.website).isNull();
        assertThat(fern.instagram).isEqualTo("fern_" + t);
        assertThat(fern.contactEmail).isNull();
        assertThat(fern.contactSourceUrl).isNull();

        // Searching again doesn't bring back brands already suggested.
        assertThat(discovery.discover("clean skincare brands", 5)).isEmpty();
    }

    @Test
    void aLeadBecomesAPitchDraftThatStartsFollowUpsWhenSent() {
        String t = tag();
        llm.nextLeads = new BrandLeads(List.of(lead("Sol " + t, "https://sol.example", "", "pr@sol.example", "https://sol.example/press")));
        BrandLead lead = discovery.discover("sunscreen brands", 3).getFirst();

        Draft d = discovery.draftPitch(lead.id);

        assertThat(d.type).isEqualTo(DraftType.PITCH);
        assertThat(d.channel).isEqualTo(Platform.EMAIL);
        assertThat(d.toAddress).isEqualTo("pr@sol.example");
        Opportunity o = opportunities.findById(d.opportunityId).orElseThrow();
        assertThat(o.origin).isEqualTo(Origin.PITCH);
        assertThat(o.status).isEqualTo(OpportunityStatus.NEW_LEAD);
        assertThat(leads.findById(lead.id).orElseThrow().status).isEqualTo(BrandLead.Status.DRAFTED);
        assertThat(discovery.open()).extracting(l -> l.id).doesNotContain(lead.id);

        drafts.markSentManually(d.id);

        o = opportunities.findById(o.id).orElseThrow();
        assertThat(o.status).isEqualTo(OpportunityStatus.PITCHED);
        assertThat(o.pitchedAt).isNotNull();
        assertThat(followUps.findByOpportunityIdOrderByNumberAsc(o.id)).extracting(f -> f.status)
                .containsExactly(FollowUpStatus.SCHEDULED);
    }

    @Test
    void aLeadWithoutContactNeedsOneBeforePitching() {
        String t = tag();
        llm.nextLeads = new BrandLeads(List.of(lead("Moss " + t, "https://moss.example", "", "", "")));
        BrandLead lead = discovery.discover("plant brands", 3).getFirst();

        assertThatThrownBy(() -> discovery.draftPitch(lead.id)).hasMessageContaining("Add one first");
        assertThatThrownBy(() -> discovery.updateContact(lead.id, "not-an-email", null))
                .isInstanceOf(IllegalArgumentException.class);

        discovery.updateContact(lead.id, "hello@moss.example", null);
        assertThat(discovery.draftPitch(lead.id).toAddress).isEqualTo("hello@moss.example");
    }

    @Test
    void dismissedLeadsLeaveTheList() {
        String t = tag();
        llm.nextLeads = new BrandLeads(List.of(lead("Dune " + t, "", "dune" + t, "", "")));
        BrandLead lead = discovery.discover("desert brands", 3).getFirst();
        discovery.dismiss(lead.id);
        assertThat(discovery.open()).extracting(l -> l.id).doesNotContain(lead.id);
    }
}
