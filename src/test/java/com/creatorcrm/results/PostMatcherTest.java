package com.creatorcrm.results;

import static org.assertj.core.api.Assertions.assertThat;

import com.creatorcrm.results.PostMatcher.Media;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Finding a deal's post among her recent posts. */
class PostMatcherTest {

    private static final LocalDate DAY = LocalDate.of(2026, 9, 20);

    private static Media post(String id, String caption, String code, int daysFromDay) {
        return new Media(id, caption, "https://www.instagram.com/p/" + code + "/",
                OffsetDateTime.of(DAY.plusDays(daysFromDay).atTime(18, 0), ZoneOffset.UTC), "FEED");
    }

    @Test
    void readsTheCodeFromAnyInstagramLink() {
        assertThat(PostMatcher.shortcode("https://www.instagram.com/reel/C8xYz12/?igsh=abc")).contains("C8xYz12");
        assertThat(PostMatcher.shortcode("https://instagram.com/p/DemoCandleSet")).contains("DemoCandleSet");
        assertThat(PostMatcher.shortcode("https://www.instagram.com/mayamakes/reel/Abc-_9/")).contains("Abc-_9");
        assertThat(PostMatcher.shortcode("https://www.tiktok.com/@maya/video/123")).isEmpty();
        assertThat(PostMatcher.shortcode(null)).isEmpty();
    }

    @Test
    void matchesByLink() {
        List<Media> media = List.of(post("1", "", "AAA", 0), post("2", "", "BBB", -3));
        assertThat(PostMatcher.byLink(media, "https://www.instagram.com/reel/BBB/?utm_source=ig")).map(Media::id).contains("2");
        assertThat(PostMatcher.byLink(media, "https://www.instagram.com/p/CCC/")).isEmpty();
    }

    @Test
    void matchesTheMentionClosestToThePostingDate() {
        List<Media> media = List.of(
                post("far", "Cozy evenings with Petal & Pine candles", "F", -30),
                post("near", "My new favourite candle from @petalandpine 🕯️ #ad", "N", 2),
                post("other", "Morning tea with Bloomleaf", "O", 0),
                post("closer", "Petal and Pine candle restock!", "C", 1));
        assertThat(PostMatcher.byMention(media, "Petal & Pine", "petalandpine", DAY)).map(Media::id).contains("closer");
        assertThat(PostMatcher.byMention(media, "Petal & Pine", "@petalandpine", DAY.plusDays(3))).map(Media::id).contains("near");
        assertThat(PostMatcher.byMention(media, "Sunday Pantry", null, DAY)).isEmpty();
        assertThat(PostMatcher.byMention(media, "Petal & Pine", null, DAY.plusDays(40))).isEmpty(); // too far from the date
    }

    @Test
    void shortNamesDontMatchEverything() {
        assertThat(PostMatcher.mentions("Lovely co-working day", "Co", null)).isFalse();
        assertThat(PostMatcher.mentions("Loving my Glowberry serum", "Glowberry Skin", null)).isFalse(); // whole name needed
        assertThat(PostMatcher.mentions("Loving my #GlowberrySkin serum", "Glowberry Skin", null)).isTrue();
        assertThat(PostMatcher.mentions("", "Glowberry Skin", "glowberry")).isFalse();
    }
}
