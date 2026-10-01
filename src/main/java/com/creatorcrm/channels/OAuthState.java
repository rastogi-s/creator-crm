package com.creatorcrm.channels;

import com.creatorcrm.security.CryptoService;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Component;

/** CSRF protection for OAuth redirects: a random state bound to the signed-in session, single use. */
@Component
public class OAuthState {
    private final CryptoService crypto;

    public OAuthState(CryptoService crypto) {
        this.crypto = crypto;
    }

    public String issue(HttpSession session, String provider) {
        String state = crypto.randomToken(24);
        session.setAttribute("oauth_state_" + provider, state);
        return state;
    }

    public void verify(HttpSession session, String provider, String state) {
        Object expected = session.getAttribute("oauth_state_" + provider);
        session.removeAttribute("oauth_state_" + provider);
        if (!(expected instanceof String s) || !CryptoService.constantTimeEquals(s, state)) {
            throw new IllegalArgumentException("OAuth state mismatch. Please start the connection again.");
        }
    }
}
