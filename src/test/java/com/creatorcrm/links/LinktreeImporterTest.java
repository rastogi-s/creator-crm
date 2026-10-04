package com.creatorcrm.links;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class LinktreeImporterTest {

    @Test
    void reducesAnyLinktreeLinkToTheUsername() {
        assertThat(LinktreeImporter.username("https://linktr.ee/Bhavana_Momlife?utm_source=linktree_profile_share&ltsid=abc&fbclid=x"))
                .isEqualTo("Bhavana_Momlife");
        assertThat(LinktreeImporter.username("www.linktr.ee/maya.makes/")).isEqualTo("maya.makes");
        assertThat(LinktreeImporter.username("@maya")).isEqualTo("maya");
        assertThat(LinktreeImporter.username(" maya ")).isEqualTo("maya");
        assertThatThrownBy(() -> LinktreeImporter.username("https://evil.example/x")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LinktreeImporter.username("")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void dropsTrackingParametersButKeepsShopCodes() {
        assertThat(LinktreeImporter.stripTracking("https://a.com/p?utm_source=ig&ref_=abc&fbclid=1#top")).isEqualTo("https://a.com/p?ref_=abc#top");
        assertThat(LinktreeImporter.stripTracking("https://a.com/p?utm_medium=x")).isEqualTo("https://a.com/p");
        assertThat(LinktreeImporter.stripTracking("https://a.com/BHAVANA10")).isEqualTo("https://a.com/BHAVANA10");
    }

    @Test
    void readsButtonsThenSocialIconsFromThePageData() {
        String html = "<html><script id=\"__NEXT_DATA__\" type=\"application/json\">"
                + "{\"props\":{\"pageProps\":{\"account\":{\"pageTitle\":\"@Bhavana\"},"
                + "\"links\":[{\"title\":\"UGC Portfolio\",\"url\":\"https://bhavana.my.canva.site/portfolio\",\"type\":\"CLASSIC\"},"
                + "{\"title\":\"Shop\",\"type\":\"HEADER\"},"
                + "{\"title\":\"YouTube\",\"url\":\"https://www.youtube.com/@crazytraveller?utm_source=lt\"},"
                + "{\"title\":\"Bad\",\"url\":\"javascript:alert(1)\"}],"
                + "\"socialLinks\":[{\"type\":\"INSTAGRAM\",\"url\":\"https://www.instagram.com/Bhavana_Momlife\"},"
                + "{\"type\":\"YOUTUBE\",\"url\":\"https://youtube.com/@crazytraveller/\"},"
                + "{\"type\":\"TIKTOK\",\"url\":\"https://www.tiktok.com/@Bhavana_Momlife\"}]}}}</script></html>";
        LinktreeImporter.Profile p = LinktreeImporter.parse("Bhavana_Momlife", html);
        assertThat(p.name()).isEqualTo("Bhavana");
        assertThat(p.links()).extracting(LinktreeImporter.FoundLink::label).containsExactly("UGC Portfolio", "YouTube", "Instagram", "TikTok");
        assertThat(p.links().get(1).url()).isEqualTo("https://www.youtube.com/@crazytraveller");
        assertThat(p.links()).extracting(LinktreeImporter.FoundLink::social).containsExactly(false, false, true, true);
    }

    @Test
    void fallsBackToPlainLinksOnThePage() {
        String html = "<a href=\"https://linktr.ee/s/about\">About</a><a class=\"x\" href=\"https://shop.example/BHAV&amp;x=1\"><p>My shop</p></a>";
        assertThat(LinktreeImporter.parse("me", html).links())
                .containsExactly(new LinktreeImporter.FoundLink("My shop", "https://shop.example/BHAV&x=1", false));
    }
}
