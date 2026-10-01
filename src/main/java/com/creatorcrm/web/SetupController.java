package com.creatorcrm.web;

import com.creatorcrm.security.SetupService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** First-run: create the admin account with the one-time code from the server console. */
@RestController
@RequestMapping("/api/setup")
public class SetupController {

    public record CreateAdmin(@NotBlank @Size(max = 64) String code,
                              @NotBlank @Size(max = 100) String username,
                              @NotBlank @Size(max = 200) String password) {}

    private final SetupService setup;

    public SetupController(SetupService setup) {
        this.setup = setup;
    }

    @GetMapping("/status")
    public Map<String, Boolean> status() {
        return Map.of("setupComplete", setup.isSetupComplete());
    }

    @PostMapping("/admin")
    public Map<String, Boolean> createAdmin(@Valid @RequestBody CreateAdmin r) {
        setup.createAdmin(r.code(), r.username(), r.password());
        return Map.of("setupComplete", true);
    }
}
