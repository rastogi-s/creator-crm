package com.creatorcrm.web;

import com.creatorcrm.contracts.ContractService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Contract check: the checklist for each contract on a deal, pasted contracts, and re-checking after new limits. */
@RestController
@RequestMapping("/api")
public class ContractsController {

    public record PastedContract(String text) {}

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
}
