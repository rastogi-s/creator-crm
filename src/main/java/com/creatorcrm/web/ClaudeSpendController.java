package com.creatorcrm.web;

import com.creatorcrm.llm.ClaudeSpend;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Settings → Claude spending: the running total, and the credit balance the low-credit warning counts down from. */
@RestController
@RequestMapping("/api/claude-spend")
public class ClaudeSpendController {

    /** {@code usd} null clears the balance. */
    public record Amount(Double usd) {}

    private final ClaudeSpend spend;

    public ClaudeSpendController(ClaudeSpend spend) {
        this.spend = spend;
    }

    @GetMapping
    public ClaudeSpend.Summary summary() {
        return spend.summary();
    }

    @PutMapping("/balance")
    public ClaudeSpend.Summary balance(@RequestBody Amount in) {
        spend.setBalance(in.usd());
        return spend.summary();
    }

    @PutMapping("/spent-before")
    public ClaudeSpend.Summary spentBefore(@RequestBody Amount in) {
        spend.setSpentBefore(in.usd() == null ? 0 : in.usd());
        return spend.summary();
    }
}
