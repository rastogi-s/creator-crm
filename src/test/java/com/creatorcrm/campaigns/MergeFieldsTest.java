package com.creatorcrm.campaigns;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class MergeFieldsTest {

    @Test
    void fillsFieldsFallbacksAndBlanks() {
        Map<String, String> v = Map.of("brand", "Glowberry", "first_name", "Ana", "my_name", "Maya");
        assertThat(MergeFields.fill("Hi {first_name}, I'm {my_name}. I love {brand}.", v))
                .isEqualTo("Hi Ana, I'm Maya. I love Glowberry.");
        // A fallback is used when there's no value, and may hold fields itself
        assertThat(MergeFields.fill("{opening_line|I've loved {brand} for years.}", v)).isEqualTo("I've loved Glowberry for years.");
        assertThat(MergeFields.fill("{opening_line|I've loved {brand}.}", Map.of("opening_line", "Your serum is magic.", "brand", "X")))
                .isEqualTo("Your serum is magic.");
        // No value and no fallback: a blank she must fill in before it can be sent
        assertThat(MergeFields.fill("Kit: {media_kit}. Product: {product}", v)).isEqualTo("Kit: [MEDIA KIT LINK]. Product: [PRODUCT]");
        assertThat(MergeFields.fill("{opening_line|}Hello", v)).isEqualTo("Hello");
    }

    @Test
    void leavesUnknownBracesAlone() {
        assertThat(MergeFields.fill("Use {curly} braces and a lone { here", Map.of())).isEqualTo("Use {curly} braces and a lone { here");
        assertThat(MergeFields.fill("{BRAND} in capitals", Map.of("brand", "Glow"))).isEqualTo("Glow in capitals");
    }
}
