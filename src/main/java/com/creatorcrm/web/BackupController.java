package com.creatorcrm.web;

import com.creatorcrm.backup.BackupService;
import com.creatorcrm.security.AppUser;
import com.creatorcrm.security.AppUserRepo;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.time.LocalDate;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Backup and restore. A backup contains every credential, so both directions re-check the signed-in
 * user's password. Passphrases travel in URL-encoded headers (never in URLs or logs).
 */
@RestController
@RequestMapping("/api/backup")
public class BackupController {
    private static final int MAX_BACKUP_BYTES = 200 * 1024 * 1024;

    public record ExportRequest(@NotBlank String currentPassword, @NotBlank @Size(min = 12, max = 1000) String passphrase) {}

    private final BackupService backups;
    private final AppUserRepo users;
    private final PasswordEncoder encoder;

    public BackupController(BackupService backups, AppUserRepo users, PasswordEncoder encoder) {
        this.backups = backups;
        this.users = users;
        this.encoder = encoder;
    }

    @PostMapping("/export")
    public ResponseEntity<byte[]> export(@Valid @RequestBody ExportRequest r, Principal principal) {
        checkPassword(principal, r.currentPassword());
        byte[] file = backups.export(r.passphrase().toCharArray());
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename("creator-crm-backup-" + LocalDate.now() + ".crmbak").build().toString())
                .body(file);
    }

    /** Decrypt and describe a backup without changing anything (shown before confirming a restore). */
    @PostMapping(value = "/inspect", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public BackupService.Summary inspect(@RequestBody byte[] file, @RequestHeader("X-Backup-Passphrase") String passphrase) {
        checkSize(file);
        return backups.inspect(file, decode(passphrase));
    }

    @PostMapping(value = "/restore", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public BackupService.Summary restore(@RequestBody byte[] file,
                                         @RequestHeader("X-Backup-Passphrase") String passphrase,
                                         @RequestHeader("X-Current-Password") String currentPassword,
                                         Principal principal) {
        checkSize(file);
        checkPassword(principal, new String(decode(currentPassword)));
        return backups.restore(file, decode(passphrase));
    }

    private void checkPassword(Principal principal, String password) {
        AppUser u = users.findByUsernameIgnoreCase(principal.getName()).orElseThrow();
        if (!encoder.matches(password, u.passwordHash)) throw new IllegalArgumentException("Current password is wrong");
    }

    private static void checkSize(byte[] file) {
        if (file == null || file.length == 0) throw new IllegalArgumentException("Choose a backup file.");
        if (file.length > MAX_BACKUP_BYTES) throw new IllegalArgumentException("Backup file is too large.");
    }

    private static char[] decode(String header) {
        return URLDecoder.decode(header, StandardCharsets.UTF_8).toCharArray();
    }
}
