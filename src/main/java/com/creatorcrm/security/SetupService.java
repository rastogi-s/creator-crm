package com.creatorcrm.security;

import java.time.OffsetDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * First-run setup. Until an admin account exists the app only serves the setup page, and creating the
 * admin requires a one-time code printed to the server console. Someone who can merely reach the URL
 * therefore cannot claim a fresh install.
 */
@Service
public class SetupService {
    private static final Logger log = LoggerFactory.getLogger(SetupService.class);
    public static final int MIN_PASSWORD_LENGTH = 12;

    private final AppUserRepo users;
    private final PasswordEncoder encoder;
    private final CryptoService crypto;
    private volatile String setupCode;

    public SetupService(AppUserRepo users, PasswordEncoder encoder, CryptoService crypto) {
        this.users = users;
        this.encoder = encoder;
        this.crypto = crypto;
    }

    public boolean isSetupComplete() {
        return users.count() > 0;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void announce() {
        String code = setupCode();
        if (code == null) return;
        log.warn("\n\n  ==============================================================\n"
                + "   First-run setup: open the app in your browser and enter this\n"
                + "   one-time setup code to create your admin account:\n\n"
                + "       {}\n"
                + "  ==============================================================\n", code);
    }

    /**
     * The one-time code while setup is pending (created on first use), else null. Desktop mode passes it
     * to the local browser so a person installing on their own computer never needs the console.
     */
    public synchronized String setupCode() {
        if (isSetupComplete()) return null;
        if (setupCode == null) setupCode = crypto.randomToken(9);
        return setupCode;
    }

    @Transactional
    public synchronized void createAdmin(String code, String username, String password) {
        if (isSetupComplete()) throw new IllegalStateException("Setup has already been completed.");
        if (setupCode == null || !CryptoService.constantTimeEquals(setupCode, code == null ? "" : code.trim())) {
            throw new IllegalArgumentException("Invalid setup code. Check the server console.");
        }
        if (username == null || !username.matches("[A-Za-z0-9._@-]{3,100}")) {
            throw new IllegalArgumentException("Username must be 3-100 letters, digits or . _ @ -");
        }
        if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
            throw new IllegalArgumentException("Password must be at least " + MIN_PASSWORD_LENGTH + " characters.");
        }
        AppUser u = new AppUser();
        u.username = username;
        u.passwordHash = encoder.encode(password);
        u.createdAt = OffsetDateTime.now();
        users.save(u);
        setupCode = null;
        log.info("Admin account '{}' created; setup code invalidated.", username);
    }
}
