package com.creatorcrm.channels.gmail;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Google's consent screen lets her untick calendar access; the scopes on the token say whether she allowed it. */
class CalendarGrantTest {

    @Test
    void readsTheGrantedScopes() {
        assertThat(GmailOAuthController.calendarGranted(String.join(" ", GmailConnector.SCOPES))).isTrue();
        assertThat(GmailOAuthController.calendarGranted("https://www.googleapis.com/auth/gmail.readonly https://www.googleapis.com/auth/gmail.compose")).isFalse();
        assertThat(GmailOAuthController.calendarGranted(null)).isTrue(); // Google left the scopes out: assume what was asked
    }
}
