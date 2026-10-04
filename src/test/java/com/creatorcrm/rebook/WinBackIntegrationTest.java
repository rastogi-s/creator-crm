package com.creatorcrm.rebook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;

import com.creatorcrm.FakeLlm;
import com.creatorcrm.channels.ChannelConnector.SentMessage;
import com.creatorcrm.channels.gmail.GmailConnector;
import com.creatorcrm.digest.DigestService;
import com.creatorcrm.domain.Activity;
import com.creatorcrm.domain.Brand;
import com.creatorcrm.domain.Draft;
import com.creatorcrm.domain.Enums.Compensation;
import com.creatorcrm.domain.Enums.DraftStatus;
import com.creatorcrm.domain.Enums.DraftType;
import com.creatorcrm.domain.Enums.FollowUpStatus;
import com.creatorcrm.domain.Enums.OpportunityStatus;
import com.creatorcrm.domain.Enums.OpportunityType;
import com.creatorcrm.domain.Enums.Origin;
import com.creatorcrm.domain.Opportunity;
import com.creatorcrm.drafts.DraftService;
import com.creatorcrm.invoices.InvoiceService;
import com.creatorcrm.repo.ActivityRepo;
import com.creatorcrm.repo.BrandRepo;
import com.creatorcrm.repo.DraftRepo;
import com.creatorcrm.repo.FollowUpRepo;
import com.creatorcrm.repo.OpportunityRepo;
import com.creatorcrm.settings.SettingsService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/** Win back past brands: who gets a re-pitch, how many a week, and what sending one does. */
@SpringBootTest
@ActiveProfiles("test")
class WinBackIntegrationTest {

    @TestConfiguration
    static class Config {
        @Bean
        @Primary
        FakeLlm fakeLlm() {
            return new FakeLlm();
        }
    }

    @MockitoSpyBean GmailConnector gmail;
    @Autowired FakeLlm llm;
    @Autowired WinBack winBack;
    @Autowired InvoiceService invoices;
    @Autowired DraftService drafts;
    @Autowired DraftRepo draftRepo;
    @Autowired BrandRepo brands;
    @Autowired OpportunityRepo opportunities;
    @Autowired ActivityRepo activity;
    @Autowired FollowUpRepo followUps;
    @Autowired DigestService digest;
    @Autowired SettingsService settings;

    private LocalDate today;

    @BeforeEach
    void reset() throws Exception {
        today = settings.today();
        llm.failDraftsWith = null;
        settings.update(Map.of(SettingsService.WIN_BACK_QUIET_DAYS, "60", SettingsService.WIN_BACK_WEEKLY_LIMIT, "5"));
        doReturn(true).when(gmail).isConnected();
        doReturn(Optional.empty()).when(gmail).pushDraft(any());
        doAnswer(inv -> new SentMessage(UUID.randomUUID().toString(), UUID.randomUUID().toString())).when(gmail).send(any());
        // The database is shared with other test classes: their brands count as re-pitched a month ago, which keeps
        // them out of the candidates without using up this week's allowance.
        for (Brand b : brands.findAll()) {
            b.lastRepitchAt = OffsetDateTime.now().minusDays(30);
            brands.save(b);
        }
        for (Draft d : draftRepo.findByStatusOrderByCreatedAtAsc(DraftStatus.PENDING)) {
            if (d.type == DraftType.REPITCH) {
                d.status = DraftStatus.DISCARDED;
                draftRepo.save(d);
            }
        }
    }

    private Brand brand() {
        Brand b = new Brand();
        b.name = "Brand " + UUID.randomUUID().toString().substring(0, 8);
        b.nameKey = Brand.key(b.name);
        b.contactName = "Noor";
        b.contactEmail = "noor@" + b.nameKey + ".test";
        b.createdAt = OffsetDateTime.now();
        return brands.save(b);
    }

