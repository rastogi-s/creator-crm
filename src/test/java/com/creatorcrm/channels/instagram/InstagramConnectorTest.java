package com.creatorcrm.channels.instagram;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.creatorcrm.channels.NormalizedMessage;
import com.creatorcrm.config.CrmProperties;
import com.creatorcrm.domain.Enums.Direction;
import com.creatorcrm.security.SecretName;
import com.creatorcrm.security.SecretStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** DM fetching: the counts the DMs page shows, and messages that come back as ids only. */
class InstagramConnectorTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    private final InstagramApi api = mock(InstagramApi.class);
    private final SecretStore secrets = mock(SecretStore.class);
    private InstagramConnector connector;

    @BeforeEach
    void setUp() {
        CrmProperties props = mock(CrmProperties.class);
        when(props.instagram()).thenReturn(new CrmProperties.Instagram("v23.0", 50));
        when(secrets.has(SecretName.INSTAGRAM_USER_ID)).thenReturn(true);
        when(secrets.require(SecretName.INSTAGRAM_USER_ID)).thenReturn("me1");
        when(secrets.require(SecretName.INSTAGRAM_ACCESS_TOKEN)).thenReturn("IGtoken");
        when(secrets.get(SecretName.INSTAGRAM_USERNAME)).thenReturn(Optional.of("ava"));
        connector = new InstagramConnector(secrets, api, props);
    }

    @Test
    void noConversationsIsReportedSoThePageCanSayWhy() throws Exception {
        when(api.get(anyString(), eq("/me/conversations"), any())).thenReturn(JSON.readTree("{\"data\":[]}"));
        OffsetDateTime since = OffsetDateTime.now().minusDays(14);

        assertThat(connector.fetchSince(since)).isEmpty();

        InstagramConnector.FetchReport r = connector.lastFetch().orElseThrow();
        assertThat(r.conversations()).isZero();
        assertThat(r.messages()).isZero();
        assertThat(r.since()).isEqualTo(since);
    }

    @Test
    void messagesListedAsIdsOnlyAreReadOneByOne() throws Exception {
        when(api.get(anyString(), eq("/me/conversations"), any())).thenReturn(JSON.readTree(
                "{\"data\":[{\"id\":\"c1\",\"updated_time\":\"2099-01-01T10:00:00+0000\"},"
                        + "{\"id\":\"c2\",\"updated_time\":\"2000-01-01T10:00:00+0000\"}]}"));
        when(api.get(anyString(), eq("/c1"), any())).thenReturn(JSON.readTree(
                "{\"messages\":{\"data\":[{\"id\":\"m1\",\"created_time\":\"2099-01-01T10:00:00+0000\"},"
                        + "{\"id\":\"m2\",\"created_time\":\"2099-01-01T09:00:00+0000\"}]}}"));
        when(api.get(anyString(), eq("/m1"), any())).thenReturn(JSON.readTree(
                "{\"id\":\"m1\",\"created_time\":\"2099-01-01T10:00:00+0000\",\"from\":{\"id\":\"b9\",\"username\":\"glowhaus\"},"
                        + "\"to\":{\"data\":[{\"id\":\"me1\",\"username\":\"ava\"}]},\"message\":\"Collab?\"}"));
        // Too old for details: Instagram says it was deleted. Skipped, the rest still syncs.
        when(api.get(anyString(), eq("/m2"), any())).thenThrow(new java.io.IOException("message deleted"));

        List<NormalizedMessage> out = connector.fetchSince(OffsetDateTime.now().minusDays(14));

        assertThat(out).singleElement().satisfies(m -> {
            assertThat(m.direction()).isEqualTo(Direction.INBOUND);
            assertThat(m.senderName()).isEqualTo("glowhaus");
            assertThat(m.content()).isEqualTo("Collab?");
            assertThat(m.threadKey()).isEqualTo("ig:b9");
        });
        InstagramConnector.FetchReport r = connector.lastFetch().orElseThrow();
        assertThat(r.conversations()).isEqualTo(2);
        assertThat(r.activeSince()).isEqualTo(1);
        assertThat(r.messages()).isEqualTo(1);
    }

    @Test
    void anEmptyFirstPageWithANextPageIsFollowed() throws Exception {
        // What her account sent back: no chats on page one, but a "next" page.
        when(api.get(anyString(), eq("/me/conversations"), argThat(q -> q != null && !q.containsKey("after"))))
                .thenReturn(JSON.readTree("{\"data\":[],\"paging\":{\"cursors\":{\"after\":\"P2\"},\"next\":\"https://x\"}}"));
        when(api.get(anyString(), eq("/me/conversations"), argThat(q -> q != null && "P2".equals(q.get("after")))))
                .thenReturn(JSON.readTree("{\"data\":[{\"id\":\"c1\",\"updated_time\":\"2099-01-01T10:00:00+0000\"}]}"));
        when(api.get(anyString(), eq("/c1"), any())).thenReturn(JSON.readTree(
                "{\"messages\":{\"data\":[{\"id\":\"m1\",\"created_time\":\"2099-01-01T10:00:00+0000\","
                        + "\"from\":{\"id\":\"b9\",\"username\":\"shubh\"},\"to\":{\"data\":[{\"id\":\"me1\"}]},\"message\":\"test\"}]}}"));

        List<NormalizedMessage> out = connector.fetchSince(OffsetDateTime.now().minusDays(14));

        assertThat(out).singleElement().satisfies(m -> assertThat(m.content()).isEqualTo("test"));
        assertThat(connector.lastFetch().orElseThrow().conversations()).isEqualTo(1);
    }
}
