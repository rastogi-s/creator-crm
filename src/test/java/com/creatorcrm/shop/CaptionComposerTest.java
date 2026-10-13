package com.creatorcrm.shop;

import static org.assertj.core.api.Assertions.assertThat;

import com.creatorcrm.domain.Product;
import com.creatorcrm.llm.CaptionDraft;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CaptionComposerTest {

    private static Product product(long id, String name, Product.Store store, String url, String code) {
        Product p = new Product();
        p.id = id;
        p.name = name;
        p.store = store;
        p.url = url;
        p.code = code;
        return p;
    }

    private static final CaptionDraft DRAFT = new CaptionDraft("Morning glow", List.of(1, 2), "serum", List.of("Watch this", " "),
            new CaptionDraft.YouTube("My 5 minute routine", "The routine I do every day.", List.of("skincare", "#morning routine", "glow, skin"), "Ask me anything!"),
            new CaptionDraft.Instagram("Morning glow ✨\nMy routine, start to finish.", List.of("#skincare", "glowup", "#AvaMakes"), "Everything's on the link"),
            new CaptionDraft.TikTok("Morning glow", List.of("#fyp")),
            "Here you go!");

    private static final List<Product> PRODUCTS = List.of(
            product(1, "Dew Drop Serum", Product.Store.AMAZON, "https://www.amazon.com/dp/B0DEMO0001?tag=ava-20", null),
            product(2, "Silk scrunchies", Product.Store.OTHER, "https://shop.example/scrunchies", "AVA15"));

    @Test
    void addsLinksDisclosuresAndHashtagsPerPlatform() {
        CaptionComposer.Result r = CaptionComposer.compose(DRAFT, PRODUCTS, List.of(), Set.of("YOUTUBE", "INSTAGRAM", "TIKTOK"),
                new CaptionComposer.Style("#ad", List.of("#avamakes", "#cozy")), null);

        assertThat(r.keyword()).isEqualTo("SERUM");
        assertThat(r.hooks()).containsExactly("Watch this");
        assertThat(r.youtube().description())
                .startsWith("The routine I do every day.\n\n🛍️ Shop this video:\n• Dew Drop Serum: https://www.amazon.com/dp/B0DEMO0001?tag=ava-20\n• Silk scrunchies: https://shop.example/scrunchies Code AVA15 for a discount")
                .contains(CaptionComposer.AMAZON_DISCLOSURE).contains(CaptionComposer.AFFILIATE_DISCLOSURE);
        assertThat(r.youtube().tags()).isEqualTo("skincare, morning routine, glow skin");
        assertThat(r.youtube().pinnedComment()).contains("Ask me anything!").contains("https://shop.example/scrunchies");

        // Links don't work in Instagram captions: the comment keyword goes in instead, then the ad label and hashtags.
        assertThat(r.instagram().caption()).isEqualTo("Morning glow ✨\nMy routine, start to finish.\n\nComment SERUM and I'll DM you the links 💌"
                + "\n\n#ad #skincare #glowup #AvaMakes #cozy");
        assertThat(r.instagram().caption()).doesNotContain("https://");
        assertThat(r.tiktok().caption()).isEqualTo("Morning glow\nLinks in my bio 🛍️\n\n#ad #fyp #avamakes #cozy");
        assertThat(r.dmReply()).isEqualTo("Here you go!\n\n• Dew Drop Serum:\nhttps://www.amazon.com/dp/B0DEMO0001?tag=ava-20\n"
                + "• Silk scrunchies:\nhttps://shop.example/scrunchies\nCode AVA15 for a discount");
        assertThat(r.bioList()).isEqualTo("Dew Drop Serum → https://www.amazon.com/dp/B0DEMO0001?tag=ava-20\nSilk scrunchies (code AVA15) → https://shop.example/scrunchies");
        assertThat(r.tips()).anyMatch(t -> t.contains("SERUM"));
    }

    @Test
    void noProductsAndNoDealMeansNoLabelAndNoLinks() {
        CaptionComposer.Result r = CaptionComposer.compose(DRAFT, List.of(), List.of(), Set.of("INSTAGRAM"),
                new CaptionComposer.Style("#ad", List.of()), null);
        assertThat(r.youtube()).isNull();
        assertThat(r.tiktok()).isNull();
        assertThat(r.keyword()).isNull();
        assertThat(r.instagram().caption()).isEqualTo("Morning glow ✨\nMy routine, start to finish.\n\n#skincare #glowup #AvaMakes");
        assertThat(r.dmReply()).isEqualTo("Here you go!");
        assertThat(r.bioList()).isNull();
    }

    @Test
    void aBrandDealGetsTheLabelAndPartnershipReminders() {
        CaptionComposer.Result r = CaptionComposer.compose(DRAFT, List.of(), List.of(), Set.of("INSTAGRAM", "YOUTUBE"),
                new CaptionComposer.Style("#sponsored", List.of()), "Glow Haus");
        assertThat(r.instagram().caption()).endsWith("#sponsored #skincare #glowup #AvaMakes");
        assertThat(r.tips()).anyMatch(t -> t.contains("Paid partnership") && t.contains("Glow Haus"));
        assertThat(r.tips()).anyMatch(t -> t.contains("paid promotion"));
    }

    @Test
    void herHashtagsAlwaysFitUnderTheLimit() {
        List<String> claude = java.util.stream.IntStream.range(0, 40).mapToObj(i -> "#tag" + i).toList();
        List<String> tags = CaptionComposer.hashtags(claude, List.of("avamakes"), 30, "");
        assertThat(tags).hasSize(30).contains("#avamakes").contains("#tag0").doesNotContain("#tag39");
        assertThat(CaptionComposer.hashtags(List.of("#Cozy", "#cozy", "#home"), List.of(), 30, "so #cozy today")).containsExactly("#home");
    }

    @Test
    void keywordFallsBackToAProductWord() {
        assertThat(CaptionComposer.keyword("", PRODUCTS)).isEqualTo("DROP");
        assertThat(CaptionComposer.keyword("glow-up!", PRODUCTS)).isEqualTo("GLOWUP");
        assertThat(CaptionComposer.keyword(null, List.of())).isEqualTo("LINK");
    }
}
