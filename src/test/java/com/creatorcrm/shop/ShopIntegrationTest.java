package com.creatorcrm.shop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.creatorcrm.FakeLlm;
import com.creatorcrm.repo.CaptionSetRepo;
import com.creatorcrm.repo.ProductRepo;
import com.creatorcrm.settings.SettingsService;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;

/** Shop links and post captions end to end: paste links, write captions, and the links land in the right places. */
@SpringBootTest
@ActiveProfiles("test")
class ShopIntegrationTest {

    @TestConfiguration
    static class Config {
        @Bean
        @Primary
        FakeLlm fakeLlm() {
            return new FakeLlm();
        }
    }

    @Autowired FakeLlm llm;
    @Autowired ProductService products;
    @Autowired CaptionService captions;
    @Autowired ProductRepo productRepo;
    @Autowired CaptionSetRepo captionRepo;
    @Autowired SettingsService settings;

    @BeforeEach
    void reset() {
        captionRepo.deleteAll();
        productRepo.deleteAll();
        settings.update(Map.of(SettingsService.AMAZON_TAG, "ava-20", SettingsService.CAPTION_HASHTAGS, "avamakes, #cozy",
                SettingsService.DISCLOSURE_TAG, ""));
        llm.lastCaptionInput = null;
    }

    @Test
    void pastedLinksBecomeProductsWithHerTag() {
        ProductService.Preview preview = products.preview("https://www.amazon.com/Dew-Drop-Serum/dp/B0DEMO0001/ref=x");
        assertThat(preview.name()).isEqualTo("Dew Drop Serum");
        assertThat(preview.url()).isEqualTo("https://www.amazon.com/dp/B0DEMO0001?tag=ava-20");
        assertThat(preview.duplicateOf()).isNull();

        ProductService.View serum = products.add(new ProductService.Request("https://www.amazon.com/Dew-Drop-Serum/dp/B0DEMO0001/ref=x", "",
                "Glow Haus", "Skincare, #Morning routine, skincare", " AVA15 "));
        assertThat(serum.tags()).containsExactly("skincare", "morning routine");
        assertThat(serum.code()).isEqualTo("AVA15");
        assertThat(serum.warning()).isNull();

        assertThat(products.preview("amazon.com/dp/B0DEMO0001").duplicateOf()).isEqualTo("Dew Drop Serum");
        assertThatThrownBy(() -> products.add(new ProductService.Request("https://www.amazon.com/dp/B0DEMO0001?tag=other-20", null, null, null, null)))
                .hasMessageContaining("already in your products");

        ProductService.Added bulk = products.addAll(List.of("https://www.walmart.com/ip/Knit-Throw/123456789", "not a link at all", ""));
        assertThat(bulk.added()).isEqualTo(1);
        assertThat(bulk.skipped()).isEqualTo(1);
        ProductService.View throwBlanket = products.list().stream().filter(p -> p.store().equals("WALMART")).findFirst().orElseThrow();
        assertThat(throwBlanket.name()).isEqualTo("Knit Throw");
        assertThat(throwBlanket.warning()).contains("plain Walmart link");

        ProductService.View edited = products.update(serum.id(), new ProductService.Request(serum.url(), "Dew Serum", "Glow Haus", "skincare", ""));
        assertThat(edited.name()).isEqualTo("Dew Serum");
        assertThat(edited.code()).isNull();
    }

