package com.creatorcrm.web;

import com.creatorcrm.learning.ProfileDrafter;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Settings, About you: Draft with Claude. Returns a suggestion only; saving stays with the Save button. */
@RestController
@RequestMapping("/api/settings/profile-draft")
public class ProfileDraftController {

    public record Request(String current) {}

    private final ProfileDrafter drafter;

    public ProfileDraftController(ProfileDrafter drafter) {
        this.drafter = drafter;
    }

    @PostMapping
    public ProfileDrafter.Result draft(@RequestBody(required = false) Request r) {
        String current = r == null ? null : r.current();
        if (current != null && current.length() > 20_000) throw new IllegalArgumentException("About you is too long");
        return drafter.draft(current);
    }
}
