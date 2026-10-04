package com.creatorcrm.results;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.creatorcrm.FakeLlm;
import com.creatorcrm.channels.instagram.InstagramApi;
import com.creatorcrm.domain.Brand;
import com.creatorcrm.domain.CampaignResult;
import com.creatorcrm.domain.Deadline;
import com.creatorcrm.domain.Draft;
import com.creatorcrm.domain.Enums.Compensation;
import com.creatorcrm.domain.Enums.DeadlineType;
import com.creatorcrm.domain.Enums.DraftStatus;
import com.creatorcrm.domain.Enums.DraftType;
import com.creatorcrm.domain.Enums.OpportunityStatus;
import com.creatorcrm.domain.Enums.OpportunityType;
import com.creatorcrm.domain.Enums.Origin;
import com.creatorcrm.domain.Opportunity;
import com.creatorcrm.repo.BrandRepo;
import com.creatorcrm.repo.CampaignResultRepo;
import com.creatorcrm.repo.DeadlineRepo;
import com.creatorcrm.repo.DraftRepo;
import com.creatorcrm.repo.OpportunityRepo;
import com.creatorcrm.security.SecretName;
import com.creatorcrm.security.SecretStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** Campaign wrap-up end to end: find the post, read its numbers a week later, draft the recap with the PDF. */
@SpringBootTest
@ActiveProfiles("test")
class CampaignResultsIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TestConfiguration
    static class Config {
        @Bean
        @Primary
        FakeLlm fakeLlm() {
            return new FakeLlm();
        }
    }

    @MockitoBean InstagramApi api;
    @Autowired FakeLlm llm;
    @Autowired CampaignResults results;
    @Autowired CampaignResultRepo resultRepo;
    @Autowired BrandRepo brands;
    @Autowired OpportunityRepo opportunities;
    @Autowired DeadlineRepo deadlines;
    @Autowired DraftRepo drafts;
    @Autowired SecretStore secrets;

    @BeforeEach
    void connect() {
        secrets.put(SecretName.INSTAGRAM_ACCESS_TOKEN, "IGtoken");
        llm.failDraftsWith = null;
    }

    @AfterEach
    void disconnect() {
        secrets.delete(SecretName.INSTAGRAM_ACCESS_TOKEN);
        llm.failDraftsWith = null;
    }

    private Opportunity deal(String name, String handle, OpportunityStatus status, Compensation comp) {
        Brand b = new Brand();
        b.name = name;
        b.nameKey = Brand.key(name) + UUID.randomUUID().toString().substring(0, 6);
        b.instagram = handle;
        b.contactEmail = "hello@" + Brand.key(name) + ".test";
        b.createdAt = OffsetDateTime.now();
        brands.save(b);
        Opportunity o = new Opportunity();
        o.brandId = b.id;
        o.origin = Origin.INBOUND;
        o.type = OpportunityType.PAID;
        o.compensation = comp;
        o.status = status;
        o.deliverables = "1 Reel";
        o.campaign = "Autumn candles";
        o.createdAt = OffsetDateTime.now();
        o.updatedAt = OffsetDateTime.now();
        return opportunities.save(o);
    }

    private void posting(Opportunity o, LocalDate day) {
        Deadline d = new Deadline();
        d.opportunityId = o.id;
        d.type = DeadlineType.POSTING;
        d.dueDate = day;
        d.description = "Post the Reel";
        deadlines.save(d);
    }

    private static String iso(OffsetDateTime t) {
        return t.withOffsetSameInstant(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssZ"));
    }

    private void media(String json) throws Exception {
        when(api.get(anyString(), eq("/me/media"), any())).thenReturn(JSON.readTree(json));
    }

    @Test
    void findsThePostReadsItsNumbersAWeekLaterAndDraftsTheRecap() throws Exception {
        String tag = UUID.randomUUID().toString().substring(0, 5);
        String brand = "Wickford Candles " + tag;
        Opportunity o = deal(brand, "wickford" + tag, OpportunityStatus.POSTED, Compensation.GIFTED);
        OffsetDateTime postedAt = OffsetDateTime.now().minusDays(9);
        posting(o, postedAt.toLocalDate());
        media("{\"data\":["
                + "{\"id\":\"m1" + tag + "\",\"caption\":\"Cozy nights with @wickford" + tag + " #gifted\",\"permalink\":\"https://www.instagram.com/p/W" + tag + "/\",\"timestamp\":\"" + iso(postedAt) + "\",\"media_type\":\"VIDEO\",\"media_product_type\":\"REELS\"},"
                + "{\"id\":\"m2\",\"caption\":\"Sunday hike\",\"permalink\":\"https://www.instagram.com/p/H/\",\"timestamp\":\"" + iso(postedAt) + "\",\"media_type\":\"IMAGE\"}]}");
        when(api.get(anyString(), eq("/m1" + tag + "/insights"), any())).thenReturn(JSON.readTree("{\"data\":["
                + "{\"name\":\"reach\",\"values\":[{\"value\":18450}]},{\"name\":\"views\",\"values\":[{\"value\":26310}]},"
                + "{\"name\":\"likes\",\"values\":[{\"value\":1284}]},{\"name\":\"comments\",\"values\":[{\"value\":96}]},"
                + "{\"name\":\"saved\",\"values\":[{\"value\":412}]},{\"name\":\"shares\",\"total_value\":{\"value\":138}}]}"));

        int recaps = results.daily();

        assertThat(recaps).isGreaterThanOrEqualTo(1);
        CampaignResult r = resultRepo.findByOpportunityId(o.id).orElseThrow();
        assertThat(r.mediaId).isEqualTo("m1" + tag);
        assertThat(r.postUrl).isEqualTo("https://www.instagram.com/p/W" + tag + "/");
        assertThat(r.mediaType).isEqualTo("REELS");
        assertThat(r.reach).isEqualTo(18450);
        assertThat(r.saves).isEqualTo(412);
        assertThat(r.shares).isEqualTo(138);
        assertThat(r.source).isEqualTo(CampaignResult.Source.INSTAGRAM);
        assertThat(r.recapDraftedAt).isNotNull();
        assertThat(CampaignResults.engagementRate(r)).isEqualTo(10.5); // 1,930 / 18,450

        Draft d = drafts.findByOpportunityIdAndStatus(o.id, DraftStatus.PENDING).stream()
                .filter(x -> x.type == DraftType.RESULTS_RECAP).findFirst().orElseThrow();
        assertThat(d.resultId).isEqualTo(r.id);
        assertThat(llm.lastDraftInput.draftType()).isEqualTo("RESULTS_RECAP");
        assertThat(llm.lastDraftInput.extraInstructions())
                .contains("18,450 accounts reached, 26,310 views, 1,284 likes, 96 comments, 412 saves, 138 shares, 10.5% engagement rate")
                .contains("Quote these exactly").contains("paid collab");

        // The PDF the brand gets.
        try (var pdf = Loader.loadPDF(results.pdf(o.id))) {
            String text = new PDFTextStripper().getText(pdf);
            assertThat(text).contains("Campaign results").contains(brand).contains("ACCOUNTS REACHED").contains("18,450")
                    .contains("10.5%").contains("Numbers from Instagram Insights");
        }

        // Drafting again replaces the unsent recap.
        Draft again = results.draftRecap(o.id);
        assertThat(drafts.findById(d.id).orElseThrow().status).isEqualTo(DraftStatus.SUPERSEDED);
        assertThat(again.status).isEqualTo(DraftStatus.PENDING);
    }

    @Test
    void waitsAWeekAndCopesWithMetricsAPostDoesntHave() throws Exception {
        String tag = UUID.randomUUID().toString().substring(0, 5);
        Opportunity o = deal("Harbor Linen " + tag, null, OpportunityStatus.PAYMENT_PENDING, Compensation.PAID);
        OffsetDateTime postedAt = OffsetDateTime.now().minusDays(2);
        media("{\"data\":[{\"id\":\"h" + tag + "\",\"caption\":\"New sheets from Harbor Linen " + tag + "!\",\"permalink\":\"https://www.instagram.com/p/HL" + tag + "/\",\"timestamp\":\"" + iso(postedAt) + "\",\"media_type\":\"IMAGE\"}]}");

        results.daily();
        CampaignResult r = resultRepo.findByOpportunityId(o.id).orElseThrow();
        assertThat(r.mediaId).isEqualTo("h" + tag);
        assertThat(r.fetchedAt).isNull(); // two days old: not yet
        assertThat(results.forDeal(o.id).orElseThrow().numbersDue()).isEqualTo(postedAt.toLocalDate().plusDays(7));

        // She asks for the numbers now. Asking for all metrics fails (no views on a photo), so each is read alone.
        when(api.get(anyString(), eq("/h" + tag + "/insights"), argThat(q -> q != null && String.valueOf(q.get("metric")).contains(","))))
                .thenThrow(new InstagramApi.InstagramApiException(400, "views is not supported for this media"));
        when(api.get(anyString(), eq("/h" + tag + "/insights"), argThat(q -> q != null && "reach".equals(q.get("metric")))))
                .thenReturn(JSON.readTree("{\"data\":[{\"name\":\"reach\",\"values\":[{\"value\":5000}]}]}"));
        when(api.get(anyString(), eq("/h" + tag + "/insights"), argThat(q -> q != null && "likes".equals(q.get("metric")))))
                .thenReturn(JSON.readTree("{\"data\":[{\"name\":\"likes\",\"values\":[{\"value\":250}]}]}"));
        CampaignResults.View v = results.fetch(o.id);
        assertThat(v.reach()).isEqualTo(5000);
        assertThat(v.likes()).isEqualTo(250);
        assertThat(v.views()).isNull();
        assertThat(v.engagementRate()).isEqualTo(5.0);
        assertThat(v.numbersDue()).isNull();
    }

    @Test
    void typedInNumbersForATikTokAndARecapWithoutClaude() {
        String tag = UUID.randomUUID().toString().substring(0, 5);
        Opportunity o = deal("Juno Tea " + tag, null, OpportunityStatus.POSTED, Compensation.PAID);
        assertThatThrownBy(() -> results.draftRecap(o.id)).hasMessageContaining("numbers");
        assertThatThrownBy(() -> results.link(o.id, "not a link")).hasMessageContaining("https://");

        CampaignResults.View v = results.link(o.id, "https://www.tiktok.com/@maya/video/7400000000");
        assertThat(v.postUrl()).isEqualTo("https://www.tiktok.com/@maya/video/7400000000");
        assertThatThrownBy(() -> results.fetch(o.id)).hasMessageContaining("type its numbers in");
        v = results.saveNumbers(o.id, new CampaignResults.Numbers(null, 40000L, 3100L, 120L, null, 75L));
        assertThat(v.source()).isEqualTo("MANUAL");
        assertThat(v.engagementRate()).isNull(); // no reach

        llm.failDraftsWith = new IllegalStateException("No Claude API key");
        Draft d = results.draftRecap(o.id);
        assertThat(d.body).contains("40,000 views, 3,100 likes, 120 comments, 75 shares").contains("attached PDF");
        assertThat(d.type).isEqualTo(DraftType.RESULTS_RECAP);
        assertThatThrownBy(() -> results.saveNumbers(o.id, new CampaignResults.Numbers(-1L, null, null, null, null, null)))
                .hasMessageContaining("negative");
    }
}
