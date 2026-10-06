package com.creatorcrm.channels.gmail;

import com.creatorcrm.domain.Message;
import com.creatorcrm.security.SecretName;
import com.creatorcrm.security.SecretStore;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Component;

/** Links that open a synced email in Gmail itself, in the connected account. */
@Component
public class GmailLinks {
    private static final String PREFIX = "email:";

    private final SecretStore secrets;

    public GmailLinks(SecretStore secrets) {
        this.secrets = secrets;
    }

    /** The Gmail web address of this message, or null when it didn't come from Gmail. */
    public String urlFor(Message m) {
        return m == null ? null : url(m.externalId, secrets.get(SecretName.GMAIL_ADDRESS).orElse(""));
    }

    /** The Gmail API id of this message, or null when it didn't come from Gmail. */
    public static String gmailId(String externalId) {
        if (externalId == null || !externalId.startsWith(PREFIX)) return null;
        String id = externalId.substring(PREFIX.length());
        // Gmail API message ids are hex; anything else (demo data, other channels) has no Gmail page.
        return id.matches("[0-9a-f]{8,32}") ? id : null;
    }

    static String url(String externalId, String account) {
        String id = gmailId(externalId);
        if (id == null) return null;
        // authuser picks the right inbox when she's signed in to more than one Google account.
        String user = account.isBlank() ? "" : "?authuser=" + URLEncoder.encode(account, StandardCharsets.UTF_8);
        return "https://mail.google.com/mail/" + user + "#all/" + id;
    }
}
