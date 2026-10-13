package com.creatorcrm.ideas;

import static com.creatorcrm.FakeLlm.analysis;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;

import com.creatorcrm.FakeLlm;
import com.creatorcrm.channels.NormalizedMessage;
import com.creatorcrm.channels.gmail.GmailConnector;
import com.creatorcrm.domain.Enums.Direction;
import com.creatorcrm.domain.Enums.Platform;
import com.creatorcrm.domain.Enums.TaskStatus;
import com.creatorcrm.domain.Enums.TaskType;
import com.creatorcrm.domain.Message;
import com.creatorcrm.domain.Opportunity;
import com.creatorcrm.domain.Task;
import com.creatorcrm.ingest.IngestionService;
import com.creatorcrm.llm.Intent;
import com.creatorcrm.llm.LlmException;
import com.creatorcrm.repo.ConversationRepo;
import com.creatorcrm.repo.MessageRepo;
import com.creatorcrm.repo.OpportunityRepo;
import com.creatorcrm.repo.TaskRepo;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/** A brand's brief end to end: its email and files are read, Claude writes ideas, and she can refresh them. */
@SpringBootTest
@ActiveProfiles("test")
class ContentIdeasIntegrationTest {

    @TestConfiguration
    static class Config {
        @Bean
        @Primary
        FakeLlm fakeLlm() {
            return new FakeLlm();
        }
    }

    @MockitoSpyBean GmailConnector gmail;
    @Autowired FakeLlm llm;
    @Autowired ContentIdeasService ideas;
    @Autowired IngestionService ingestion;
    @Autowired ConversationRepo conversations;
    @Autowired OpportunityRepo opportunities;
    @Autowired MessageRepo messages;
    @Autowired TaskRepo tasks;

    private final java.util.function.Function<com.creatorcrm.llm.ContentIdeasInput, com.creatorcrm.llm.ContentIdeaList> normal = new FakeLlm().ideas;

    @BeforeEach
    void reset() throws Exception {
        llm.next.clear();
        llm.ideasCalls = 0;
        llm.lastIdeasInput = null;
        llm.ideas = normal;
        doReturn(true).when(gmail).isConnected();
        doReturn(Optional.empty()).when(gmail).pushDraft(any());
        doReturn(List.of()).when(gmail).briefAttachments(anyString(), anyInt(), anyLong());
        for (Message m : messages.findByAiProcessedFalseAndFilteredReasonIsNullOrderBySentAtAsc()) {
            m.filteredReason = "left over from another test";
            messages.save(m);
        }
    }

    @AfterEach
    void restore() {
        llm.ideas = normal;
    }

    private Opportunity email(Intent intent, String externalId, String body, OffsetDateTime sentAt) {
        String thread = "brief" + UUID.randomUUID().toString().substring(0, 8);
        llm.next.add(analysis(intent, "Brand " + thread, false, List.of()));
        ingestion.store(List.of(new NormalizedMessage(Platform.EMAIL, externalId, thread, Direction.INBOUND,
                "sam@" + thread + ".test", "Sam", "me@creator.test", "sam@" + thread + ".test", "Creative brief", body,
                "<" + UUID.randomUUID() + "@mail>", "", sentAt, false)));
        ingestion.processPending();
        Long conv = conversations.findByPlatformAndExternalId(Platform.EMAIL, thread).orElseThrow().id;
        return opportunities.findFirstByConversationIdOrderByIdDesc(conv).orElseThrow();
    }

    private Optional<Task> planTask(Opportunity o, TaskStatus status) {
        return tasks.findByOpportunityIdAndStatus(o.id, status).stream().filter(t -> t.type == TaskType.PLAN_CONTENT).findFirst();
    }

