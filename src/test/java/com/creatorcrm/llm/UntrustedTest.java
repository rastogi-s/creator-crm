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
}
