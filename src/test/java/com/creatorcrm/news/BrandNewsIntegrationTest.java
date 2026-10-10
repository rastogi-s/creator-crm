package com.creatorcrm.news;

import static org.assertj.core.api.Assertions.assertThat;

import com.creatorcrm.FakeLlm;
import com.creatorcrm.domain.Brand;
import com.creatorcrm.domain.BrandNews;
import com.creatorcrm.domain.Conversation;
import com.creatorcrm.domain.Enums.Compensation;
import com.creatorcrm.domain.Enums.Direction;
import com.creatorcrm.domain.Enums.OpportunityStatus;
import com.creatorcrm.domain.Enums.OpportunityType;
import com.creatorcrm.domain.Enums.Origin;
import com.creatorcrm.domain.Enums.Platform;
import com.creatorcrm.domain.Message;
import com.creatorcrm.domain.Opportunity;
import com.creatorcrm.llm.NewsFound;
import com.creatorcrm.llm.NewsSort;
import com.creatorcrm.repo.BrandNewsRepo;
import com.creatorcrm.repo.BrandRepo;
import com.creatorcrm.repo.ConversationRepo;
import com.creatorcrm.repo.MessageRepo;
import com.creatorcrm.repo.OpportunityRepo;
import com.creatorcrm.settings.SettingsService;
import java.net.URI;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;

/** Brand news: what's collected from her inbox and the news index, how Claude's sort is applied, and what's used. */
@SpringBootTest
@ActiveProfiles("test")
class BrandNewsIntegrationTest {

    /** The news index answers with whatever the test put in {@link #articles}, never the internet. */
    static String articles = "{\"articles\":[]}";

    @TestConfiguration
    static class Config {
        @Bean
        @Primary
        FakeLlm fakeLlm() {
            return new FakeLlm();
        }

        @Bean
        @Primary
        NewsIndex quietNewsIndex() {
            return new NewsIndex() {
                @Override
                protected String fetch(URI uri) {
                    return articles;
                }

                @Override
                protected void pause(long ms) {}
            };
        }
    }

    @Autowired FakeLlm llm;
    @Autowired BrandNewsService news;
    @Autowired BrandNewsRepo repo;
    @Autowired BrandRepo brands;
    @Autowired ConversationRepo conversations;
    @Autowired MessageRepo messages;
    @Autowired OpportunityRepo opportunities;
    @Autowired SettingsService settings;

    @BeforeEach
    void reset() {
        articles = "{\"articles\":[]}";
        llm.nextNewsFound = new NewsFound(List.of());
        llm.newsSearches = 0;
        llm.newsSort = in -> new NewsSort(in.items().stream().map(i -> i.title().toLowerCase().contains("sale")
                ? new NewsSort.Verdict(i.id(), false, BrandNews.Kind.OTHER, "", "", 0)
                : new NewsSort.Verdict(i.id(), true, BrandNews.Kind.LAUNCH, "Launching " + i.title(), "A first-look Reel", 82)).toList());
        settings.update(Map.of(SettingsService.BRAND_NEWS, "true", SettingsService.BRAND_NEWS_WEB_SEARCH, "true"));
    }

    private Brand brand() {
        Brand b = new Brand();
        b.name = "Glow" + UUID.randomUUID().toString().substring(0, 8);
        b.nameKey = Brand.key(b.name);
        b.contactEmail = "noor@" + b.nameKey + ".test";
        b.createdAt = OffsetDateTime.now();
        return brands.save(b);
    }

    private Message mail(Brand b, String from, String subject, int daysAgo) {
        Conversation c = new Conversation();
        c.platform = Platform.EMAIL;
        c.externalId = UUID.randomUUID().toString();
        c.counterparty = from;
        c.subject = subject;
        c.createdAt = OffsetDateTime.now().minusDays(daysAgo);
        c = conversations.save(c);
        Message m = new Message();
        m.conversationId = c.id;
        m.externalId = "email:" + UUID.randomUUID();
        m.direction = Direction.INBOUND;
        m.sender = from;
        m.subject = subject;
        m.content = subject + ". Read all about it.";
        m.sentAt = OffsetDateTime.now().minusDays(daysAgo);
        m.filteredReason = "bulk/newsletter mail";
        return messages.save(m);
    }

    private List<BrandNews> stored(Brand b) {
        return repo.findByBrandId(b.id);
    }

