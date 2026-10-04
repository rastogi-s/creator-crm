package com.creatorcrm.web;

import com.creatorcrm.domain.Draft;
import com.creatorcrm.rebook.WinBack;
import com.creatorcrm.settings.SettingsService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Win back past brands: who's worth re-pitching, and "Pitch them again" on a finished deal. */
@RestController
@RequestMapping("/api")
public class RebookController {

    private final WinBack winBack;
    private final SettingsService settings;

    public RebookController(WinBack winBack, SettingsService settings) {
        this.winBack = winBack;
        this.settings = settings;
    }

    @GetMapping("/rebook")
    public List<WinBack.Candidate> candidates() {
        return winBack.candidates(settings.today());
    }

    @PostMapping("/opportunities/{id}/repitch")
    public Draft repitch(@PathVariable Long id) {
        return winBack.draftFor(id);
    }
}
