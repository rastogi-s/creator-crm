package com.creatorcrm.security;

import java.time.OffsetDateTime;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Encrypted credential storage. Values are never logged and never returned to the browser. */
@Service
public class SecretStore {

    public static class MissingCredentialException extends RuntimeException {
        public MissingCredentialException(SecretName name) {
            super(plain(name));
        }

        /** What to do about it, in her words: which account to connect, and where. */
        static String plain(SecretName name) {
            String n = name.name();
            if (name == SecretName.ANTHROPIC_API_KEY) return "Claude isn't connected yet. Add your Claude key in Settings, Accounts.";
            if (n.startsWith("GOOGLE_") || n.startsWith("GMAIL_")) return "Gmail isn't connected yet. Connect it in Settings, Accounts.";
            if (n.startsWith("INSTAGRAM_")) return "Instagram isn't connected yet. Connect it in Settings, Accounts.";
            if (n.startsWith("FACEBOOK_")) return "Facebook isn't connected yet. Connect it in Settings, Advanced.";
            if (name == SecretName.BACKUP_PASSPHRASE) return "Choose a backup passphrase first, in Settings, App.";
            return "This needs setting up first, in Settings, Advanced.";
        }
    }

    private final SecretRepo repo;
    private final CryptoService crypto;
    private final Environment env;

    public SecretStore(SecretRepo repo, CryptoService crypto, Environment env) {
        this.repo = repo;
        this.crypto = crypto;
        this.env = env;
    }

    public Optional<String> get(SecretName name) {
        String fromEnv = env.getProperty(name.name());
        if (fromEnv != null && !fromEnv.isBlank()) return Optional.of(fromEnv.trim());
        return repo.findById(name.name()).map(s -> crypto.decrypt(s.valueEnc));
    }

    public boolean has(SecretName name) {
        return get(name).isPresent();
    }

    public String require(SecretName name) {
        return get(name).orElseThrow(() -> new MissingCredentialException(name));
    }

    @Transactional
    public void put(SecretName name, String value) {
        if (value == null || value.isBlank()) {
            repo.deleteById(name.name());
            return;
        }
        Secret s = repo.findById(name.name()).orElseGet(Secret::new);
        s.name = name.name();
        s.valueEnc = crypto.encrypt(value.trim());
        s.updatedAt = OffsetDateTime.now();
        repo.save(s);
    }

    @Transactional
    public void delete(SecretName... names) {
        for (SecretName n : names) repo.deleteById(n.name());
    }

    /** Which credentials are present, for the Settings page. Never exposes values. */
    public Map<SecretName, Boolean> presence() {
        Map<SecretName, Boolean> m = new EnumMap<>(SecretName.class);
        for (SecretName n : SecretName.values()) m.put(n, has(n));
        return m;
    }
}
