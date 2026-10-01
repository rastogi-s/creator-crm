package com.creatorcrm.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * AES-256-GCM encryption for credentials at rest.
 *
 * <p>The master key comes from {@code CRM_ENCRYPTION_KEY} (base64, 32 bytes) if set; otherwise it is
 * generated on first start and kept in {@code <data-dir>/master.key}. Back that file up: without it the
 * stored credentials cannot be decrypted (you would have to re-enter them in Settings).
 */
@Service
public class CryptoService {
    private static final Logger log = LoggerFactory.getLogger(CryptoService.class);
    private static final String PREFIX = "v1:";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecureRandom random = new SecureRandom();
    private final SecretKeySpec key;

    @Autowired
    public CryptoService(@Value("${CRM_ENCRYPTION_KEY:}") String envKey,
                         @Value("${crm.data-dir:data}") String dataDir) {
        this.key = new SecretKeySpec(loadOrCreateKey(envKey, Path.of(dataDir, "master.key")), "AES");
    }

    public CryptoService(byte[] rawKey) {
        this.key = new SecretKeySpec(rawKey, "AES");
    }

    public String encrypt(String plaintext) {
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return PREFIX + Base64.getEncoder().encodeToString(out);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Encryption failed", e);
        }
    }

    public String decrypt(String stored) {
        if (!stored.startsWith(PREFIX)) {
            throw new IllegalStateException("Unknown ciphertext format");
        }
        try {
            byte[] in = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, in, 0, IV_BYTES));
            return new String(cipher.doFinal(in, IV_BYTES, in.length - IV_BYTES), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Decryption failed (wrong master key?)", e);
        }
    }

    /** URL-safe random token, e.g. for OAuth state, setup codes and API keys. */
    public String randomToken(int bytes) {
        byte[] b = new byte[bytes];
        random.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    public static String sha256Hex(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Constant-time comparison for tokens and signatures. */
    public static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    private byte[] loadOrCreateKey(String envKey, Path keyFile) {
        if (envKey != null && !envKey.isBlank()) {
            byte[] k = Base64.getDecoder().decode(envKey.trim());
            if (k.length != 32) throw new IllegalStateException("CRM_ENCRYPTION_KEY must be 32 bytes, base64-encoded");
            return k;
        }
        try {
            if (Files.exists(keyFile)) {
                return Base64.getDecoder().decode(Files.readString(keyFile).trim());
            }
            Files.createDirectories(keyFile.toAbsolutePath().getParent());
            byte[] k = new byte[32];
            random.nextBytes(k);
            Files.writeString(keyFile, Base64.getEncoder().encodeToString(k));
            restrictPermissions(keyFile);
            log.warn("Generated a new master encryption key at {}. Back it up and keep it private.",
                    keyFile.toAbsolutePath());
            return k;
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read/create master key " + keyFile, e);
        }
    }

    private static void restrictPermissions(Path file) {
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException | IOException e) {
            // Windows: fall back to owner-only read/write flags; the file also inherits the profile ACL.
            file.toFile().setReadable(false, false);
            file.toFile().setReadable(true, true);
            file.toFile().setWritable(false, false);
            file.toFile().setWritable(true, true);
        }
    }
}
