package com.creatorcrm.outreach;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.creatorcrm.channels.instagram.InstagramApi;
import com.creatorcrm.domain.Brand;
import com.creatorcrm.domain.BrandLead;
import com.creatorcrm.domain.InstagramEngagement;
import com.creatorcrm.repo.BrandLeadRepo;
import com.creatorcrm.repo.BrandRepo;
import com.creatorcrm.repo.InstagramEngagementRepo;
import com.creatorcrm.repo.InstagramSeenEventRepo;
import com.creatorcrm.security.SecretName;
import com.creatorcrm.security.SecretStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** Brands engaging with her on Instagram become leads; the Facebook connection tells brands from fans. */
@SpringBootTest
@ActiveProfiles("test")
class InstagramEngagementIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @MockitoBean InstagramApi api;
    @Autowired InstagramEngagementService engagement;
    @Autowired BrandDiscoveryService discovery;
    @Autowired InstagramEngagementRepo engagements;
    @Autowired InstagramSeenEventRepo seen;
    @Autowired BrandLeadRepo leads;
    @Autowired BrandRepo brands;
    @Autowired SecretStore secrets;

    private static JsonNode json(String s) throws Exception {
        return JSON.readTree(s);
    }

    @BeforeEach
    void connect() throws Exception {
        secrets.put(SecretName.INSTAGRAM_ACCESS_TOKEN, "IGtoken");
        secrets.put(SecretName.INSTAGRAM_USERNAME, "maya");
        when(api.get(anyString(), eq("/me/media"), any())).thenReturn(json("""
                {"data":[{"id":"m1","permalink":"https://www.instagram.com/p/abc/","comments_count":3},
                         {"id":"m2","permalink":"https://www.instagram.com/p/def/","comments_count":0}]}"""));
        when(api.get(anyString(), eq("/m1/comments"), any())).thenReturn(json("""
                {"data":[{"id":"c1","username":"GlowCo","text":"Love this! DM us 💌","timestamp":"2026-10-01T10:00:00+0000"},
                         {"id":"c2","username":"glowco","text":"So pretty","timestamp":"2026-10-02T10:00:00+0000"},
                         {"id":"c3","username":"maya","text":"thank you!","timestamp":"2026-10-02T11:00:00+0000"},
                         {"id":"c4","username":"bestie22","text":"obsessed","timestamp":"2026-10-03T09:00:00+0000"}]}"""));
        when(api.get(anyString(), eq("/me/tags"), any())).thenThrow(new InstagramApi.InstagramApiException(400, "Unsupported"));
    }

    @AfterEach
    void cleanUp() {
        secrets.delete(SecretName.INSTAGRAM_ACCESS_TOKEN, SecretName.INSTAGRAM_USERNAME, SecretName.FACEBOOK_PAGE_TOKEN,
                SecretName.FACEBOOK_IG_USER_ID);
        engagements.deleteAll();
        seen.deleteAll();
    }

    private void connectFacebook() throws Exception {
        secrets.put(SecretName.FACEBOOK_PAGE_TOKEN, "PAGEtoken");
        secrets.put(SecretName.FACEBOOK_IG_USER_ID, "901");
        when(api.getFacebook(anyString(), eq("/901/tags"), any())).thenReturn(json("""
                {"data":[{"id":"t1","username":"sunbloom","caption":"Our fave creator @maya","permalink":"https://www.instagram.com/p/xyz/","timestamp":"2026-10-03T12:00:00+0000"}]}"""));
        when(api.getFacebook(anyString(), eq("/901"), argThat(q -> q != null && q.getOrDefault("fields", "").contains("username(glowco)"))))
                .thenReturn(json("""
                        {"business_discovery":{"username":"glowco","name":"Glow Co","biography":"Clean skincare. Collabs: hello@glowco.com",
                         "website":"https://glowco.com","followers_count":52000,"media_count":300,
                         "media":{"data":[{"caption":"With @lena_skin #ad"},{"caption":"New drop"}]}}}"""));
        when(api.getFacebook(anyString(), eq("/901"), argThat(q -> q != null && q.getOrDefault("fields", "").contains("username(sunbloom)"))))
                .thenReturn(json("""
                        {"business_discovery":{"username":"sunbloom","name":"Sunbloom","biography":"","followers_count":8000,"media_count":40,"media":{"data":[]}}}"""));
        when(api.getFacebook(anyString(), eq("/901"), argThat(q -> q != null && q.getOrDefault("fields", "").contains("username(bestie22)"))))
                .thenThrow(new InstagramApi.InstagramApiException(400, "Invalid user id"));
    }

    @Test
    void commentersAreCountedOnceAndHerOwnRepliesAreSkipped() {
        assertThat(engagement.poll()).isEqualTo(3);
        assertThat(engagement.poll()).isZero(); // same comments again

        InstagramEngagement glow = engagements.findByUsername("glowco").orElseThrow();
        assertThat(glow.comments).isEqualTo(2);
        assertThat(glow.lastText).isEqualTo("So pretty");
        assertThat(glow.lastPermalink).isEqualTo("https://www.instagram.com/p/abc/");
        assertThat(engagements.findByUsername("maya")).isEmpty();
        // Without the Facebook connection nobody can be checked, so everyone is listed.
        assertThat(engagement.open()).extracting(InstagramEngagementService.Row::username).containsExactlyInAnyOrder("glowco", "bestie22");
        assertThat(engagement.lastError()).isEmpty();
    }

    @Test
    void missingCommentsPermissionAsksForAReconnect() throws Exception {
        when(api.get(anyString(), eq("/m1/comments"), any())).thenThrow(new InstagramApi.InstagramApiException(400, "Insufficient permission"));
        engagement.poll();
        assertThat(engagement.lastError()).contains(InstagramEngagementService.NEEDS_RECONNECT);
    }

    @Test
    void facebookConnectionAddsTagsAndHidesFans() throws Exception {
        connectFacebook();
        engagement.poll();

        assertThat(engagement.open()).extracting(InstagramEngagementService.Row::username).containsExactly("sunbloom", "glowco");
        assertThat(engagements.findByUsername("bestie22").orElseThrow().isBusiness).isFalse();
        assertThat(engagements.findByUsername("glowco").orElseThrow().followers).isEqualTo(52000);
    }

    @Test
    void engagingAccountBecomesAnEnrichedLead() throws Exception {
        connectFacebook();
        engagement.poll();
        Long id = engagements.findByUsername("glowco").orElseThrow().id;

        BrandLead lead = engagement.makeLead(id);

        assertThat(lead.name).isEqualTo("Glow Co");
        assertThat(lead.source).isEqualTo(BrandLead.Source.INSTAGRAM);
        assertThat(lead.instagram).isEqualTo("glowco");
        assertThat(lead.fitReason).startsWith("Commented on your posts 2 times.");
        assertThat(lead.contactEmail).isEqualTo("hello@glowco.com");
        assertThat(lead.contactSourceUrl).isEqualTo("https://www.instagram.com/glowco/");
        assertThat(lead.website).isEqualTo("https://glowco.com");
        assertThat(lead.igPartners).isEqualTo("lena_skin");
        assertThat(BrandDiscoveryService.pitchInstructions(lead)).contains("already engaged").contains("@lena_skin");
        assertThat(engagement.open()).extracting(InstagramEngagementService.Row::username).doesNotContain("glowco");
        leads.deleteById(lead.id);
    }

    @Test
    void lookUpABrandByHandle() throws Exception {
        assertThatThrownBy(() -> discovery.lookupHandle("glowco")).hasMessageContaining("Connect Facebook");
        connectFacebook();

        BrandLead lead = discovery.lookupHandle("@GlowCo");
        assertThat(lead.source).isEqualTo(BrandLead.Source.LOOKUP);
        assertThat(lead.igFollowers).isEqualTo(52000);
        assertThat(discovery.lookupHandle("glowco").id).isEqualTo(lead.id); // no duplicate lead
        assertThatThrownBy(() -> discovery.lookupHandle("bestie22")).hasMessageContaining("isn't a business or creator account");
        leads.deleteById(lead.id);
    }

    @Test
    void brandsSheAlreadyWorksWithAreNotOfferedAgain() {
        Brand b = new Brand();
        b.name = "Glow Cosmetics Ltd"; // came in by email under another name
        b.nameKey = Brand.key(b.name);
        b.instagram = "GlowCo";
        b.createdAt = OffsetDateTime.now();
        b = brands.save(b);
        try {
            engagement.poll();
            assertThat(engagement.open()).extracting(InstagramEngagementService.Row::username).containsExactly("bestie22");
            Long id = engagements.findByUsername("glowco").orElseThrow().id;
            assertThatThrownBy(() -> engagement.makeLead(id)).hasMessageContaining("already one of your brands (Glow Cosmetics Ltd)");
        } finally {
            brands.deleteById(b.id);
        }
    }

    @Test
    void commentWebhookIsRecorded() throws Exception {
        engagement.onWebhookChange("comments", json("""
                {"id":"c99","text":"We'd love to send you our new serum","from":{"id":"5","username":"serumlab"},"media":{"id":"m1"}}"""));
        engagement.onWebhookChange("comments", json("""
                {"id":"c99","text":"We'd love to send you our new serum","from":{"id":"5","username":"serumlab"},"media":{"id":"m1"}}"""));
        InstagramEngagement e = engagements.findByUsername("serumlab").orElseThrow();
        assertThat(e.comments).isEqualTo(1);
        assertThat(e.lastKind).isEqualTo(InstagramEngagement.Kind.COMMENT);
    }
}
