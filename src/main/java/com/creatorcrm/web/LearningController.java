package com.creatorcrm.web;

import com.creatorcrm.domain.WritingExample;
import com.creatorcrm.learning.LearningService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Settings page API: what the draft writer has learned from, with the option to leave messages out. */
@RestController
@RequestMapping("/api/learning")
public class LearningController {

    public record Overview(LearningService.Stats stats, List<WritingExample> recent) {}

    private final LearningService learning;

    public LearningController(LearningService learning) {
        this.learning = learning;
    }

    @GetMapping
    public Overview overview() {
        return new Overview(learning.stats(), learning.recent());
    }

    @PostMapping("/examples/{id}/excluded")
    public WritingExample setExcluded(@PathVariable Long id, @RequestParam boolean excluded) {
        return learning.setExcluded(id, excluded);
    }
}
