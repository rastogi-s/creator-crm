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
    /** Optional Facebook connection (Instagram API with Facebook Login), used to look up brands' accounts. */
    FACEBOOK_APP_ID,
    FACEBOOK_APP_SECRET,
    FACEBOOK_PAGE_TOKEN,
    FACEBOOK_PAGE_NAME,
    /** Her Instagram account's id as the Facebook Graph API knows it (not the Instagram Login id). */
    FACEBOOK_IG_USER_ID,
    /** GitHub token allowed to open issues on the app's repo; turns on error reports. */
    ERROR_REPORT_TOKEN,
    /** Optional contact finders: Hunter.io (free tier 50 credits a month) and Apollo.io. Their data is for her own outreach only. */
    HUNTER_API_KEY,
    APOLLO_API_KEY,
    /** Encrypts the automatic nightly backups. */
    BACKUP_PASSPHRASE,
    /** Stored as a SHA-256 hash only; the plain key is shown once when generated. */
    MCP_API_KEY_HASH
}
