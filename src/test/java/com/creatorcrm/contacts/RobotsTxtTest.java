package com.creatorcrm.contacts;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RobotsTxtTest {

    @Test
    void starGroupAppliesWhenNoGroupNamesUs() {
        RobotsTxt r = RobotsTxt.parse("User-agent: *\nDisallow: /cart\nDisallow: /account\nCrawl-delay: 5\n", "CreatorCRM");
        assertThat(r.allows("/")).isTrue();
        assertThat(r.allows("/pages/contact")).isTrue();
        assertThat(r.allows("/cart")).isFalse();
        assertThat(r.allows("/account/login")).isFalse();
        assertThat(r.crawlDelaySeconds()).isEqualTo(5.0);
    }

    @Test
    void ourOwnGroupWinsOverStar() {
        String txt = "User-agent: *\nAllow: /\n\nUser-agent: Googlebot\nUser-agent: CreatorCRM\nDisallow: /\n";
        assertThat(RobotsTxt.parse(txt, "CreatorCRM").allows("/contact")).isFalse();
        assertThat(RobotsTxt.parse(txt, "OtherBot").allows("/contact")).isTrue();
    }

    @Test
    void longestRuleWinsAndAllowWinsATie() {
        RobotsTxt r = RobotsTxt.parse("User-agent: *\nDisallow: /pages\nAllow: /pages/contact\nDisallow: /*.pdf$\n", "CreatorCRM");
        assertThat(r.allows("/pages/contact")).isTrue();
        assertThat(r.allows("/pages/about")).isFalse();
        assertThat(r.allows("/files/kit.pdf")).isFalse();
        assertThat(r.allows("/files/kit.pdf?x=1")).isTrue();
        assertThat(RobotsTxt.parse("User-agent: *\nDisallow: /a\nAllow: /a\n", "x").allows("/a")).isTrue();
    }

    @Test
    void emptyDisallowAllowsEverythingAndCommentsAreIgnored() {
        RobotsTxt r = RobotsTxt.parse("# hi\nUser-agent: * # all\nDisallow:\n", "CreatorCRM");
        assertThat(r.allows("/anything")).isTrue();
        assertThat(RobotsTxt.disallowAll().allows("/")).isFalse();
        assertThat(RobotsTxt.allowAll().allows("/")).isTrue();
    }
}
