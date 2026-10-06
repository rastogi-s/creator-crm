package com.creatorcrm.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/** The first-run setup guide remembers whether she finished it or chose to set up later. */
@SpringBootTest
@ActiveProfiles("test")
class SetupGuideSettingIntegrationTest {

    @Autowired SettingsService settings;

    @AfterEach
    void reset() {
        settings.update(Map.of(SettingsService.SETUP_GUIDE, ""));
    }

    @Test
    void blankUntilFinishedOrSkipped() {
        assertThat(settings.all()).containsEntry(SettingsService.SETUP_GUIDE, "");
        settings.update(Map.of(SettingsService.SETUP_GUIDE, "skipped"));
        assertThat(settings.all()).containsEntry(SettingsService.SETUP_GUIDE, "skipped");
        settings.update(Map.of(SettingsService.SETUP_GUIDE, "done"));
        assertThat(settings.all()).containsEntry(SettingsService.SETUP_GUIDE, "done");
    }

    @Test
    void onlyKnownValues() {
        assertThatThrownBy(() -> settings.update(Map.of(SettingsService.SETUP_GUIDE, "maybe")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
