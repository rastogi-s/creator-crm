package com.creatorcrm.security;

/**
 * Every credential the app can hold. Each one can also be supplied as an environment variable with the
 * same name (handy for Docker/headless installs); the environment wins over the stored value.
 */
public enum SecretName {
    ANTHROPIC_API_KEY,
    GOOGLE_CLIENT_ID,
    GOOGLE_CLIENT_SECRET,
    GMAIL_REFRESH_TOKEN,
    GMAIL_ADDRESS,
    INSTAGRAM_APP_ID,
    INSTAGRAM_APP_SECRET,
    INSTAGRAM_ACCESS_TOKEN,
    INSTAGRAM_TOKEN_EXPIRES_AT,
    INSTAGRAM_USER_ID,
    INSTAGRAM_USERNAME,
    INSTAGRAM_WEBHOOK_VERIFY_TOKEN,
    /** Stored as a SHA-256 hash only; the plain key is shown once when generated. */
    MCP_API_KEY_HASH
}
