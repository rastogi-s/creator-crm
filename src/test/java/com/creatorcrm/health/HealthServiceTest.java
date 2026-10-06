package com.creatorcrm.health;

import static org.assertj.core.api.Assertions.assertThat;

import com.creatorcrm.backup.AutoBackupService;
import com.creatorcrm.health.HealthService.ChannelFacts;
import com.creatorcrm.health.HealthService.Check;
import com.creatorcrm.health.HealthService.ClaudeFacts;
import com.creatorcrm.health.HealthService.FixKind;
import com.creatorcrm.health.HealthService.State;
import com.creatorcrm.llm.ClaudeSpend;
import com.creatorcrm.update.UpdateService;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.Test;

class HealthServiceTest {

    /** A Thursday afternoon. */
    private static final ZonedDateTime NOW = ZonedDateTime.of(2026, 10, 8, 15, 0, 0, 0, ZoneId.of("America/New_York"));

    private static String ago(long hours) {
        return NOW.minusHours(hours).toOffsetDateTime().toString();
    }

    // ---------------------------------------------------------------- Claude

    @Test
    void claudeWithoutAKeyIsNotSetUpAndOffersSetup() {
        Check c = HealthService.claude(new ClaudeFacts(false, null, null, null), NOW);
        assertThat(c.state()).isEqualTo(State.off);
        assertThat(c.summary()).startsWith("Not set up yet");
        assertThat(c.fix().target()).isEqualTo("#settings");
    }

    @Test
    void claudeOutOfCreditsIsAProblemLinkingToSpending() {
        Check c = HealthService.claude(new ClaudeFacts(true, ago(26), null, null), NOW);
        assertThat(c.state()).isEqualTo(State.problem);
        assertThat(c.summary()).isEqualTo("Ran out of credits yesterday, so new messages aren't being read.");
        assertThat(c.fix().kind()).isEqualTo(FixKind.link);
        assertThat(c.fix().target()).isEqualTo("#settings?spend");
    }

    @Test
    void aRejectedKeyIsAProblemButAOneOffErrorIsOnlyAWarning() {
        Check bad = HealthService.claude(new ClaudeFacts(true, null, null, ago(2) + " Claude API error (401): invalid x-api-key"), NOW);
        assertThat(bad.state()).isEqualTo(State.problem);
        assertThat(bad.headline()).isEqualTo("Claude's key stopped working");
        assertThat(bad.fix().label()).isEqualTo("Update key");

        Check blip = HealthService.claude(new ClaudeFacts(true, null, null, ago(2) + " Claude API error (529): overloaded"), NOW);
        assertThat(blip.state()).isEqualTo(State.warn);
        assertThat(blip.fix().kind()).isEqualTo(FixKind.post);
    }

    @Test
    void lowCreditsWarn() {
        Check c = HealthService.claude(new ClaudeFacts(true, null, new ClaudeSpend.Alert("LOW",
                "Claude credits are running low: about $3.20 left. Top up soon in the Claude Console."), null), NOW);
        assertThat(c.state()).isEqualTo(State.warn);
        assertThat(c.summary()).startsWith("Credits are running low: about $3.20 left.");
    }

    @Test
    void healthyClaudeHasNoFix() {
        Check c = HealthService.claude(new ClaudeFacts(true, null, null, null), NOW);
        assertThat(c.state()).isEqualTo(State.ok);
        assertThat(c.fix()).isNull();
    }

    // ---------------------------------------------------------------- Gmail and Instagram

    @Test
    void connectedGmailSaysWhenItLastChecked() {
        Check c = HealthService.gmail(new ChannelFacts(true, true, ago(1), null, null, null), NOW);
        assertThat(c.state()).isEqualTo(State.ok);
        assertThat(c.summary()).isEqualTo("Connected, last checked 2:00 PM.");
        assertThat(c.lastCheckedAt()).isNotNull();
        assertThat(c.fix()).isNull();
    }

