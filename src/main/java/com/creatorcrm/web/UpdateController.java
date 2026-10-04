package com.creatorcrm.web;

import com.creatorcrm.update.UpdateService;
import com.creatorcrm.update.VideoCache;
import com.creatorcrm.update.WhatsNewService;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** In-app updates, the What's New page and its walkthrough videos. */
@RestController
public class UpdateController {

    public record AutoCheck(boolean enabled) {}

    private final UpdateService updates;
    private final WhatsNewService whatsNew;
    private final VideoCache videos;

    public UpdateController(UpdateService updates, WhatsNewService whatsNew, VideoCache videos) {
        this.updates = updates;
        this.whatsNew = whatsNew;
        this.videos = videos;
    }

    @GetMapping("/api/updates")
    public UpdateService.Status status() {
        return updates.status();
    }

    @PostMapping("/api/updates/check")
    public UpdateService.Status check() {
        return updates.check();
    }

    @PostMapping("/api/updates/install")
    public UpdateService.Status install() {
        return updates.install();
    }

    @PutMapping("/api/updates/auto-check")
    public UpdateService.Status autoCheck(@RequestBody AutoCheck body) {
        updates.setAutoCheck(body.enabled());
        return updates.status();
    }

    @GetMapping("/api/whats-new")
    public WhatsNewService.View whatsNew() {
        return whatsNew.view();
    }

    @PostMapping("/api/whats-new/seen")
    public Map<String, Boolean> seen() {
        whatsNew.markSeen();
        return Map.of("ok", true);
    }

    @GetMapping("/api/videos/{version}/{file}")
    public ResponseEntity<Resource> video(@PathVariable String version, @PathVariable String file) throws InterruptedException {
        Path path;
        try {
            path = videos.get(version, file);
        } catch (IOException e) {
            return ResponseEntity.status(502).build();
        }
        // Spring answers Range requests for a Resource body, so the player can seek.
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(file.endsWith(".webm") ? "video/webm" : "video/mp4"))
                .cacheControl(CacheControl.maxAge(Duration.ofDays(30)).cachePrivate())
                .body(new FileSystemResource(path));
    }
}
