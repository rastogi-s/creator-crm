package com.creatorcrm.channels.instagram;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class BrandLookupServiceTest {

    @Test
    void partnersComeFromSponsoredCaptionsOnly() {
        List<String> captions = List.of(
                "Morning glow with @maya.makes #ad",
                "New drop! Shop now @glowco",
                "Thanks to @lena_skin for this routine. #gifted @maya.makes.",
                "Behind the scenes with @randomfriend");
        assertThat(BrandLookupService.partners(captions, "glowco")).containsExactly("maya.makes", "lena_skin");
    }

    @Test
    void emailInBioIsFoundButHandlesAreNot() {
        assertThat(BrandLookupService.firstEmail("Clean skincare 🌿 collabs: Partners@GlowCo.com | @glowco_uk"))
                .isEqualTo("partners@glowco.com");
        assertThat(BrandLookupService.firstEmail("Follow @glowco_uk")).isNull();
    }

    @Test
    void picksThePageLinkedToHerInstagram() throws Exception {
        var pages = new ObjectMapper().readTree("""
                {"data":[
                  {"id":"1","name":"Old page","access_token":"t1"},
                  {"id":"2","name":"Other","access_token":"t2","instagram_business_account":{"id":"900","username":"someone"}},
                  {"id":"3","name":"Maya","access_token":"t3","instagram_business_account":{"id":"901","username":"Maya"}}]}""");
        assertThat(FacebookOAuthController.pickPage(pages, "maya").path("id").asText()).isEqualTo("3");
        assertThat(FacebookOAuthController.pickPage(pages, "nobody").path("id").asText()).isEqualTo("2");
        assertThat(FacebookOAuthController.pickPage(new ObjectMapper().readTree("{\"data\":[]}"), "maya")).isNull();
    }
}
