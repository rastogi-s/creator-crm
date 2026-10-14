package com.creatorcrm.llm;

import static org.assertj.core.api.Assertions.assertThat;

import com.creatorcrm.domain.Enums.Direction;
import com.creatorcrm.domain.Message;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;

class UntrustedTest {

    @Test
    void longEmailKeepsTheLinksPastTheCut() {
        Message m = new Message();
        m.direction = Direction.INBOUND;
        m.sender = "jo@brand.example";
        m.sentAt = OffsetDateTime.now();
        m.content = "Hello!\n" + "Lots of words about the campaign. ".repeat(300)
                + "\nReady? Apply here (https://forms.brand.example/apply)\nUnsubscribe (https://brand.example/u)";
        String wrapped = Untrusted.wrap(m);
        assertThat(wrapped).contains("[...truncated]")
                .contains("Lines with links further down:\nReady? Apply here (https://forms.brand.example/apply)")
                .doesNotContain("brand.example/u)");
    }

    @Test
    void cuttingALongEmailNeverSplitsAnEmoji() {
        Message m = new Message();
        m.direction = Direction.INBOUND;
        m.sender = "jo@brand.example";
        m.sentAt = OffsetDateTime.now();
        // The 6,000-char cut lands between the two halves of the emoji.
        m.content = "x".repeat(5_999) + "\uD83D\uDE00 and more";
        String wrapped = Untrusted.wrap(m);
        assertThat(wrapped).contains("[...truncated]").isEqualTo(Untrusted.wellFormed(wrapped));
    }

    @Test
    void loneHalvesOfEmojiAreReplaced() {
        assertThat(Untrusted.wellFormed("ok \uD83D\uDE00")).isEqualTo("ok \uD83D\uDE00");
        assertThat(Untrusted.wellFormed("a\uD83D b")).isEqualTo("a\uFFFD b");
        assertThat(Untrusted.wellFormed("end\uD83D")).isEqualTo("end\uFFFD");
        assertThat(Untrusted.wellFormed("\uDE00start")).isEqualTo("\uFFFDstart");
        assertThat(Untrusted.wellFormed(null)).isNull();
    }

    @Test
    void requestJsonHasNoLoneSurrogateEscape() throws Exception {
        // What the Claude API saw for message 605: "\uD83D" with no second half is "unexpected end of hex escape".
        String user = Untrusted.wellFormed("Hi \uD83D there");
        var params = com.anthropic.models.messages.MessageCreateParams.builder()
                .model("claude-sonnet-5-5").maxTokens(10).addUserMessage(user).build();
        String json = com.anthropic.core.ObjectMappers.jsonMapper().writeValueAsString(params._body());
        assertThat(json).doesNotContainIgnoringCase("\\ud83d").contains("Hi \uFFFD there");
    }
}