    private Opportunity deal(Brand b, OpportunityStatus status, Compensation comp, String campaign) {
        Opportunity o = new Opportunity();
        o.brandId = b.id;
        o.origin = Origin.INBOUND;
        o.type = comp == Compensation.GIFTED ? OpportunityType.GIFTED : OpportunityType.PAID;
        o.compensation = comp;
        o.status = status;
        o.campaign = campaign;
        if (comp == Compensation.PAID) {
            o.budgetAmount = new BigDecimal("900");
            o.currency = "USD";
            o.budgetText = "$900";
        }
        o.createdAt = OffsetDateTime.now().minusDays(200);
        o.updatedAt = o.createdAt;
        return opportunities.save(o);
    }

    /** A paid collab whose invoice was paid {@code daysAgo} days ago (which closes the deal as paid). */
    private Opportunity paidCollab(Brand b, int daysAgo) {
        Opportunity o = deal(b, OpportunityStatus.PAYMENT_PENDING, Compensation.PAID, "Spring Reel");
        var inv = invoices.markSent(invoices.createForDeal(o.id, today.minusDays(daysAgo + 30L)).id);
        invoices.markPaid(inv.id, today.minusDays(daysAgo));
        return opportunities.findById(o.id).orElseThrow();
    }

    /** A gifted collab that moved to Posted {@code daysAgo} days ago. */
    private Opportunity giftedPost(Brand b, int daysAgo) {
        Opportunity o = deal(b, OpportunityStatus.POSTED, Compensation.GIFTED, "Candle gift set");
        Activity a = Activity.of(o.id, Activity.STATUS_CHANGED, b.name + ": 📦 Product Received → ✅ Posted");
        a.at = OffsetDateTime.now().minusDays(daysAgo);
        activity.save(a);
        return o;
    }

    private List<String> candidateBrands() {
        return winBack.candidates(today).stream().map(WinBack.Candidate::brand).toList();
    }

    private List<Draft> repitches(Opportunity o) {
        return draftRepo.findByOpportunityIdAndStatus(o.id, DraftStatus.PENDING).stream()
                .filter(d -> d.type == DraftType.REPITCH).toList();
    }

    @Test
    void picksQuietPaidBrandsAndGiftedCollabsAndSkipsBusyOnes() {
        Brand quietPaid = brand();
        paidCollab(quietPaid, 90);
        Brand recentPaid = brand();
        paidCollab(recentPaid, 30);
        Brand gifted = brand();
        giftedPost(gifted, 20);
        Brand freshGift = brand();
        giftedPost(freshGift, 5);
        Brand talking = brand(); // paid long ago, but already negotiating something new
        paidCollab(talking, 120);
        deal(talking, OpportunityStatus.NEGOTIATING, Compensation.PAID, "Fall campaign");
        Brand pitched = brand(); // paid long ago, pitched again last week (no answer, went cold)
        paidCollab(pitched, 120);
        Opportunity pitch = deal(pitched, OpportunityStatus.COLD, Compensation.UNKNOWN, "New idea");
        pitch.pitchedAt = today.minusDays(10);
        opportunities.save(pitch);
        Brand neverPosted = brand(); // closed before any content went up
        deal(neverPosted, OpportunityStatus.CLOSED, Compensation.PAID, "Declined deal");

        List<String> names = candidateBrands();
        assertThat(names).contains(quietPaid.name, gifted.name)
                .doesNotContain(recentPaid.name, freshGift.name, talking.name, pitched.name, neverPosted.name);
        assertThat(names.indexOf(quietPaid.name)).isLessThan(names.indexOf(gifted.name)); // paid before gifted

        WinBack.Candidate c = winBack.candidates(today).stream().filter(x -> x.brand().equals(quietPaid.name)).findFirst().orElseThrow();
        assertThat(c.lastCollab()).isEqualTo("Spring Reel");
        assertThat(c.gifted()).isFalse();
        assertThat(c.amount()).contains("900");
        assertThat(c.finishedOn()).isEqualTo(today.minusDays(90));
        assertThat(winBack.candidates(today).stream().filter(x -> x.brand().equals(gifted.name)).findFirst().orElseThrow().gifted()).isTrue();
    }