    @Test
    void anExpiredGoogleSignInNeedsReconnectingThroughGoogle() {
        // Google signs a "Testing" app out every 7 days: the refresh token comes back invalid_grant.
        String monday = NOW.minusDays(3).toOffsetDateTime().toString();
        Check c = HealthService.gmail(new ChannelFacts(true, true, ago(80),
                monday + " invalid_grant: Token has been expired or revoked.", monday, null), NOW);
        assertThat(c.state()).isEqualTo(State.problem);
        assertThat(c.headline()).isEqualTo("Gmail needs reconnecting");
        assertThat(c.summary()).isEqualTo("Stopped connecting on Monday. Sign in to Google again.");
        assertThat(c.fix().kind()).isEqualTo(FixKind.oauth);
        assertThat(c.fix().target()).isEqualTo("/oauth/google/start");
    }

    @Test
    void aShortOutageIsOnlyAWarningButALongOneIsAProblem() {
        Check blip = HealthService.gmail(new ChannelFacts(true, true, ago(1), ago(0) + " connect timed out", ago(0), null), NOW);
        assertThat(blip.state()).isEqualTo(State.warn);
        assertThat(blip.fix().target()).isEqualTo("/api/sync");

        Check down = HealthService.gmail(new ChannelFacts(true, true, ago(5), ago(0) + " connect timed out", ago(5), null), NOW);
        assertThat(down.state()).isEqualTo(State.problem);
        assertThat(down.summary()).contains("since 10:00 AM");
    }

    @Test
    void aStaleSyncWithoutErrorsGetsACheckNowButton() {
        Check c = HealthService.gmail(new ChannelFacts(true, true, ago(30), null, null, null), NOW);
        assertThat(c.state()).isEqualTo(State.warn);
        assertThat(c.summary()).isEqualTo("Hasn't checked for new mail since yesterday at 9:00 AM.");
        assertThat(c.fix().label()).isEqualTo("Check now");
    }

    @Test
    void instagramConnectedWithAPastedTokenReconnectsThroughSettings() {
        Check c = HealthService.instagram(new ChannelFacts(true, false, ago(1), null, null, ago(24)), NOW);
        assertThat(c.state()).isEqualTo(State.problem);
        assertThat(c.headline()).isEqualTo("Instagram needs reconnecting");
        assertThat(c.fix().kind()).isEqualTo(FixKind.link);
        assertThat(c.fix().target()).isEqualTo("#settings");
    }

    @Test
    void aChannelThatIsNotConnectedIsOffWithASetupButton() {
        Check c = HealthService.instagram(new ChannelFacts(false, false, null, null, null, null), NOW);
        assertThat(c.state()).isEqualTo(State.off);
        assertThat(c.summary()).isEqualTo("Not set up yet.");
        assertThat(c.fix().label()).isEqualTo("Set up");
    }

    // ---------------------------------------------------------------- backups

    private static AutoBackupService.Status backup(boolean enabled, String lastAt, String error) {
        return new AutoBackupService.Status(enabled, enabled, "/b", "/b", lastAt, null, 10, error, 14);
    }

    @Test
    void backupsOffIsAWarningThatOpensTheBackupCard() {
        Check c = HealthService.backups(backup(false, null, null), NOW);
        assertThat(c.state()).isEqualTo(State.warn);
        assertThat(c.fix().target()).isEqualTo("#settings?backup");
    }

    @Test
    void aFailedBackupIsAProblemWithBackUpNow() {
        Check c = HealthService.backups(backup(true, ago(37), "Couldn't save the backup: disk full"), NOW);
        assertThat(c.state()).isEqualTo(State.problem);
        assertThat(c.summary()).isEqualTo("The last backup didn't save. The newest good one is from yesterday at 2:00 AM.");
        assertThat(c.fix().kind()).isEqualTo(FixKind.post);
        assertThat(c.fix().target()).isEqualTo("/api/backup/auto/run");
    }

    @Test
    void backupsAreFineWhenRecentAndBehindWhenOld() {
        assertThat(HealthService.backups(backup(true, ago(13), null), NOW).summary()).isEqualTo("Saved today at 2:00 AM.");
        Check old = HealthService.backups(backup(true, ago(24 * 4), null), NOW);
        assertThat(old.state()).isEqualTo(State.warn);
        assertThat(old.summary()).isEqualTo("The last backup was on Sunday.");
    }

