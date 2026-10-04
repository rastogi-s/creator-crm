package com.creatorcrm.rates;

import static org.assertj.core.api.Assertions.assertThat;

import com.creatorcrm.rates.Deliverables.Kind;
import org.junit.jupiter.api.Test;

/** Reading deliverables and usage terms from the wording the classifier writes. */
class DeliverablesTest {

    @Test
    void countsEachKindOfContent() {
        Deliverables d = Deliverables.parse("1 Reel + 3 Stories", "");
        assertThat(d.count(Kind.REEL)).isEqualTo(1);
        assertThat(d.count(Kind.STORY)).isEqualTo(3);
        assertThat(d.describe()).isEqualTo("1 Reel + 3 Stories");

        assertThat(Deliverables.parse("one Reel and two Stories", null).count(Kind.STORY)).isEqualTo(2);
        assertThat(Deliverables.parse("a TikTok, 2 feed posts & a UGC video", null).counts())
                .containsEntry(Kind.TIKTOK, 1).containsEntry(Kind.POST, 2).containsEntry(Kind.UGC, 1);
        assertThat(Deliverables.parse("3-frame Story set", null).count(Kind.STORY)).isEqualTo(3);
        // Brackets explain rather than add: "3 videos (TikTok + Reels)" is three videos.
        assertThat(Deliverables.parse("3 videos (TikTok + Reels)", null).counts()).containsOnlyKeys(Kind.REEL).containsEntry(Kind.REEL, 3);
        assertThat(Deliverables.parse("", null).isEmpty()).isTrue();
        assertThat(Deliverables.parse("Product review", null).isEmpty()).isTrue();
    }

    @Test
    void readsPaidUsageAndExclusivity() {
        Deliverables d = Deliverables.parse("1 Reel", "3 months paid usage, 30-day exclusivity");
        assertThat(d.usageMonths()).isEqualTo(3);
        assertThat(d.exclusive()).isTrue();
        assertThat(d.exclusivityMonths()).isEqualTo(1);
        assertThat(d.describe()).isEqualTo("1 Reel, 3 months of paid usage, 1 month exclusivity");

        assertThat(Deliverables.parse("1 Reel", "Whitelisting for 60 days").usageMonths()).isEqualTo(2);
        assertThat(Deliverables.parse("1 Reel", "usage in perpetuity").usageMonths()).isEqualTo(Deliverables.PERPETUAL_MONTHS);
        assertThat(Deliverables.parse("1 Reel", "Run as Spark Ads").usageMonths()).isEqualTo(1);
        // Organic reposts, "usage rights" with no terms, and a paid Reel are not paid usage.
        assertThat(Deliverables.parse("1 Reel", "30 days organic usage").usageMonths()).isZero();
        assertThat(Deliverables.parse("1 Reel", "usage rights").usageMonths()).isZero();
        assertThat(Deliverables.parse("1 paid Reel", "").usageMonths()).isZero();
        assertThat(Deliverables.parse("1 Reel", "No paid usage").usageMonths()).isZero();
        assertThat(Deliverables.parse("1 Reel", "Category exclusivity").exclusivityMonths()).isZero();
        assertThat(Deliverables.parse("1 Reel", "Category exclusivity").exclusive()).isTrue();
    }
}