    @Test
    void draftsUpToTheWeeklyLimitOnceAndShowsThemOnToday() {
        settings.update(Map.of(SettingsService.WIN_BACK_WEEKLY_LIMIT, "2"));
        Opportunity a = paidCollab(brand(), 100);
        Opportunity b = paidCollab(brand(), 90);
        Opportunity c = giftedPost(brand(), 30);

        assertThat(winBack.draftDue(today)).isEqualTo(2);
        assertThat(repitches(a)).hasSize(1);
        assertThat(repitches(b)).hasSize(1);
        assertThat(repitches(c)).isEmpty(); // over this week's limit
        Draft d = repitches(a).get(0);
        assertThat(d.toAddress).startsWith("noor@");
        assertThat(d.conversationId).isNull();
        assertThat(llm.lastDraftInput.draftType()).isEqualTo("REPITCH");
        assertThat(brands.findById(a.brandId).orElseThrow().lastRepitchAt).isNotNull();

        // The next morning: the allowance is used up and the drafted brands aren't picked twice.
        assertThat(winBack.draftDue(today.plusDays(1))).isZero();
        assertThat(repitches(a)).hasSize(1);

        assertThat(digest.morning().rebook()).extracting(DigestService.Item::refId).contains(d.id);
        assertThat(digest.morning().rebook()).filteredOn(i -> i.refId().equals(d.id)).first()
                .satisfies(i -> assertThat(i.detail()).contains("Spring Reel").contains("re-pitch ready"));

        settings.update(Map.of(SettingsService.WIN_BACK_WEEKLY_LIMIT, "0"));
        brands.findAll().forEach(x -> { x.lastRepitchAt = OffsetDateTime.now().minusDays(30); brands.save(x); });
        assertThat(winBack.draftDue(today)).isZero(); // off
    }

    @Test
    void sendingARepitchStartsANewPitchAndLeavesTheOldDealAlone() {
        Opportunity old = paidCollab(brand(), 90);
        assertThat(old.status).isEqualTo(OpportunityStatus.CLOSED);
        Draft d = winBack.draftFor(old.id);
        assertThat(d.type).isEqualTo(DraftType.REPITCH);
        assertThat(llm.lastDraftInput.extraInstructions()).contains("Spring Reel").contains("fresh email").contains("Don't quote any rates");

        Draft sent = drafts.send(d.id, null, null);
        assertThat(sent.status).isEqualTo(DraftStatus.SENT);
        assertThat(sent.opportunityId).isNotEqualTo(old.id);
        Opportunity fresh = opportunities.findById(sent.opportunityId).orElseThrow();
        assertThat(fresh.brandId).isEqualTo(old.brandId);
        assertThat(fresh.origin).isEqualTo(Origin.PITCH);
        assertThat(fresh.status).isEqualTo(OpportunityStatus.PITCHED);
        assertThat(fresh.pitchedAt).isEqualTo(today);
        assertThat(fresh.campaign).contains("Spring Reel");
        assertThat(fresh.conversationId).isNotNull();
        assertThat(followUps.findByOpportunityIdOrderByNumberAsc(fresh.id))
                .anyMatch(f -> f.status == FollowUpStatus.SCHEDULED);
        assertThat(opportunities.findById(old.id).orElseThrow().status).isEqualTo(OpportunityStatus.CLOSED);

        // Now there's an open pitch, so the brand is no longer a candidate.
        brands.findById(old.brandId).ifPresent(b -> { b.lastRepitchAt = null; brands.save(b); });
        assertThat(candidateBrands()).doesNotContain(brands.findById(old.brandId).orElseThrow().name);
    }

    @Test
    void unfinishedDealsCantBeRepitchedAndSettingsAreChecked() {
        Opportunity open = deal(brand(), OpportunityStatus.NEGOTIATING, Compensation.PAID, "Live deal");
        assertThatThrownBy(() -> winBack.draftFor(open.id)).hasMessageContaining("finished collabs");
        assertThatThrownBy(() -> settings.update(Map.of(SettingsService.WIN_BACK_QUIET_DAYS, "7"))).hasMessageContaining("at least 14");
        assertThatThrownBy(() -> settings.update(Map.of(SettingsService.WIN_BACK_WEEKLY_LIMIT, "50"))).hasMessageContaining("0 to 20");
    }
}
