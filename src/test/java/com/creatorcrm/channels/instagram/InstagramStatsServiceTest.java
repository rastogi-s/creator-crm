package com.creatorcrm.channels.instagram;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.when;

import com.creatorcrm.repo.AppStateRepo;
import com.creatorcrm.security.SecretName;
import com.creatorcrm.security.SecretStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
@ActiveProfiles("test")
class InstagramStatsServiceTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @MockitoBean InstagramApi api;
    @Autowired InstagramStatsService stats;
    @Autowired SecretStore secrets;
    @Autowired AppStateRepo state;

    @BeforeEach
    void connect() throws Exception {
        secrets.put(SecretName.INSTAGRAM_ACCESS_TOKEN, "IGtoken");
        when(api.get(anyString(), eq("/me"), any())).thenReturn(JSON.readTree(
                "{\"user_id\":\"17841\",\"username\":\"maya\",\"followers_count\":45210,\"follows_count\":300,\"media_count\":512}"));
        when(api.get(anyString(), eq("/me/media"), any())).thenReturn(JSON.readTree(
                "{\"data\":[{\"like_count\":1000,\"comments_count\":40},{\"like_count\":1400,\"comments_count\":60},{\"comments_count\":5}]}"));
    }

    @AfterEach
    void disconnect() {
        secrets.delete(SecretName.INSTAGRAM_ACCESS_TOKEN);
        state.deleteById(InstagramStatsService.STATE_KEY);
    }

    @Test
    void readsFollowersEngagementAndReach() throws Exception {
        when(api.get(anyString(), startsWith("/17841/insights"), any())).thenReturn(JSON.readTree(
                "{\"data\":[{\"name\":\"reach\",\"total_value\":{\"value\":120345}}]}"));

        InstagramStatsService.Stats s = stats.refresh();

        assertThat(s.followers()).isEqualTo(45210);
        assertThat(s.postsSampled()).isEqualTo(2); // the post with hidden likes is skipped
        assertThat(s.avgLikes()).isEqualTo(1200);
        assertThat(s.avgComments()).isEqualTo(50);
        assertThat(s.engagementRatePct()).isEqualTo(2.8); // 1,250 / 45,210
        assertThat(s.reach28d()).isEqualTo(120345);
        assertThat(stats.current()).contains(s);
        assertThat(stats.profileSection()).contains("Followers: 45,210").contains("Engagement rate: 2.8%")
                .contains("Accounts reached in the last 28 days: 120,345");
    }

    @Test
    void reachIsOptionalWhenInsightsPermissionIsMissing() throws Exception {
        when(api.get(anyString(), startsWith("/17841/insights"), any()))
                .thenThrow(new InstagramApi.InstagramApiException(400, "Insufficient permission"));

        InstagramStatsService.Stats s = stats.refresh();

        assertThat(s.reach28d()).isNull();
        assertThat(stats.profileSection()).contains("Followers: 45,210").doesNotContain("reached");
    }

    @Test
    void dailyRefreshSkipsWhenNotConnected() {
        secrets.delete(SecretName.INSTAGRAM_ACCESS_TOKEN);
        stats.refreshIfStale();
        assertThat(stats.current()).isEmpty();
        assertThat(stats.profileSection()).isEmpty();
    }
}
