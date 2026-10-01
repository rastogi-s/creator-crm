package com.creatorcrm.backup;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Backup file encryption, independent of the install's master key so a backup can be restored anywhere.
 *
 * <pre>
 * file = MAGIC | salt(16) | iv(12) | AES-256-GCM( gzip(json) )      key = PBKDF2-HMAC-SHA256(passphrase, salt, 600k)
 * </pre>
 * The header is authenticated as associated data, so any tampering makes decryption fail.
 */
final class BackupCrypto {
    static final byte[] MAGIC = "CRMBAK1\n".getBytes(StandardCharsets.US_ASCII);
    static final int MIN_PASSPHRASE = 12;
    private static final int SALT = 16;
    private static final int IV = 12;
    private static final int ITERATIONS = 600_000;

    private static final SecureRandom RANDOM = new SecureRandom();

    private BackupCrypto() {}

    static byte[] encrypt(byte[] plaintext, char[] passphrase) {
        checkPassphrase(passphrase);
        byte[] salt = random(SALT);
        byte[] iv = random(IV);
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key(passphrase, salt), new GCMParameterSpec(128, iv));
            c.updateAAD(MAGIC);
            byte[] ct = c.doFinal(gzip(plaintext));
            ByteArrayOutputStream out = new ByteArrayOutputStream(MAGIC.length + SALT + IV + ct.length);
            out.write(MAGIC);
            out.write(salt);
            out.write(iv);
            out.write(ct);
            return out.toByteArray();
        } catch (GeneralSecurityException | IOException e) {
            throw new IllegalStateException("Could not encrypt backup", e);
        }
    }

    static byte[] decrypt(byte[] file, char[] passphrase) {
        if (file.length < MAGIC.length + SALT + IV + 16 || !Arrays.equals(Arrays.copyOf(file, MAGIC.length), MAGIC)) {
            throw new IllegalArgumentException("This is not a Creator CRM backup file.");
        }
        int p = MAGIC.length;
        byte[] salt = Arrays.copyOfRange(file, p, p + SALT);
        byte[] iv = Arrays.copyOfRange(file, p + SALT, p + SALT + IV);
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key(passphrase, salt), new GCMParameterSpec(128, iv));
            c.updateAAD(MAGIC);
            return gunzip(c.doFinal(file, p + SALT + IV, file.length - p - SALT - IV));
        } catch (AEADBadTagException e) {
            throw new IllegalArgumentException("Wrong passphrase, or the backup file is damaged.");
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Could not decrypt backup", e);
        }
    }

    static void checkPassphrase(char[] passphrase) {
        if (passphrase == null || passphrase.length < MIN_PASSPHRASE) {
            throw new IllegalArgumentException("Backup passphrase must be at least " + MIN_PASSPHRASE + " characters.");
        }
    }

    private static SecretKeySpec key(char[] passphrase, byte[] salt) throws GeneralSecurityException {
        PBEKeySpec spec = new PBEKeySpec(passphrase, salt, ITERATIONS, 256);
        try {
            byte[] k = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
            return new SecretKeySpec(k, "AES");
        } finally {
            spec.clearPassword();
        }
    }

    private static byte[] random(int n) {
        byte[] b = new byte[n];
        RANDOM.nextBytes(b);
        return b;
    }

    private static byte[] gzip(byte[] data) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(bos)) {
            gz.write(data);
        }
        return bos.toByteArray();
    }

    private static byte[] gunzip(byte[] data) {
        try (GZIPInputStream gz = new GZIPInputStream(new ByteArrayInputStream(data))) {
            return gz.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
