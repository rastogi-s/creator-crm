package com.creatorcrm.practice;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PracticeFenceTest {

    @Test
    void keepsPracticeAwayFromRealAccountsBackupsAndUpdates() {
        assertThat(PracticeFence.blocked("POST", "/oauth/google/start")).isTrue();
        assertThat(PracticeFence.blocked("PUT", "/api/settings/credentials")).isTrue();
        assertThat(PracticeFence.blocked("POST", "/api/backup/auto/run")).isTrue();
        assertThat(PracticeFence.blocked("POST", "/api/backup/restore")).isTrue();
        assertThat(PracticeFence.blocked("POST", "/api/updates/install")).isTrue();
        assertThat(PracticeFence.blocked("PUT", "/api/desktop/start-with-windows")).isTrue();
        assertThat(PracticeFence.blocked("POST", "/api/diagnostics/report")).isTrue();
    }

    @Test
    void letsHerDoEverythingElse() {
        assertThat(PracticeFence.blocked("GET", "/api/settings")).isFalse();
        assertThat(PracticeFence.blocked("GET", "/api/updates")).isFalse();
        assertThat(PracticeFence.blocked("POST", "/api/drafts/3/send")).isFalse();
        assertThat(PracticeFence.blocked("PUT", "/api/settings/preferences")).isFalse();
        assertThat(PracticeFence.blocked("POST", "/api/practice/leave")).isFalse();
    }

    @Test
    void theCopyGetsItsOwnDatabaseWhateverTheRealInstallUses() {
        String[] args = PracticeMode.args(java.nio.file.Path.of("/tmp/creator-crm-practice-1"), "http://localhost:8080/#more");
        assertThat(args).contains("--crm.practice=true", "--server.port=0", "--crm.desktop=false", "--crm.updates.enabled=false",
                "--server.servlet.session.cookie.name=CRM_PRACTICE_SESSION");
        assertThat(args).anyMatch(a -> a.startsWith("--spring.datasource.url=jdbc:h2:file:/tmp/creator-crm-practice-1/"));
    }

    @Test
    void onlyEverDeletesItsOwnFolders(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tmp) throws Exception {
        java.nio.file.Path other = java.nio.file.Files.createDirectories(tmp.resolve("data"));
        java.nio.file.Files.writeString(other.resolve("creator-crm.mv.db"), "real");
        PracticeMode.deleteTree(other);
        assertThat(other.resolve("creator-crm.mv.db")).exists();
    }
}
