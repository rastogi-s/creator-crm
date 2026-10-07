package com.creatorcrm.contacts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.creatorcrm.contacts.FinderCredits.Provider;
import com.creatorcrm.domain.Brand;
import com.creatorcrm.domain.BrandContact;
import com.creatorcrm.domain.BrandContact.Verified;
import com.creatorcrm.domain.ContactSource;
import com.creatorcrm.domain.Draft;
import com.creatorcrm.domain.Enums.DraftType;
import com.creatorcrm.domain.Enums.Platform;
import com.creatorcrm.drafts.DraftService;
import com.creatorcrm.repo.BrandContactRepo;
import com.creatorcrm.repo.BrandRepo;
import com.creatorcrm.security.SecretName;
import com.creatorcrm.security.SecretStore;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** "Find more people" and address checks end to end, with Hunter and DNS replaced by stand-ins. */
@SpringBootTest
@ActiveProfiles("test")
class ContactFinderIntegrationTest {

    @MockitoBean HunterApi hunter;
    @MockitoBean MailServers mail;
    @Autowired ContactFinder finder;
    @Autowired FinderCredits credits;
    @Autowired ContactService contacts;
    @Autowired BrandRepo brands;
    @Autowired BrandContactRepo contactRepo;
    @Autowired SecretStore secrets;
    @Autowired DraftService drafts;

    @BeforeEach
    void setUp() {
        secrets.put(SecretName.HUNTER_API_KEY, "test-key");
        secrets.put(SecretName.APOLLO_API_KEY, null);
        credits.setLimit(Provider.HUNTER, 10_000);
        when(hunter.account(anyString())).thenReturn(new HunterApi.Account("Free", 1, 10_000, null));
        when(mail.check(anyString())).thenReturn(MailServers.Result.ACCEPTS_MAIL);
    }

    private static String tag() {
        return UUID.randomUUID().toString().substring(0, 6);
    }

    private Brand brand(String name, String website) {
        Brand b = new Brand();
        b.name = name;
        b.nameKey = Brand.key(name);
        b.website = website;
        b.createdAt = OffsetDateTime.now();
        return brands.save(b);
    }

    private static HunterApi.Person person(String email, String first, String last, String position, String verification) {
        return new HunterApi.Person(email, first, last, position, "marketing", "personal", 90, null, null, verification,
                "2026-09-01", null);
    }

    @Test
    void findsPeopleTagsThemLicensedAndNeverPaysTwiceForTheSameBrand() {
        String t = tag();
        String domain = "find" + t + ".com";
        Brand b = brand("Find " + t, "https://www." + domain + "/shop");
        double before = credits.usedThisMonth(Provider.HUNTER);
        when(hunter.domainSearch(anyString(), eq(domain), eq(HunterApi.PARTNERSHIP_DEPARTMENTS), anyInt()))
                .thenReturn(new HunterApi.DomainResult("Find", false, null, 3, List.of(
                        person("priya@" + domain, "Priya", "Shah", "Influencer Marketing Manager", "valid"),
                        person("sam@" + domain, "Sam", "Lee", "Social Media Coordinator", "invalid"),
                        person("noreply@" + domain, null, null, null, null))));

        ContactFinder.FindResult r = finder.findMore(b.id, false);

        assertThat(r.found()).isEqualTo(3);
        assertThat(r.added()).isEqualTo(2);
        assertThat(r.skipped()).isEqualTo(1);
        assertThat(r.hunterCredits()).isEqualTo(1);
        assertThat(credits.usedThisMonth(Provider.HUNTER)).isEqualTo(before + 1);
        BrandContact priya = contactRepo.findByEmail("priya@" + domain).orElseThrow();
        assertThat(priya.verified).isEqualTo(Verified.VALID);
        assertThat(priya.role).isEqualTo(BrandContact.Role.PARTNERSHIPS);
        BrandContact sam = contactRepo.findByEmail("sam@" + domain).orElseThrow();
        assertThat(sam.verified).isEqualTo(Verified.INVALID);
        assertThat(sam.score).isZero();
        // Found through a paid finder: for her own pitches only, never in anything shared
        assertThat(r.contacts()).allSatisfy(v -> {
            assertThat(v.sources()).contains("HUNTER");
            assertThat(v.shareable()).isFalse();
        });
        // The best one becomes the brand's main contact; the dead address never does
        assertThat(brands.findById(b.id).orElseThrow().contactEmail).isEqualTo("priya@" + domain);

        ContactFinder.FindResult again = finder.findMore(b.id, false);
        assertThat(again.askFirst()).contains(domain);
        verify(hunter, times(1)).domainSearch(anyString(), eq(domain), any(), anyInt());
    }

