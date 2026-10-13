package com.creatorcrm.contacts;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class BouncesTest {

    @Test
    void spotsDeliveryFailureNotices() {
        assertThat(Bounces.isBounce("Mail Delivery Subsystem <mailer-daemon@googlemail.com>", "Delivery Status Notification (Failure)")).isTrue();
        assertThat(Bounces.isBounce("MAILER-DAEMON@yahoo.com", "Failure Notice")).isTrue();
        assertThat(Bounces.isBounce("postmaster@glowberry.com", "Undeliverable: Collab idea")).isTrue();
        assertThat(Bounces.isBounce("Microsoft Outlook <MicrosoftExchange329e71ec88ae@glowberry.com>", "Undeliverable: Collab idea")).isTrue();
    }

    @Test
    void aBrandWritingAboutAParcelIsNotABounce() {
        assertThat(Bounces.isBounce("ana@glowberry.com", "Re: Collab idea for Glowberry")).isFalse();
        assertThat(Bounces.isBounce("ana@glowberry.com", "Re: Undelivered package")).isFalse();
        assertThat(Bounces.isBounce("ana@glowberry.com", "Fwd: Returned mail")).isFalse();
        assertThat(Bounces.isBounce(null, null)).isFalse();
    }
}
