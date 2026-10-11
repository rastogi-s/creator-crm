package com.creatorcrm.dms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import static com.creatorcrm.FakeLlm.analysis;

import com.creatorcrm.FakeLlm;
import com.creatorcrm.ingest.IngestionService;
import com.creatorcrm.llm.Intent;
import com.creatorcrm.domain.Conversation;
import com.creatorcrm.domain.Enums.Platform;
import com.creatorcrm.repo.ConversationRepo;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;

/** DMs from her Instagram download: who's who, the 90-day cut-off, odd text encoding, and re-imports. */
@SpringBootTest
@ActiveProfiles("test")
class DmExportImportIntegrationTest {

    @TestConfiguration
    static class Config {
        @Bean
        @Primary
        FakeLlm fakeLlm() {
            return new FakeLlm();
        }
    }

    @Autowired FakeLlm llm;
    @Autowired IngestionService ingestion;
    @Autowired DmExportImport exportImport;
    @Autowired DmInbox inbox;
    @Autowired ConversationRepo conversations;

    private static String chat(String other, long... msAgoMine) {
        long now = System.currentTimeMillis();
        // newest first, as Instagram writes them
        return "{\"participants\":[{\"name\":\"" + other + " Shop\"},{\"name\":\"Bhavu Caf\\u00c3\\u00a9\"}],\"messages\":["
                + "{\"sender_name\":\"Bhavu Caf\\u00c3\\u00a9\",\"timestamp_ms\":" + (now - 3_600_000L) + ",\"content\":\"Sounds fun \\u00e2\\u0080\\u0094 rates?\"},"
                + "{\"sender_name\":\"" + other + " Shop\",\"timestamp_ms\":" + (now - 7_200_000L) + ",\"content\":\"Hi! Paid collab?\"},"
                + "{\"sender_name\":\"" + other + " Shop\",\"timestamp_ms\":" + (now - 200L * 86_400_000L) + ",\"content\":\"old hello\"}"
                + "],\"title\":\"" + other + " Shop\",\"thread_path\":\"inbox/" + other + "_123\"}";
    }

    /** Claude's answers for the messages an import is about to bring in (it reads them in the background). */
    private void claudeWillRead(int messages) {
        llm.failWith = null;
        llm.next.clear();
        for (int i = 0; i < messages; i++) llm.next.add(analysis(Intent.NOT_BRAND_RELATED, "", false, List.of()));
    }

    /** Lets the background read finish, so nothing is left waiting for other tests. */
    private void claudeFinishes() throws InterruptedException {
        for (int i = 0; i < 100 && ingestion.status().waitingForAi() > 0; i++) {
            ingestion.processPendingAsync();
            Thread.sleep(100);
        }
        assertThat(ingestion.status().waitingForAi()).isZero();
        llm.next.clear();
    }

    @Test
    void importsHerChatsWithUsernamesAndSkipsOldOnesAndRepeats() throws Exception {
        claudeWillRead(4);
        String a = "brand" + UUID.randomUUID().toString().substring(0, 6);
        String b = "shop" + UUID.randomUUID().toString().substring(0, 6);
        List<DmExportImport.ExportFile> files = List.of(
                new DmExportImport.ExportFile("export/your_instagram_activity/messages/inbox/" + a + "_123/message_1.json", chat(a)),
                new DmExportImport.ExportFile("export/your_instagram_activity/messages/message_requests/" + b + "_456/message_1.json", chat(b)),
                new DmExportImport.ExportFile("export/media/photo.jpg", "not json"));

        DmExportImport.Result r = exportImport.importFiles(files, 90);

        assertThat(r.you()).isEqualTo("Bhavu Café");
        assertThat(r.chats()).isEqualTo(2);
        assertThat(r.newMessages()).isEqualTo(4);
        assertThat(r.olderSkipped()).isEqualTo(2);
        Conversation c = conversations.findByPlatformAndExternalId(Platform.INSTAGRAM, "igx:" + a + "_123").orElseThrow();
        DmInbox.Row row = inbox.list().stream().filter(x -> x.id().equals(c.id)).findFirst().orElseThrow();
        assertThat(row.handle()).isEqualTo(a);
        assertThat(row.lastFromMe()).isTrue();
        assertThat(row.lastText()).isEqualTo("Sounds fun — rates?");

        claudeFinishes();
        assertThat(exportImport.importFiles(files, 90).newMessages()).isZero();
    }

    @Test
    void saysWhatToPickWhenTheFolderHasNoMessages() {
        assertThatThrownBy(() -> exportImport.importFiles(List.of(new DmExportImport.ExportFile("x/media/a.jpg", "")), 90))
                .hasMessageContaining("no Instagram messages");
        String one = "solo" + UUID.randomUUID().toString().substring(0, 6);
        assertThatThrownBy(() -> exportImport.importFiles(List.of(
                new DmExportImport.ExportFile("messages/inbox/" + one + "_1/message_1.json", chat(one))), 90))
                .hasMessageContaining("Couldn't tell which name is yours");
    }

    @Test
    void herNameComesFromHerProfileFileWhenItsThere() throws Exception {
        claudeWillRead(2);
        String one = "solo" + UUID.randomUUID().toString().substring(0, 6);
        DmExportImport.Result r = exportImport.importFiles(List.of(
                new DmExportImport.ExportFile("personal_information/personal_information/personal_information.json",
                        "{\"profile_user\":[{\"string_map_data\":{\"Name\":{\"value\":\"Bhavu Caf\\u00c3\\u00a9\"}}}]}"),
                new DmExportImport.ExportFile("messages/inbox/" + one + "_1/message_1.json", chat(one))), 90);
        assertThat(r.you()).isEqualTo("Bhavu Café");
        assertThat(r.newMessages()).isEqualTo(2);
        claudeFinishes();
    }
}
