package com.creatorcrm.campaigns;

import static org.assertj.core.api.Assertions.assertThat;

import com.creatorcrm.domain.Message;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.Test;

/** The plain-code rules: opt-out replies, bounces, recipient working hours and the footer. */
class CampaignRulesTest {

    @Test
    void shortNoThanksAndRemoveMeAreOptOuts() {
        assertThat(CampaignWatcher.asksToStop("No thanks")).isTrue();
        assertThat(CampaignWatcher.asksToStop("no thank you!\n\nOn Mon, Maya wrote:\n> Hi there")).isTrue();
        assertThat(CampaignWatcher.asksToStop("Not interested, sorry")).isTrue();
        assertThat(CampaignWatcher.asksToStop("Please remove me from your list")).isTrue();
        // A real answer is not an opt-out, even when the quoted footer says "no thanks"
        assertThat(CampaignWatcher.asksToStop("We'd love to chat! What are your rates?\n\nOn Mon, Maya wrote:\n> Not a fit? Just reply \"no thanks\""))
                .isFalse();
        assertThat(CampaignWatcher.asksToStop("No thanks needed, we'd love to send you our new serum. What's your address and when could you post? "
                + "Our team is planning a launch in March and we're looking for creators who fit our brand, so this would be perfect timing."))
                .isFalse();
    }

    @Test
    void spotsBounces() {
        Message m = new Message();
        m.sender = "mailer-daemon@googlemail.com";
        m.subject = "Delivery Status Notification (Failure)";
        assertThat(CampaignWatcher.isBounce(m)).isTrue();
        m.sender = "ana@glowberry.com";
        m.subject = "Re: Collab idea for Glowberry";
        assertThat(CampaignWatcher.isBounce(m)).isFalse();
    }

    @Test
    void sendsOnlyInTheRecipientsWorkingHours() {
        ZoneId ny = ZoneId.of("America/New_York");
        // Tuesday 10:00 in New York is 15:00 in London and 20:30 in India
        ZonedDateTime tue10 = ZonedDateTime.of(2026, 10, 6, 10, 0, 0, 0, ny);
        assertThat(RecipientHours.isWorkingTime("ana@glowberry.com", tue10, ny)).isTrue();
        assertThat(RecipientHours.isWorkingTime("ana@glowberry.co.uk", tue10, ny)).isTrue();
        assertThat(RecipientHours.isWorkingTime("ana@glowberry.in", tue10, ny)).isFalse();
        // Weekends and nights: never
        assertThat(RecipientHours.isWorkingTime("ana@glowberry.com", tue10.withHour(22), ny)).isFalse();
        assertThat(RecipientHours.isWorkingTime("ana@glowberry.com", tue10.plusDays(4), ny)).isFalse();
    }

    @Test
    void footerHasTheAddressAndOptOutAndComesBackIfRemoved() {
        String body = CampaignFooter.ensure("Hi Ana,\n\nLet's work together.", "Maya", "PO Box 12\nAustin, TX 78701");
        assertThat(body).endsWith("--\nMaya · PO Box 12, Austin, TX 78701\n" + CampaignFooter.OPT_OUT_LINE);
        assertThat(CampaignFooter.ensure(body, "Maya", "PO Box 12\nAustin, TX 78701")).isEqualTo(body);
        assertThat(CampaignFooter.ensure("Hi Ana, edited", "Maya", "PO Box 12")).contains("PO Box 12").contains(CampaignFooter.OPT_OUT_LINE);
    }
}
