package com.creatorcrm.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.creatorcrm.health.HealthService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/** The "Everything working?" card reads remembered state for all five checks, in a fixed order. */
@SpringBootTest
@ActiveProfiles("test")
class HealthIntegrationTest {

    @Autowired HealthController health;

    @Test
    void reportsEveryCheckInOrder() {
        List<HealthService.Check> checks = health.checks();
        assertThat(checks).extracting(HealthService.Check::id)
                .containsExactly("claude", "gmail", "instagram", "backups", "updates");
        for (HealthService.Check c : checks) {
            assertThat(c.summary()).isNotBlank();
            assertThat(c.headline()).isNotBlank();
            if (c.state() == HealthService.State.problem || c.state() == HealthService.State.warn) {
                assertThat(c.fix()).as(c.id()).isNotNull();
            }
        }
    }
}
