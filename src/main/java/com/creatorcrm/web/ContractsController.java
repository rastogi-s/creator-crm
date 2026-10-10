package com.creatorcrm.web;

import com.creatorcrm.contracts.ContractService;
import com.creatorcrm.domain.Draft;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Contract check: the checklist for each contract on a deal, pasted contracts, re-checking after new limits, and an email asking the brand about flagged points. */
@RestController
@RequestMapping("/api")
public class ContractsController {

    public record PastedContract(String text) {}

    /** The checklist lines she wants to ask the brand about, and anything to add. */
    public record Questions(List<String> flags, String note) {}

    private final ContractService contracts;

    public ContractsController(ContractService contracts) {
        this.contracts = contracts;
    }

    @GetMapping("/opportunities/{id}/contracts")
    public List<ContractService.View> forDeal(@PathVariable Long id) {
        return contracts.forDeal(id);
    }

    @PostMapping("/opportunities/{id}/contracts")
    public ContractService.View paste(@PathVariable Long id, @RequestBody PastedContract body) {
        return contracts.checkPasted(id, body.text());
    }

    @PostMapping("/contracts/{id}/recheck")
    public ContractService.View recheck(@PathVariable Long id) {
        return contracts.recheck(id);
    }

    @PostMapping("/contracts/{id}/questions")
    public Draft questions(@PathVariable Long id, @RequestBody Questions body) {
        return contracts.draftQuestions(id, body.flags(), body.note());
    }
}
