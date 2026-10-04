package com.creatorcrm.web;

import com.creatorcrm.domain.Draft;
import com.creatorcrm.rates.RateAdvisor;
import java.math.BigDecimal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Rate advisor: what to ask for on a deal, and a counter-offer draft at the amount she picks. */
@RestController
@RequestMapping("/api/opportunities/{id}")
public class RatesController {

    public record CounterRequest(BigDecimal amount) {}

    private final RateAdvisor advisor;

    public RatesController(RateAdvisor advisor) {
        this.advisor = advisor;
    }

    @GetMapping("/rate")
    public RateAdvisor.Advice rate(@PathVariable Long id) {
        return advisor.advise(id);
    }

    @PostMapping("/counter")
    public Draft counter(@PathVariable Long id, @RequestBody CounterRequest r) {
        return advisor.draftCounter(id, r.amount());
    }
}