    @Test
    void captionsGetTheProductsLinksAndHerVoice() {
        ProductService.View serum = products.add(new ProductService.Request("https://www.amazon.com/Dew-Drop-Serum/dp/B0DEMO0001", null, null, "skincare", null));
        ProductService.View lamp = products.add(new ProductService.Request("https://shop.example/products/cozy-lamp", "Cozy lamp", null, "home", "AVA10"));

        String png = Base64.getEncoder().encodeToString(new byte[] {(byte) 0x89, 'P', 'N', 'G', 1, 2, 3});
        CaptionService.View v = captions.write(new CaptionService.Request("  My morning skincare routine  ", "funnier", null, List.of(), true,
                "SHORT", List.of("INSTAGRAM", "YOUTUBE", "FACEBOOK"), List.of(new CaptionService.Picture("shot.png", "image/png", png))));

        // Claude saw the products by name only, never their links, and could pick from all of them.
        assertThat(llm.lastCaptionInput.pick()).isTrue();
        assertThat(llm.lastCaptionInput.products()).hasSize(2).noneMatch(l -> l.contains("http"));
        assertThat(llm.lastCaptionInput.platforms()).containsExactly("YOUTUBE", "INSTAGRAM");
        assertThat(llm.lastCaptionInput.pictures()).hasSize(1);
        assertThat(llm.lastCaptionInput.note()).isEqualTo("funnier");

        // It picked product 1: the one it was shown first.
        Long picked = llm.lastCaptionInput.products().get(0).contains("Cozy lamp") ? lamp.id() : serum.id();
        assertThat(v.productIds()).containsExactly(picked);
        assertThat(v.platforms()).containsExactly("YOUTUBE", "INSTAGRAM");
        assertThat(v.result().tiktok()).isNull();
        assertThat(v.result().keyword()).isEqualTo("GLOW");
        String url = picked.equals(serum.id()) ? "https://www.amazon.com/dp/B0DEMO0001?tag=ava-20" : "https://shop.example/products/cozy-lamp";
        assertThat(v.result().youtube().description()).contains(url);
        assertThat(v.result().instagram().caption()).contains("Comment GLOW").endsWith("#ad #skincare #glowup #avamakes #cozy");
        assertThat(v.result().dmReply()).contains(url);
        assertThat(v.pictureCount()).isEqualTo(1);

        assertThat(products.list().get(0).id()).isEqualTo(picked);
        assertThat(products.list().get(0).timesUsed()).isEqualTo(1);

        assertThat(captions.recent()).extracting(CaptionService.Summary::title).containsExactly("Morning glow");
        assertThat(captions.get(v.id()).orElseThrow().result().youtube().description()).contains(url);

        // A link she fixes later shows up in the saved captions too.
        if (picked.equals(lamp.id())) products.update(lamp.id(), new ProductService.Request("https://shop.example/products/lamp-new", "Cozy lamp", null, "home", null));
        else products.update(serum.id(), new ProductService.Request("https://www.amazon.com/dp/B0DEMO0009", "Dew Drop Serum", null, null, null));
        assertThat(captions.get(v.id()).orElseThrow().result().dmReply()).doesNotContain(url)
                .containsAnyOf("https://shop.example/products/lamp-new", "https://www.amazon.com/dp/B0DEMO0009?tag=ava-20");
    }

    @Test
    void productsSheChoseStayEvenIfClaudeLeavesThemOut() {
        ProductService.View a = products.add(new ProductService.Request("https://shop.example/products/one-thing", "One thing", null, null, null));
        ProductService.View b = products.add(new ProductService.Request("https://shop.example/products/other-thing", "Other thing", null, null, null));
        llm.captions = in -> new com.creatorcrm.llm.CaptionDraft("Two things", List.of(2), "", List.of(),
                new com.creatorcrm.llm.CaptionDraft.YouTube("", "", List.of(), ""),
                new com.creatorcrm.llm.CaptionDraft.Instagram("", List.of(), ""),
                new com.creatorcrm.llm.CaptionDraft.TikTok("Look", List.of()), "");
        try {
            CaptionService.View v = captions.write(new CaptionService.Request("two things", null, null, List.of(a.id(), b.id()), true, "VIDEO",
                    List.of("TIKTOK"), null));
            assertThat(llm.lastCaptionInput.pick()).isFalse();
            assertThat(v.productIds()).containsExactly(b.id(), a.id());
            assertThat(v.result().tiktok().caption()).isEqualTo("Look\nLinks in my bio 🛍️\n\n#ad #avamakes #cozy");
            assertThat(v.result().dmReply()).startsWith("Here you go! 💕");
        } finally {
            llm.captions = new FakeLlm().captions;
        }
    }

    @Test
    void checksWhatSheSends() {
        assertThatThrownBy(() -> captions.write(new CaptionService.Request(" ", null, null, null, false, "SHORT", List.of("TIKTOK"), null)))
                .hasMessageContaining("what the video is about");
        assertThatThrownBy(() -> captions.write(new CaptionService.Request("a video", null, null, null, false, "SHORT", List.of(), null)))
                .hasMessageContaining("at least one");
        assertThatThrownBy(() -> captions.write(new CaptionService.Request("a video", null, null, null, false, "SHORT", List.of("TIKTOK"),
                List.of(new CaptionService.Picture("x.pdf", "application/pdf", "AAAA"))))).hasMessageContaining("pictures");
        assertThatThrownBy(() -> settings.update(Map.of(SettingsService.AMAZON_TAG, "not a tag!"))).hasMessageContaining("ava-20");
    }
}