    // ---------------------------------------------------------------- updates

    private static UpdateService.Status update(String latest, boolean available, boolean canInstall, OffsetDateTime checkedAt,
                                               String error, String installState) {
        return new UpdateService.Status("1.31.0", latest, available, null, null, "https://example.test/release", canInstall,
                null, checkedAt, error, installState, false, true, true);
    }

    @Test
    void anAvailableUpdateOffersUpdateNowOrDownload() {
        Check c = HealthService.updates(update("1.32.0", true, true, NOW.toOffsetDateTime(), null, null), NOW);
        assertThat(c.state()).isEqualTo(State.warn);
        assertThat(c.summary()).isEqualTo("Version 1.32.0 is ready (you have 1.31.0).");
        assertThat(c.fix().kind()).isEqualTo(FixKind.install);

        Check mac = HealthService.updates(update("1.32.0", true, false, NOW.toOffsetDateTime(), null, null), NOW);
        assertThat(mac.fix().kind()).isEqualTo(FixKind.link);
        assertThat(mac.fix().target()).isEqualTo("https://example.test/release");
    }

    @Test
    void aFailedInstallIsAProblem() {
        Check c = HealthService.updates(update("1.32.0", true, true, NOW.toOffsetDateTime(), null,
                "Update failed: checksum mismatch. Nothing was changed; you can try again."), NOW);
        assertThat(c.state()).isEqualTo(State.problem);
        assertThat(c.fix().label()).isEqualTo("Try again");
    }

    @Test
    void upToDateAndCheckErrors() {
        Check ok = HealthService.updates(update("1.31.0", false, false, NOW.minusHours(2).toOffsetDateTime(), null, null), NOW);
        assertThat(ok.state()).isEqualTo(State.ok);
        assertThat(ok.summary()).isEqualTo("Up to date (version 1.31.0), checked 1:00 PM.");

        Check err = HealthService.updates(update(null, false, false, NOW.toOffsetDateTime(), "Couldn't reach GitHub: timeout", null), NOW);
        assertThat(err.state()).isEqualTo(State.warn);
        assertThat(err.fix().target()).isEqualTo("/api/updates/check");
    }

    // ---------------------------------------------------------------- the rule every row follows

    @Test
    void everyProblemAndWarningHasExactlyOneFix() {
        ChannelFacts[] channels = {
                new ChannelFacts(true, true, ago(1), ago(0) + " x", ago(0), null),
                new ChannelFacts(true, true, ago(9), ago(0) + " x", ago(3), null),
                new ChannelFacts(true, true, ago(9), ago(0) + " 401 Unauthorized", ago(3), null),
                new ChannelFacts(true, true, ago(30), null, null, null),
                new ChannelFacts(true, false, ago(1), null, null, ago(1)) };
        for (ChannelFacts f : channels) {
            for (Check c : new Check[] {HealthService.gmail(f, NOW), HealthService.instagram(f, NOW)}) {
                assertThat(c.state()).isIn(State.warn, State.problem);
                assertThat(c.fix()).as(c.summary()).isNotNull();
                assertThat(c.fix().label()).isNotBlank();
            }
        }
    }

    @Test
    void plainWordsForTimes() {
        assertThat(HealthService.time(NOW.minusMinutes(30).toOffsetDateTime(), NOW)).isEqualTo("2:30 PM");
        assertThat(HealthService.day(NOW.minusDays(2).toOffsetDateTime(), NOW)).isEqualTo("on Tuesday");
        assertThat(HealthService.day(NOW.minusDays(9).toOffsetDateTime(), NOW)).isEqualTo("on Sep 29");
        // Stored in UTC, shown in her time zone.
        assertThat(HealthService.time(OffsetDateTime.parse("2026-10-08T13:30:00Z"), NOW)).isEqualTo("9:30 AM");
    }
}
