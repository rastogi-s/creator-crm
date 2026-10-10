package com.creatorcrm.news;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class NewsIndexTest {

    @Test
    void keepsOnlyArticlesWhoseHeadlineNamesTheBrand() {
        String json = """
                {"articles":[
                 {"url":"https://trade.test/glowberry-mist","title":"Glowberry launches Barrier Mist","seendate":"20261006T120000Z","domain":"trade.test"},
                 {"url":"https://other.test/a","title":"Ten mists to try this autumn","seendate":"20261005T120000Z","domain":"other.test"},
                 {"url":"not a link","title":"Glowberry news","seendate":"20261005T120000Z","domain":"x.test"}]}""";
        List<NewsIndex.Article> out = NewsIndex.parse(json, "Glowberry");
        assertThat(out).singleElement().satisfies(a -> {
            assertThat(a.url()).isEqualTo("https://trade.test/glowberry-mist");
            assertThat(a.site()).isEqualTo("trade.test");
            assertThat(a.seenAt()).isEqualTo(OffsetDateTime.parse("2026-10-06T12:00:00Z"));
        });
        assertThat(NewsIndex.parse("The specified phrase is too short.", "Glowberry")).isEmpty();
    }

    @Test
    void skipsNamesTooShortToSearchAndAsksForAQuotedPhrase() {
        List<URI> asked = new ArrayList<>();
        NewsIndex index = new NewsIndex() {
            @Override
            protected String fetch(URI uri) {
                asked.add(uri);
                return "{\"articles\":[]}";
            }

            @Override
            protected void pause(long ms) {}
        };
        assertThat(index.search("Q&A", 45)).isEmpty();
        assertThat(asked).isEmpty();
        index.search("Petal & Pine", 45);
        assertThat(asked).singleElement().satisfies(u -> {
            assertThat(u.getRawQuery()).contains("query=%22Petal+%26+Pine%22").contains("timespan=45d").contains("mode=artlist");
        });
    }
}