    @Test
    void aBriefWithAWordFileGetsIdeasByItself() throws Exception {
        String id = UUID.randomUUID().toString();
        doReturn(List.of(new GmailConnector.FileAttachment("Cozy brief.docx", BriefFilesTest.docx("Show the lamp in the first 3 seconds", "Tag @novanest"))))
                .when(gmail).briefAttachments(eq(id), anyInt(), anyLong());
        Opportunity o = email(Intent.CONTENT_BRIEF, id, "Hi! Our brief is attached. Draft due Friday.", OffsetDateTime.now().minusHours(1));

        assertThat(llm.ideasCalls).isEqualTo(1);
        assertThat(llm.lastIdeasInput.guidelines()).startsWith("<untrusted_guidelines>")
                .contains("Draft due Friday").contains("Show the lamp in the first 3 seconds").contains("Cozy brief.docx");
        assertThat(llm.lastIdeasInput.profile()).contains("Name:");
        ContentIdeasService.View v = ideas.latest(o.id).orElseThrow();
        assertThat(v.status()).isEqualTo("READY");
        assertThat(v.source()).isEqualTo("EMAIL");
        assertThat(v.fileNames()).isEqualTo("Cozy brief.docx");
        assertThat(v.ideas()).singleElement().satisfies(i -> assertThat(i.hook()).isEqualTo("Watch this corner change"));
        assertThat(v.mustInclude()).containsExactly("Tag @brand");
        assertThat(v.error()).isNull();

        // Refresh reuses the brief and passes her note; nothing is downloaded again.
        ContentIdeasService.View again = ideas.generate(o.id, null, "funnier please");
        assertThat(llm.ideasCalls).isEqualTo(2);
        assertThat(llm.lastIdeasInput.note()).isEqualTo("funnier please");
        assertThat(llm.lastIdeasInput.guidelines()).contains("Show the lamp in the first 3 seconds");
        assertThat(again.fileNames()).isEqualTo("Cozy brief.docx");
        assertThat(again.note()).isEqualTo("funnier please");
    }

    @Test
    void aBriefBehindALinkSaysToPasteIt() {
        Opportunity o = email(Intent.CONTENT_BRIEF, UUID.randomUUID().toString(),
                "Here's the brief: https://drive.google.com/file/d/abc/view", OffsetDateTime.now().minusHours(1));
        ContentIdeasService.View v = ideas.latest(o.id).orElseThrow();
        assertThat(v.status()).isEqualTo("READY");
        assertThat(v.error()).contains("Google Drive").contains("Paste its text");

        assertThatThrownBy(() -> ideas.generate(o.id, "short", null)).hasMessageContaining("whole brief");
        ContentIdeasService.View pasted = ideas.generate(o.id, "Key message: our throws make any corner cozy. Tag @novanest.", null);
        assertThat(pasted.source()).isEqualTo("PASTED");
        assertThat(pasted.error()).isNull();
        assertThat(llm.lastIdeasInput.guidelines()).contains("our throws make any corner cozy");
    }

    @Test
    void whenClaudeFailsAToDoWaitsUntilIdeasExist() {
        llm.ideas = in -> { throw new LlmException("Claude API error (529): overloaded"); };
        Opportunity o = email(Intent.CONTENT_BRIEF, UUID.randomUUID().toString(), "Brief: show it in your morning routine.",
                OffsetDateTime.now().minusHours(1));
        assertThat(ideas.latest(o.id).orElseThrow().status()).isEqualTo("FAILED");
        assertThat(planTask(o, TaskStatus.OPEN)).get().satisfies(t -> assertThat(t.description).contains("Refresh ideas"));

        llm.ideas = normal;
        assertThat(ideas.generate(o.id, null, null).status()).isEqualTo("READY");
        assertThat(planTask(o, TaskStatus.OPEN)).isEmpty();
        assertThat(planTask(o, TaskStatus.DONE)).get().satisfies(t -> assertThat(t.autoClosedReason).contains("Content ideas are ready"));
    }

    @Test
    void shippingEmailsCountOnlyWhenTheyCarryTheBrief() {
        Opportunity plain = email(Intent.PRODUCT_SHIPPED, UUID.randomUUID().toString(), "Your package is on its way! Tracking: 123.",
                OffsetDateTime.now().minusHours(1));
        assertThat(ideas.latest(plain.id)).isEmpty();
        Opportunity withBrief = email(Intent.PRODUCT_SHIPPED, UUID.randomUUID().toString(),
                "Your package shipped today. The content guidelines are below: mention the soft texture.", OffsetDateTime.now().minusHours(1));
        assertThat(ideas.latest(withBrief.id)).get().satisfies(v -> assertThat(v.status()).isEqualTo("READY"));
    }

    @Test
    void oldBriefsInAnImportCostNothingUntilAsked() {
        Opportunity o = email(Intent.CONTENT_BRIEF, UUID.randomUUID().toString(), "Brief: film it at golden hour.",
                OffsetDateTime.now().minusDays(120));
        assertThat(llm.ideasCalls).isZero();
        assertThat(ideas.latest(o.id)).isEmpty();
        // Get ideas finds the brief email in the conversation.
        ContentIdeasService.View v = ideas.generate(o.id, null, null);
        assertThat(v.source()).isEqualTo("EMAIL");
        assertThat(llm.lastIdeasInput.guidelines()).contains("film it at golden hour");
    }
}
