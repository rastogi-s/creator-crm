package com.creatorcrm.contacts;

import static org.assertj.core.api.Assertions.assertThat;

import com.creatorcrm.domain.BrandContact;
import com.creatorcrm.domain.BrandContact.Role;
import com.creatorcrm.domain.BrandContact.Verified;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Cleaning, roles, ranking, signatures, opt-out replies and CSV: the plain-code rules behind brand contacts. */
class ContactRulesTest {
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-10-07T10:00:00Z");

    @Test
    void cleansAddressesAndDropsJunk() {
        assertThat(Emails.clean("  Jane Doe <Jane.Doe@GlowBerry.com> ")).isEqualTo("jane.doe@glowberry.com");
        assertThat(Emails.clean("mailto:collabs@glowberry.com?subject=Hi")).isEqualTo("collabs@glowberry.com");
        assertThat(Emails.clean("someone+creators@gmail.com")).isEqualTo("someone@gmail.com");
        assertThat(Emails.clean("partner+tag@brand.com")).isEqualTo("partner+tag@brand.com");
        assertThat(Emails.clean("noreply@brand.com")).isNull();
        assertThat(Emails.clean("privacy@brand.com")).isNull();
        assertThat(Emails.clean("logo@2x.png")).isNull();
        assertThat(Emails.clean("you@example.com")).isNull();
        assertThat(Emails.clean("abc@o12345.ingest.sentry.io")).isNull();
        assertThat(Emails.clean("not an email")).isNull();
        assertThat(Emails.findAll("To: A <a@x.com>, b@y.com; a@x.com")).containsExactly("a@x.com", "b@y.com");
    }

    @Test
    void matchesBrandsByRegisteredDomain() {
        assertThat(Emails.domainOfUrl("https://shop.glowberry.com/pages/contact")).isEqualTo("glowberry.com");
        assertThat(Emails.domainOfUrl("www.glowberry.co.uk")).isEqualTo("glowberry.co.uk");
        assertThat(Emails.brandDomain("pr@mail.glowberry.com")).isEqualTo("glowberry.com");
        assertThat(Emails.brandDomain("jane@gmail.com")).isNull();
        assertThat(Emails.domainOfUrl("")).isNull();
    }

    @Test
    void guessesRolesFromTitlesThenInboxNames() {
        assertThat(ContactRoles.guess("jane@brand.com", "Influencer Marketing Manager")).isEqualTo(Role.PARTNERSHIPS);
        assertThat(ContactRoles.guess("jane@brand.com", "Co-Founder & CEO")).isEqualTo(Role.FOUNDER);
        assertThat(ContactRoles.guess("collabs@brand.com", null)).isEqualTo(Role.PARTNERSHIPS);
        assertThat(ContactRoles.guess("press@brand.com", null)).isEqualTo(Role.PR);
        assertThat(ContactRoles.guess("hello@brand.com", null)).isEqualTo(Role.GENERAL);
        assertThat(ContactRoles.guess("support@brand.com", null)).isEqualTo(Role.SUPPORT);
        assertThat(ContactRoles.guess("jane@brand.com", null)).isEqualTo(Role.OTHER);
    }

    private static BrandContact contact(Role role, int replies, int deals, Integer replyHours, int sent) {
        BrandContact c = new BrandContact();
        c.role = role;
        c.replies = replies;
        c.dealsWon = deals;
        c.avgReplyHours = replyHours;
        c.emailsSent = sent;
        c.lastRepliedAt = replies > 0 ? NOW.minusDays(10) : null;
        return c;
    }

    @Test
    void peopleWhoRepliedRankFirstAndAreRankedAmongThemselves() {
        BrandContact dealMaker = contact(Role.OTHER, 3, 2, 5, 3);
        BrandContact fastReplier = contact(Role.SUPPORT, 1, 0, 3, 1);
        BrandContact slowReplier = contact(Role.PARTNERSHIPS, 1, 0, 200, 1);
        BrandContact bestUntried = contact(Role.PARTNERSHIPS, 0, 0, null, 0);
        bestUntried.name = "Jane";
        bestUntried.verified = Verified.VALID;
        BrandContact ignoredUs = contact(Role.PARTNERSHIPS, 0, 0, null, 4);
        BrandContact supportInbox = contact(Role.SUPPORT, 0, 0, null, 0);

        int deal = ContactRanking.score(dealMaker, NOW).score();
        int fast = ContactRanking.score(fastReplier, NOW).score();
        int slow = ContactRanking.score(slowReplier, NOW).score();
        int untried = ContactRanking.score(bestUntried, NOW).score();
        assertThat(deal).isGreaterThan(fast);
        assertThat(fast).isGreaterThan(slow);
        assertThat(slow).isGreaterThan(untried);
        assertThat(untried).isGreaterThan(ContactRanking.score(ignoredUs, NOW).score());
        assertThat(untried).isGreaterThan(ContactRanking.score(supportInbox, NOW).score());
        assertThat(ContactRanking.score(dealMaker, NOW).reason()).contains("Replied 3 times").contains("2 deals");
    }

