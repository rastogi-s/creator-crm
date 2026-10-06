package com.creatorcrm.practice;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Locale;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** The real app's side of practice mode: start it, see when it's ready, close it. */
@RestController
@ConditionalOnProperty(name = "crm.practice", havingValue = "false", matchIfMissing = true)
public class PracticeController {
    static final Set<String> LOCAL_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]", "::1");

    private final PracticeMode practice;

    public PracticeController(PracticeMode practice) {
        this.practice = practice;
    }

    @GetMapping("/api/practice")
    public PracticeMode.Status status() {
        return practice.status();
    }

    @PostMapping("/api/practice/start")
    public PracticeMode.Status start(HttpServletRequest req) {
        String host = req.getServerName();
        // The practice copy only listens on this computer, so it can't be opened from a phone.
        if (host == null || !LOCAL_HOSTS.contains(host.toLowerCase(Locale.ROOT))) {
            throw new IllegalStateException("Practice mode opens on the computer where the app runs, not on your phone.");
        }
        String returnUrl = req.getScheme() + "://" + host + ":" + req.getServerPort() + "/#more";
        return practice.start(host, returnUrl);
    }

    @PostMapping("/api/practice/stop")
    public PracticeMode.Status stop() {
        practice.stop();
        return practice.status();
    }
}
