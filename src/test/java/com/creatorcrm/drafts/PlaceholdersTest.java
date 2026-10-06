package com.creatorcrm.drafts;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PlaceholdersTest {

    @Test
    void findsTheBlanksClaudeLeaves() {
        assertThat(Placeholders.find("My rate is [RATE FOR 1 REEL]. Media kit: [MEDIA KIT LINK]. Again [RATE FOR 1 REEL]."))
                .containsExactly("[RATE FOR 1 REEL]", "[MEDIA KIT LINK]");
    }

    @Test
    void ignoresOrdinaryBrackets() {
        assertThat(Placeholders.find("See note [1], [x], [sic], [Link], [AB] and a link [here](https://x.test)")).isEmpty();
        assertThat(Placeholders.message("Hi Marcus, my rate for one Reel is $600.")).isNull();
    }

    @Test
    void saysHowManyAreLeft() {
        assertThat(Placeholders.message("[RATE FOR 1 REEL]")).isEqualTo("Fill in the blank before sending: [RATE FOR 1 REEL]");
        assertThat(Placeholders.message("[RATE] and [USAGE TERMS]"))
                .isEqualTo("Fill in the 2 blanks before sending: [RATE], [USAGE TERMS]");
    }
}