    @Test
    void anyoneWhoCantBeEmailedScoresZero() {
        BrandContact c = contact(Role.PARTNERSHIPS, 5, 2, 2, 5);
        c.optedOut = true;
        assertThat(ContactRanking.score(c, NOW).score()).isZero();
        c.optedOut = false;
        c.bounced = true;
        assertThat(ContactRanking.score(c, NOW).score()).isZero();
        c.bounced = false;
        c.verified = Verified.INVALID;
        assertThat(ContactRanking.score(c, NOW).score()).isZero();
    }

    @Test
    void readsTitleAndPhoneFromSignature() {
        String body = """
                Hi! We'd love to work with you on our fall launch.

                Best,
                Jane Doe | Influencer Marketing Manager
                GlowBerry Skincare
                +1 (415) 555-0134

                On Mon, Oct 5, 2026 at 9:00 AM Creator <me@gmail.com> wrote:
                > Hi Jane Doe, Senior Director of Everything +1 999 999 9999
                """;
        SignatureParser.Signature s = SignatureParser.parse(body, "Jane Doe");
        assertThat(s.title()).isEqualTo("Influencer Marketing Manager");
        assertThat(s.phone()).isEqualTo("+1 (415) 555-0134");

        SignatureParser.Signature none = SignatureParser.parse("Thanks!\nJane\nThanks again", "Jane Doe");
        assertThat(none.title()).isNull();
        assertThat(none.phone()).isNull();
    }

    @Test
    void spotsRepliesAskingToStop() {
        assertThat(OptOut.asksToStop("Please remove me from your list.\n\nOn Mon wrote:\n> pitch")).isTrue();
        assertThat(OptOut.asksToStop("unsubscribe")).isTrue();
        assertThat(OptOut.asksToStop("Not interested, please stop emailing us")).isTrue();
        assertThat(OptOut.asksToStop("We'd love to work with you! What are your rates?")).isFalse();
        assertThat(OptOut.asksToStop("Thanks!\n" + "x".repeat(500) + " unsubscribe")).isFalse();
    }

    @Test
    void parsesAndWritesCsvSafely() {
        List<List<String>> rows = ContactCsv.parse("﻿Email,Company\r\n\"a@x.com\",\"Glow, \"\"Co\"\"\"\n\nb@y.com,Y\n");
        assertThat(rows).containsExactly(List.of("Email", "Company"), List.of("a@x.com", "Glow, \"Co\""), List.of("b@y.com", "Y"));
        assertThat(ContactCsv.parse("email;name\na@x.com;Ann")).containsExactly(List.of("email", "name"), List.of("a@x.com", "Ann"));
        assertThat(ContactCsv.cell("=HYPERLINK(\"x\")")).isEqualTo("\"'=HYPERLINK(\"\"x\"\")\"");
    }

    @Test
    void onlyRoleInboxesFromFreeSourcesAreShareable() {
        BrandContact inbox = contact(Role.PARTNERSHIPS, 0, 0, null, 0);
        var web = new com.creatorcrm.domain.ContactSource();
        web.source = com.creatorcrm.domain.ContactSource.Kind.WEBSITE;
        var hunter = new com.creatorcrm.domain.ContactSource();
        hunter.source = com.creatorcrm.domain.ContactSource.Kind.HUNTER;
        assertThat(ContactService.shareable(inbox, List.of(web))).isTrue();
        assertThat(ContactService.shareable(inbox, List.of(web, hunter))).isFalse();
        inbox.name = "Jane";
        assertThat(ContactService.shareable(inbox, List.of(web))).isFalse();
    }
}
