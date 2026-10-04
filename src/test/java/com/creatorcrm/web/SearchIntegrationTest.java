package com.creatorcrm.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.creatorcrm.domain.Brand;
import com.creatorcrm.domain.Conversation;
import com.creatorcrm.domain.Enums.Compensation;
import com.creatorcrm.domain.Enums.Direction;
import com.creatorcrm.domain.Enums.OpportunityStatus;
import com.creatorcrm.domain.Enums.OpportunityType;
import com.creatorcrm.domain.Enums.Origin;
import com.creatorcrm.domain.Enums.Platform;
import com.creatorcrm.domain.Message;
import com.creatorcrm.domain.Opportunity;
import com.creatorcrm.repo.BrandRepo;
import com.creatorcrm.repo.ConversationRepo;
import com.creatorcrm.repo.MessageRepo;
import com.creatorcrm.repo.OpportunityRepo;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/** The header search: deals by brand or contact, past messages, and nothing for a one-letter query. */
@SpringBootTest
@ActiveProfiles("test")
class SearchIntegrationTest {

    @Autowired SearchController search;
    @Autowired BrandRepo brands;
    @Autowired OpportunityRepo opportunities;
    @Autowired ConversationRepo conversations;
    @Autowired MessageRepo messages;

    private String tag;
    private Opportunity deal;

    @BeforeEach
    void seed() {
        tag = UUID.randomUUID().toString().substring(0, 8);
        Brand b = new Brand();
        b.name = "Lumen " + tag;
        b.nameKey = Brand.key(b.name);
        b.contactName = "Priya";
        b.contactEmail = "priya@lumen-" + tag + ".test";
        b.createdAt = OffsetDateTime.now();
        brands.save(b);

        Conversation c = new Conversation();
        c.brandId = b.id;
        c.platform = Platform.EMAIL;
        c.externalId = "thread-" + tag;
        c.createdAt = OffsetDateTime.now();
        conversations.save(c);

        deal = new Opportunity();
        deal.brandId = b.id;
        deal.conversationId = c.id;
        deal.origin = Origin.INBOUND;
        deal.type = OpportunityType.PAID;
        deal.compensation = Compensation.PAID;
        deal.status = OpportunityStatus.NEGOTIATING;
        deal.campaign = "Autumn serum launch";
        deal.createdAt = OffsetDateTime.now();
        deal.updatedAt = OffsetDateTime.now();
        opportunities.save(deal);

        Message m = new Message();
        m.conversationId = c.id;
        m.externalId = "msg-" + tag;
        m.direction = Direction.INBOUND;
        m.sender = b.contactEmail;
        m.subject = "Collab";
        m.content = "Hi! Our budget is 50% upfront and the usage window is quokka-" + tag + " for 90 days.";
        m.sentAt = OffsetDateTime.now();
        messages.save(m);
    }

    @Test
    void findsTheDealByBrandAndByContactEmail() {
        assertThat(search.search("lumen " + tag)).anySatisfy(h -> {
            assertThat(h.kind()).isEqualTo("DEAL");
            assertThat(h.opportunityId()).isEqualTo(deal.id);
            assertThat(h.detail()).contains("Autumn serum launch").contains("Priya");
        });
        assertThat(search.search("PRIYA@LUMEN-" + tag)).extracting(SearchController.Hit::opportunityId).contains(deal.id);
    }

    @Test
    void findsAPastMessageAndShowsTheTextAroundIt() {
        List<SearchController.Hit> hits = search.search("quokka-" + tag);
        assertThat(hits).singleElement().satisfies(h -> {
            assertThat(h.kind()).isEqualTo("MESSAGE");
            assertThat(h.opportunityId()).isEqualTo(deal.id);
            assertThat(h.detail()).contains("quokka-" + tag);
        });
    }

    @Test
    void percentSignsAreSearchedLiterally() {
        assertThat(search.search("50% upfront")).extracting(SearchController.Hit::kind).contains("MESSAGE");
        assertThat(SearchController.escapeLike("50%_\\")).isEqualTo("50\\%\\_\\\\");
    }

    @Test
    void ignoresQueriesShorterThanTwoLetters() {
        assertThat(search.search(" l ")).isEmpty();
    }

    @Test
    void snippetsCenterOnTheMatch() {
        String text = "x".repeat(300) + " needle " + "y".repeat(300);
        String s = SearchController.snippet(text, "needle");
        assertThat(s).startsWith("…").endsWith("…").contains("needle");
        assertThat(s.length()).isLessThan(150);
    }
}
