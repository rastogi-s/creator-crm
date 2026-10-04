package com.creatorcrm.channels.instagram;

import com.creatorcrm.security.SecretName;
import com.creatorcrm.security.SecretStore;
import org.springframework.stereotype.Component;

/**
 * The optional second Meta connection: her Instagram account reached through the Facebook Page it is linked to.
 * Only this route offers business discovery (looking up another business or creator account by handle) and
 * reliable tagged-post reads; DMs and stats keep using the Instagram Login connection.
 */
@Component
public class FacebookConnection {
    private final SecretStore secrets;

    public FacebookConnection(SecretStore secrets) {
        this.secrets = secrets;
    }

    public boolean isConnected() {
        return secrets.has(SecretName.FACEBOOK_PAGE_TOKEN) && secrets.has(SecretName.FACEBOOK_IG_USER_ID);
    }

    public String token() {
        return secrets.require(SecretName.FACEBOOK_PAGE_TOKEN);
    }

    public String igUserId() {
        return secrets.require(SecretName.FACEBOOK_IG_USER_ID);
    }

    public String pageName() {
        return secrets.get(SecretName.FACEBOOK_PAGE_NAME).orElse("");
    }

    public void disconnect() {
        secrets.delete(SecretName.FACEBOOK_PAGE_TOKEN, SecretName.FACEBOOK_PAGE_NAME, SecretName.FACEBOOK_IG_USER_ID);
    }
}
