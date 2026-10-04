package com.creatorcrm.rates;

import static org.assertj.core.api.Assertions.assertThat;

import com.creatorcrm.rates.Deliverables.Kind;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The rate advisor's arithmetic, without a database. */
class RateMathTest {

    private static final String PROFILE = """
            # Rates (drafts will only quote what is written here)
            - 1 Instagram Reel: $800
            - 3-frame Story set: $300
            - UGC video, no posting (30–60s): $___
            - Usage rights / whitelisting: +30% per 30 days
            - Raw footage: +$150
            """;

    @Test
    void ratesWrittenInAboutYou() {
        Map<Kind, BigDecimal> written = RateAdvisor.fromProfile(PROFILE);
        assertThat(written).containsOnlyKeys(Kind.REEL, Kind.STORY);
        assertThat(written.get(Kind.REEL)).isEqualByComparingTo("800");
        assertThat(written.get(Kind.STORY)).isEqualByComparingTo("100"); // a set of three
        // Missing kinds are estimated from the ones she wrote.
        Map<Kind, BigDecimal> all = RateAdvisor.fill(written);
        assertThat(all.get(Kind.REEL)).isEqualByComparingTo("800");
        assertThat(all.get(Kind.TIKTOK).doubleValue()).isPositive();
        assertThat(RateAdvisor.fromProfile("- 1 Instagram Reel: $___")).isEmpty();
    }

    @Test
    void historyIsPricedPerReelAfterTakingOutUsage() {
        List<RateAdvisor.PastDeal> history = List.of(
                new RateAdvisor.PastDeal(new BigDecimal("800"), Deliverables.parse("1 Reel", "")),
                new RateAdvisor.PastDeal(new BigDecimal("1000"), Deliverables.parse("1 Reel + 4 Stories", "")), // 2 Reels' worth
                new RateAdvisor.PastDeal(new BigDecimal("1300"), Deliverables.parse("1 Reel", "1 month paid usage"))); // 1000 + 30%
        assertThat(RateAdvisor.perReelFromHistory(history, 30, 25)).isEqualByComparingTo("800");
    }

    @Test
    void suggestionAddsUpliftsAndComparesTheOffer() {
        RateAdvisor.Rates rates = new RateAdvisor.Rates(RateAdvisor.fill(RateAdvisor.fromProfile(PROFILE)), "Based on the rates in About you");
        RateAdvisor.Advice a = RateAdvisor.compute(Deliverables.parse("1 Reel + 2 Stories", "3 months paid usage, exclusivity"),
                rates, 30, 25, new BigDecimal("600"));
        // (800 + 2 × 100) × (1 + 0.9 + 0.25) = 2150
        assertThat(a.available()).isTrue();
        assertThat(a.suggested()).isEqualByComparingTo("2150");
        assertThat(a.suggestedText()).isEqualTo("$2,150");
        assertThat(a.lines()).extracting(RateAdvisor.Line::text).containsExactly(
                "1 Reel × $800", "2 Stories × $100", "3 months of paid usage, +90%", "Exclusivity, +25%");
        assertThat(a.offerGapPercent()).isEqualTo(-72);
        assertThat(a.basis()).isEqualTo("Based on the rates in About you");

        RateAdvisor.Advice noOffer = RateAdvisor.compute(Deliverables.parse("1 Reel", ""), rates, 30, 25, null);
        assertThat(noOffer.offer()).isNull();
        assertThat(noOffer.offerGapPercent()).isNull();
        assertThat(RateAdvisor.roundToTen(new BigDecimal("824"))).isEqualByComparingTo("820");
    }
}
