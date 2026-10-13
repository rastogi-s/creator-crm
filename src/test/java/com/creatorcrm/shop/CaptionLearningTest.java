package com.creatorcrm.shop;

import static org.assertj.core.api.Assertions.assertThat;

import com.creatorcrm.domain.CaptionSet;
import com.creatorcrm.domain.CaptionUse;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class CaptionLearningTest {

    @Test
    void examplesLoseTheLinksDisclosuresAndHashtagsTheAppAdds() {
        String shown = "Glass skin in 3 steps ✨\n\n🛍️ Shop this video:\n• Dew Serum: https://www.amazon.com/dp/B0DEMO0001?tag=ava-20 Code AVA20 for a discount\n\n"
                + CaptionComposer.AMAZON_DISCLOSURE + "\n\n#ad #skincare #glowup";
        assertThat(CaptionLearning.stripAppParts(shown)).isEqualTo("Glass skin in 3 steps ✨");
        assertThat(CaptionLearning.stripAppParts("Love this #cozy lamp\nso much")).isEqualTo("Love this #cozy lamp\nso much");
    }

    @Test
    void editedCaptionsComeFirstAndNoFieldCrowdsOutTheRest() {
        List<CaptionUse> newestFirst = new ArrayList<>();
        for (int i = 0; i < 5; i++) newestFirst.add(use(CaptionUse.Field.INSTAGRAM_CAPTION, false, "plain " + i));
        newestFirst.add(use(CaptionUse.Field.INSTAGRAM_CAPTION, true, "edited old"));
        newestFirst.add(use(CaptionUse.Field.YOUTUBE_TITLE, true, "a title"));
        newestFirst.add(use(CaptionUse.Field.DM_REPLY, false, "here you go"));

        List<CaptionUse> picked = CaptionLearning.pick(newestFirst, List.of("INSTAGRAM"));
        assertThat(picked).extracting(u -> u.usedText).containsExactly("edited old", "plain 0", "here you go");
        assertThat(CaptionLearning.pick(newestFirst, List.of("YOUTUBE"))).extracting(u -> u.usedText).containsExactly("a title");
    }

    @Test
    void aTweakSheKeepsPressingBecomesAHabit() {
        List<CaptionSet> recent = List.of(set("mention my giveaway. shorter"), set("shorter"), set("funnier"), set(null));
        assertThat(CaptionLearning.habits(recent)).containsExactly("shorter (she asked for this on 2 of her last 4 captions)");
    }

    private static CaptionUse use(CaptionUse.Field f, boolean edited, String text) {
        CaptionUse u = new CaptionUse();
        u.field = f;
        u.edited = edited;
        u.writtenText = "Claude's version";
        u.usedText = text;
        u.usedAt = OffsetDateTime.now();
        return u;
    }

    private static CaptionSet set(String note) {
        CaptionSet s = new CaptionSet();
        s.note = note;
        return s;
    }
}
