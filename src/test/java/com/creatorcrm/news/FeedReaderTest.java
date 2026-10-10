package com.creatorcrm.news;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;

class FeedReaderTest {

    @Test
    void readsRssItemsWithDatesLinksAndPlainSummaries() {
        String rss = """
                <?xml version="1.0" encoding="UTF-8"?>
                <rss version="2.0"><channel><title>Glowberry journal</title>
                <item><title>Meet Barrier Mist</title><link>https://glowberry.test/blogs/journal/barrier-mist</link>
                <pubDate>Tue, 06 Oct 2026 09:00:00 +0000</pubDate>
                <description><![CDATA[<p>Our first <b>face mist</b> lands 1 November.</p>]]></description></item>
                <item><title>Autumn sale</title><link>https://glowberry.test/sale</link>
                <pubDate>Fri, 2 Oct 2026 08:00:00 GMT</pubDate></item>
                <item><title></title><link>https://glowberry.test/empty</link></item>
                </channel></rss>""";
        List<FeedReader.Item> items = FeedReader.parse(rss, "https://glowberry.test/feed");
        assertThat(items).hasSize(2);
        FeedReader.Item mist = items.get(0);
        assertThat(mist.title()).isEqualTo("Meet Barrier Mist");
        assertThat(mist.link()).isEqualTo("https://glowberry.test/blogs/journal/barrier-mist");
        assertThat(mist.published()).isEqualTo(OffsetDateTime.of(2026, 10, 6, 9, 0, 0, 0, ZoneOffset.UTC));
        assertThat(mist.summary()).isEqualTo("Our first face mist lands 1 November.");
        assertThat(items.get(1).published()).isEqualTo(OffsetDateTime.of(2026, 10, 2, 8, 0, 0, 0, ZoneOffset.UTC));
    }

    @Test
    void readsAShopifyAtomProductFeed() {
        String atom = """
                <?xml version="1.0" encoding="UTF-8"?>
                <feed xmlns="http://www.w3.org/2005/Atom" xmlns:s="http://jadedpixel.com/-/spec/shopify">
                <title>Trailmix</title>
                <entry><id>1</id><published>2026-10-04T10:15:00-04:00</published><updated>2026-10-05T10:15:00-04:00</updated>
                <link rel="alternate" type="text/html" href="https://trailmix.test/products/maple-crunch"/>
                <title>Maple Crunch trail bar</title><summary type="html">&lt;p&gt;New for autumn&lt;/p&gt;</summary></entry>
                </feed>""";
        List<FeedReader.Item> items = FeedReader.parse(atom, "https://trailmix.test/collections/all.atom");
        assertThat(items).singleElement().satisfies(i -> {
            assertThat(i.title()).isEqualTo("Maple Crunch trail bar");
            assertThat(i.link()).isEqualTo("https://trailmix.test/products/maple-crunch");
            assertThat(i.published()).isEqualTo(OffsetDateTime.parse("2026-10-04T10:15:00-04:00"));
            assertThat(i.summary()).isEqualTo("New for autumn");
        });
    }

    @Test
    void findsTheFeedsAPageLinksToButNotItsCommentsFeed() {
        var page = Jsoup.parse("<html><head>"
                + "<link rel=\"alternate\" type=\"application/rss+xml\" title=\"Glowberry\" href=\"/feed\">"
                + "<link rel=\"alternate\" type=\"application/rss+xml\" title=\"Comments Feed\" href=\"/comments/feed\">"
                + "<link rel=\"alternate\" hreflang=\"fr\" href=\"/fr\">"
                + "</head></html>", "https://glowberry.test/");
        assertThat(FeedReader.feedLinks(page)).extracting(Object::toString).containsExactly("https://glowberry.test/feed");
        assertThat(FeedReader.looksLikeFeed("<?xml version=\"1.0\"?><rss>")).isTrue();
        assertThat(FeedReader.looksLikeFeed("<!doctype html><html>")).isFalse();
    }
}
