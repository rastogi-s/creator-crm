package com.creatorcrm.diagnostics;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class RedactorTest {
    final Redactor r = new Redactor(List.of("supersecretvalue123", "priya.creates"));

    @Test
    void removesPersonalDataAndKeepsTheUsefulParts() {
        String log = """
                2026-10-04 08:58:17.123 ERROR [sync] GmailConnector - Sync failed for priya@gmail.com (call +91 98765 43210)
                Authorization: Bearer ya29.a0AfH6SMBxyzxyzxyzxyz and key sk-ant-api03-abcdefghijkl
                GET https://graph.instagram.com/me?access_token=IGQWRabcdefghijklmnopqrstuv&fields=id from @priya.creates
                token=abcd1234efgh refresh_token: "1//0gabcdefghijklmnopqrstuvwxyz" ghp_abcdefghijklmnopqrstuvwxyz123456
                C:\\Users\\Priya\\.creator-crm\\logs\\creator-crm.log and supersecretvalue123
                \tat com.creatorcrm.channels.gmail.GmailConnector.fetch(GmailConnector.java:120)
                """;
        String out = r.redact(log);
        assertThat(out).doesNotContain("priya@gmail.com", "98765", "ya29.", "sk-ant-", "IGQWR", "abcd1234efgh",
                "1//0g", "ghp_", "Priya", "supersecretvalue123", "priya.creates");
        assertThat(out).contains("2026-10-04 08:58:17.123 ERROR", "[email]", "[phone]",
                "com.creatorcrm.channels.gmail.GmailConnector.fetch(GmailConnector.java:120)",
                "https://graph.instagram.com/me?[query]", "C:\\Users\\[user]\\.creator-crm");
    }

    @Test
    void removesTheFirstRunSetupCode() {
        String out = r.redact("   one-time setup code to create your admin account:\n\n       Un4SI42uJRxD\n  =====");
        assertThat(out).doesNotContain("Un4SI42uJRxD").contains("[redacted]");
    }

    @Test
    void knownValuesOnlyMatchWholeWords() {
        Redactor named = new Redactor(List.of("Creator"));
        assertThat(named.redact("at com.creatorcrm.Foo by Creator")).isEqualTo("at com.creatorcrm.Foo by [redacted]");
    }

    @Test
    void leavesOrdinaryTextAlone() {
        String s = "Update check failed: GitHub answered 503 at 2026-10-04 08:58:17, deal #1234 status NEGOTIATING";
        assertThat(r.redact(s)).isEqualTo(s);
    }
}