    @Test
    void collectsTheBrandsOwnRecentMailAndNewsThenSortsIt() {
        Brand b = brand();
        String domain = b.nameKey + ".test";
        Message launch = mail(b, "news@mail." + domain, "Meet Barrier Mist", 3);
        mail(b, "hello@" + domain, "Autumn sale: 20% off", 5);
        mail(b, "news@" + domain, "Summer collection", 70);                 // too old
        mail(b, "promo@someoneelse.test", "Meet Barrier Mist", 2);            // another brand
        Message dealMail = mail(b, "noor@" + domain, "Your invoice", 2);      // part of a deal, not news
        Opportunity o = new Opportunity();
        o.brandId = b.id;
        o.conversationId = dealMail.conversationId;
        o.origin = Origin.INBOUND;
        o.type = OpportunityType.PAID;
        o.compensation = Compensation.PAID;
        o.status = OpportunityStatus.CLOSED;
        o.createdAt = OffsetDateTime.now();
        o.updatedAt = o.createdAt;
        opportunities.save(o);
        articles = "{\"articles\":[{\"url\":\"https://trade.test/" + b.nameKey + "\",\"title\":\"" + b.name
                + " opens its first store\",\"seendate\":\"" + OffsetDateTime.now().minusDays(1).format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'"))
                + "\",\"domain\":\"trade.test\"}]}";

        BrandNewsService.CheckResult r = news.check(b.id);

        assertThat(r.added()).isEqualTo(3);
        assertThat(stored(b)).extracting(n -> n.title)
                .containsExactlyInAnyOrder("Meet Barrier Mist", "Autumn sale: 20% off", b.name + " opens its first store");
        BrandNews mist = stored(b).stream().filter(n -> n.title.equals("Meet Barrier Mist")).findFirst().orElseThrow();
        assertThat(mist.source).isEqualTo(BrandNews.Source.EMAIL);
        assertThat(mist.messageId).isEqualTo(launch.id);
        assertThat(mist.kind).isEqualTo(BrandNews.Kind.LAUNCH);
        assertThat(mist.headline).isEqualTo("Launching Meet Barrier Mist");
        assertThat(mist.fit).isEqualTo(82);
        BrandNews sale = stored(b).stream().filter(n -> n.title.startsWith("Autumn sale")).findFirst().orElseThrow();
        assertThat(sale.hidden).isTrue();
        assertThat(sale.kind).isEqualTo(BrandNews.Kind.OTHER);
        assertThat(stored(b).stream().filter(n -> n.source == BrandNews.Source.NEWS)).singleElement()
                .satisfies(n -> assertThat(n.url).isEqualTo("https://trade.test/" + b.nameKey));
        assertThat(llm.newsSearches).isZero(); // the free sources had news: no paid web search
        assertThat(brands.findById(b.id).orElseThrow().newsCheckedAt).isNotNull();

        // Found again next time: nothing new is stored twice
        assertThat(news.check(b.id).added()).isZero();
        // Checked today: the morning run leaves it alone for a week
        assertThat(news.refreshDue(List.of(b.id))).isZero();
    }

    @Test
    void bestIsTheFreshestOnNicheUnusedItem() {
        Brand b = brand();
        mail(b, "news@" + b.nameKey + ".test", "Meet Barrier Mist", 3);
        news.check(b.id);
        BrandNews best = news.best(b.id, settings.today()).orElseThrow();
        assertThat(best.title).isEqualTo("Meet Barrier Mist");

        news.markUsed(best, 12345L);
        assertThat(news.best(b.id, settings.today())).isEmpty(); // each item opens one re-pitch
        assertThat(news.forDraft(12345L)).get().extracting(BrandNewsService.View::headline).isEqualTo("Launching Meet Barrier Mist");

        Brand other = brand();
        mail(other, "news@" + other.nameKey + ".test", "Meet Cloud Balm", 2);
        news.check(other.id);
        BrandNews balm = news.best(other.id, settings.today()).orElseThrow();
        news.setHidden(balm.id, true);
        assertThat(news.best(other.id, settings.today())).isEmpty(); // she hid it
        settings.update(Map.of(SettingsService.BRAND_NEWS, "false"));
        news.setHidden(balm.id, false);
        assertThat(news.best(other.id, settings.today())).isEmpty(); // news switched off
    }

    @Test
    void searchesTheWebOnlyWhenNothingFreeTurnedUpAndSheAllowsIt() {
        Brand b = brand();
        String today = settings.today().toString();
        llm.nextNewsFound = new NewsFound(List.of(
                new NewsFound.Item("Cloud Balm launches 1 November", "https://press.test/cloud-balm", today, "A new balm."),
                new NewsFound.Item("No link", "", today, "Should be dropped."),
                new NewsFound.Item("Old news", "https://press.test/old", settings.today().minusDays(90).toString(), "Too old.")));

        news.check(b.id);

        assertThat(llm.newsSearches).isEqualTo(1);
        assertThat(stored(b)).singleElement().satisfies(n -> {
            assertThat(n.source).isEqualTo(BrandNews.Source.WEB_SEARCH);
            assertThat(n.url).isEqualTo("https://press.test/cloud-balm");
        });

        Brand quiet = brand();
        settings.update(Map.of(SettingsService.BRAND_NEWS_WEB_SEARCH, "false"));
        news.check(quiet.id);
        assertThat(llm.newsSearches).isEqualTo(1);
        assertThat(stored(quiet)).isEmpty();
    }
}
