package com.creatorcrm.contacts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.creatorcrm.domain.BrandContact;
import com.creatorcrm.domain.BrandContact.Verified;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Reading Hunter's and Apollo's answers, credit costs and the mail-server rules: plain code, no network. */
class FinderRulesTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void readsHunterDomainSearch() throws Exception {
        HunterApi.DomainResult r = HunterApi.parseDomain(JSON.readTree("""
                {"data":{"domain":"glow.com","organization":"Glow","accept_all":true,"pattern":"{first}","emails":[
                  {"value":"priya@glow.com","type":"personal","confidence":91,"first_name":"Priya","last_name":"Shah",
                   "position":"Influencer Marketing Manager","department":"marketing","linkedin":"https://linkedin.com/in/p",
                   "phone_number":null,"verification":{"date":"2026-09-02","status":"valid"},
                   "sources":[{"domain":"glow.com","uri":"https://glow.com/about"}]},
                  {"value":"press@glow.com","type":"generic","confidence":null,"verification":null,"sources":[]}]},
                 "meta":{"results":14}}"""));
        assertThat(r.acceptAll()).isTrue();
        assertThat(r.total()).isEqualTo(14);
        assertThat(r.people()).hasSize(2);
        HunterApi.Person p = r.people().get(0);
        assertThat(p.name()).isEqualTo("Priya Shah");
        assertThat(p.position()).isEqualTo("Influencer Marketing Manager");
        assertThat(p.confidence()).isEqualTo(91);
        assertThat(p.verification()).isEqualTo("valid");
        assertThat(p.sourceUrl()).isEqualTo("https://glow.com/about");
        assertThat(r.people().get(1).name()).isNull();
        assertThat(r.people().get(1).confidence()).isNull();
    }

    @Test
    void readsHunterAccountOldAndNewPlans() throws Exception {
        HunterApi.Account credits = HunterApi.parseAccount(JSON.readTree("""
                {"data":{"plan_name":"Free","reset_date":"2026-11-01","requests":{"credits":{"used":12.5,"available":50}}}}"""));
        assertThat(credits.used()).isEqualTo(12.5);
        assertThat(credits.available()).isEqualTo(50);
        assertThat(credits.resetDate()).isEqualTo("2026-11-01");
        HunterApi.Account split = HunterApi.parseAccount(JSON.readTree("""
                {"data":{"plan_name":"Free","requests":{"searches":{"used":3,"available":25},"verifications":{"used":4,"available":50}}}}"""));
        assertThat(split.used()).isEqualTo(7);
        assertThat(split.available()).isEqualTo(75);
    }

    @Test
    void hunterErrorsAreInPlainWords() {
        assertThatThrownBy(() -> HunterApi.ok(new HunterApi.Reply(401, "{}"))).hasMessageContaining("didn't accept the key");
        assertThatThrownBy(() -> HunterApi.ok(new HunterApi.Reply(429, "{}"))).hasMessageContaining("credits are used up");
        assertThatThrownBy(() -> HunterApi.ok(new HunterApi.Reply(400, "{\"errors\":[{\"details\":\"Domain is invalid\"}]}")))
                .hasMessageContaining("Domain is invalid");
    }

    @Test
    void verifierAnswersDecideWhetherAnAddressIsEmailed() {
        assertThat(ContactFinder.fromHunterCheck(new HunterApi.Verification("valid", "deliverable", 97, false))).isEqualTo(Verified.VALID);
        assertThat(ContactFinder.fromHunterCheck(new HunterApi.Verification("invalid", "undeliverable", 0, false))).isEqualTo(Verified.INVALID);
        assertThat(ContactFinder.fromHunterCheck(new HunterApi.Verification("disposable", "risky", 10, false))).isEqualTo(Verified.INVALID);
        assertThat(ContactFinder.fromHunterCheck(new HunterApi.Verification("accept_all", "risky", 60, false))).isEqualTo(Verified.RISKY);
        assertThat(ContactFinder.fromHunterCheck(new HunterApi.Verification("unknown", null, null, false))).isEqualTo(Verified.UNKNOWN);
        assertThat(ContactFinder.fromHunterCheck(new HunterApi.Verification("unknown", null, null, true))).isNull();
        assertThat(ContactFinder.fromHunterStatus("webmail")).isNull();
    }

    @Test
    void domainSearchCostsOneCreditPerTenAddresses() {
        assertThat(ContactFinder.searchCost(0)).isZero();
        assertThat(ContactFinder.searchCost(1)).isEqualTo(1);
        assertThat(ContactFinder.searchCost(10)).isEqualTo(1);
        assertThat(ContactFinder.searchCost(11)).isEqualTo(2);
    }

    @Test
    void onlyUncheckedOrStaleAddressesAreChecked() {
        OffsetDateTime now = OffsetDateTime.now();
        BrandContact c = new BrandContact();
        assertThat(ContactFinder.needsCheck(c, now)).isTrue();
        c.verified = Verified.VALID;
        c.verifiedAt = now.minusDays(10);
        assertThat(ContactFinder.needsCheck(c, now)).isFalse();
        c.verifiedAt = now.minusDays(ContactFinder.STALE_DAYS + 1);
        assertThat(ContactFinder.needsCheck(c, now)).isTrue();
        c.verified = Verified.INVALID;
        assertThat(ContactFinder.needsCheck(c, now)).isFalse();
        c.verified = Verified.UNKNOWN;
        c.optedOut = true;
        assertThat(ContactFinder.needsCheck(c, now)).isFalse();
    }

    @Test
    void nullMxMeansNoEmail() {
        assertThat(MailServers.interpretMx(List.of("0 ."))).isEqualTo(MailServers.Result.NO_MAIL);
        assertThat(MailServers.interpretMx(List.of("10 mx1.glow.com.", "20 mx2.glow.com."))).isEqualTo(MailServers.Result.ACCEPTS_MAIL);
    }

    @Test
    void mailServerCheckFallsBackToTheDomainAndTreatsDnsTroubleAsUnknown() {
        MailServers noMx = new MailServers() {
            @Override
            protected List<String> records(String domain, String type) throws Exception {
                if (domain.startsWith("gone")) throw new javax.naming.NameNotFoundException(domain);
                if (domain.startsWith("down")) throw new javax.naming.CommunicationException("timeout");
                return "A".equals(type) && domain.startsWith("site") ? List.of("192.0.2.1") : List.of();
            }
        };
        assertThat(noMx.check("site.com")).isEqualTo(MailServers.Result.ACCEPTS_MAIL);
        assertThat(noMx.check("parked.com")).isEqualTo(MailServers.Result.NO_MAIL);
        assertThat(noMx.check("gone.com")).isEqualTo(MailServers.Result.NO_MAIL);
        assertThat(noMx.check("down.com")).isEqualTo(MailServers.Result.UNKNOWN);
    }

    @Test
    void apolloHiddenEmailsAreNotSaved() throws Exception {
        List<ApolloApi.Person> people = ApolloApi.parsePeople(JSON.readTree("""
                [{"id":"a1","first_name":"Jo","last_name":"Kim","title":"Brand Partnerships","email":"email_not_unlocked@glow.com"},
                 {"id":"a2","name":"Ana Ruiz","title":"PR","email":"ana@glow.com","email_status":"verified"}]"""));
        assertThat(people.get(0).email()).isNull();
        assertThat(people.get(0).name()).isEqualTo("Jo Kim");
        assertThat(people.get(1).email()).isEqualTo("ana@glow.com");
    }

    @Test
    void longDomainsStillFitAStateKey() {
        String k = FinderCredits.key(FinderCredits.Provider.HUNTER, "searched." + "a".repeat(200) + ".com");
        assertThat(k).hasSizeLessThanOrEqualTo(100);
        assertThat(k).isNotEqualTo(FinderCredits.key(FinderCredits.Provider.HUNTER, "searched." + "a".repeat(201) + ".com"));
    }
}
