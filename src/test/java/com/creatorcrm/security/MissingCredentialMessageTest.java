package com.creatorcrm.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.creatorcrm.security.SecretStore.MissingCredentialException;
import org.junit.jupiter.api.Test;

/** A missing credential tells her which account to connect and where, not an internal name. */
class MissingCredentialMessageTest {

    @Test
    void namesTheAccountAndWhereToConnectIt() {
        assertThat(new MissingCredentialException(SecretName.ANTHROPIC_API_KEY).getMessage())
                .isEqualTo("Claude isn't connected yet. Add your Claude key in Settings, Accounts.");
        assertThat(new MissingCredentialException(SecretName.GMAIL_REFRESH_TOKEN).getMessage()).startsWith("Gmail isn't connected");
        assertThat(new MissingCredentialException(SecretName.INSTAGRAM_ACCESS_TOKEN).getMessage()).startsWith("Instagram isn't connected");
        assertThat(new MissingCredentialException(SecretName.FACEBOOK_PAGE_TOKEN).getMessage()).contains("Settings, Advanced");
        for (SecretName n : SecretName.values()) {
            assertThat(new MissingCredentialException(n).getMessage()).doesNotContain("_");
        }
    }
}