    @Test
    void searchesEveryoneWhenNobodyIsInMarketing() {
        String t = tag();
        String domain = "wide" + t + ".com";
        Brand b = brand("Wide " + t, domain);
        when(hunter.domainSearch(anyString(), eq(domain), eq(HunterApi.PARTNERSHIP_DEPARTMENTS), anyInt()))
                .thenReturn(new HunterApi.DomainResult(null, false, null, 0, List.of()));
        when(hunter.domainSearch(anyString(), eq(domain), isNull(), anyInt()))
                .thenReturn(new HunterApi.DomainResult(null, true, null, 1, List.of(person("hello@" + domain, null, null, null, null))));
        ContactFinder.FindResult r = finder.findMore(b.id, false);
        assertThat(r.added()).isEqualTo(1);
        assertThat(contactRepo.findByEmail("hello@" + domain).orElseThrow().verified).isEqualTo(Verified.RISKY);
        assertThat(finder.risky("Hello <hello@" + domain + ">")).isTrue();
    }

    @Test
    void staysWithinTheMonthlyLimitAndNeedsAKey() {
        String t = tag();
        Brand b = brand("Limit " + t, "limit" + t + ".com");
        credits.setLimit(Provider.HUNTER, 0);
        assertThatThrownBy(() -> finder.findMore(b.id, false)).hasMessageContaining("credits are used up");
        verify(hunter, never()).domainSearch(anyString(), eq("limit" + t + ".com"), any(), anyInt());

        secrets.put(SecretName.HUNTER_API_KEY, null);
        assertThatThrownBy(() -> finder.findMore(b.id, false)).hasMessageContaining("Add a Hunter key");
        Brand noSite = brand("No site " + t, null);
        secrets.put(SecretName.HUNTER_API_KEY, "test-key");
        assertThatThrownBy(() -> finder.findMore(noSite.id, false)).hasMessageContaining("website");
    }

    @Test
    void checksAddressesAndNeverEmailsOnesThatDontExist() {
        String t = tag();
        String domain = "check" + t + ".com";
        Brand b = brand("Check " + t, domain);
        BrandContact gone = contacts.add(b.id, ContactService.Found.of("old@" + domain, null, ContactSource.Kind.MANUAL, null)).orElseThrow();
        when(hunter.verify(anyString(), eq("old@" + domain))).thenReturn(new HunterApi.Verification("invalid", "undeliverable", 0, false));
        double before = credits.usedThisMonth(Provider.HUNTER);

        ContactFinder.CheckResult r = finder.check(gone.id);
        assertThat(r.contact().verified).isEqualTo(Verified.INVALID);
        assertThat(credits.usedThisMonth(Provider.HUNTER)).isEqualTo(before + 0.5);

        Draft pitch = new Draft();
        pitch.type = DraftType.PITCH;
        pitch.channel = Platform.EMAIL;
        pitch.toAddress = "old@" + domain;
        assertThat(drafts.sendBlockedReason(pitch)).isPresent().get().asString().contains("doesn't exist");

        // A domain with no mail server is caught for free, without asking Hunter
        String deadDomain = "dead" + t + ".com";
        Brand dead = brand("Dead " + t, deadDomain);
        BrandContact d = contacts.add(dead.id, ContactService.Found.of("hi@" + deadDomain, null, ContactSource.Kind.MANUAL, null)).orElseThrow();
        when(mail.check(deadDomain)).thenReturn(MailServers.Result.NO_MAIL);
        assertThat(finder.checkMailServers(10_000)).isGreaterThanOrEqualTo(1);
        assertThat(contactRepo.findById(d.id).orElseThrow().verified).isEqualTo(Verified.INVALID);
        verify(hunter, never()).verify(anyString(), eq("hi@" + deadDomain));
        assertThat(brands.findById(dead.id).orElseThrow().contactEmail).isNull();
    }

    @Test
    void someoneWhoRepliedIsProvenWithoutACredit() {
        String t = tag();
        Brand b = brand("Replied " + t, "replied" + t + ".com");
        BrandContact c = contacts.add(b.id, ContactService.Found.of("maya@replied" + t + ".com", "Maya", ContactSource.Kind.GMAIL, null)).orElseThrow();
        c.replies = 2;
        contactRepo.save(c);
        ContactFinder.CheckResult r = finder.check(c.id);
        assertThat(r.contact().verified).isEqualTo(Verified.VALID);
        assertThat(r.credits()).isZero();
        verify(hunter, never()).verify(anyString(), eq("maya@replied" + t + ".com"));
    }
}
