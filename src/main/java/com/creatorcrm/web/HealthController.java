package com.creatorcrm.web;

import com.creatorcrm.health.HealthService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The "Everything working?" card on More, and its one-line version on Today. */
@RestController
@RequestMapping("/api/health")
public class HealthController {

    private final HealthService health;

    public HealthController(HealthService health) {
        this.health = health;
    }

    /** Read-only: what the app last saw. Never calls out to the internet. */
    @GetMapping
    public List<HealthService.Check> checks() {
        return health.checks();
    }

    /** "Check again": asks for the latest version once more, then reports everything. */
    @PostMapping("/recheck")
    public List<HealthService.Check> recheck() {
        return health.recheck();
    }
}
