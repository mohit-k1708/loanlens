package com.loanlens.controller;

import com.loanlens.dto.ProfitabilityResponse;
import com.loanlens.dto.SnapshotSummaryResponse;
import com.loanlens.service.ProfitabilityService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/loans/{loanId}/profitability")
public class ProfitabilityController {

    private final ProfitabilityService service;

    public ProfitabilityController(ProfitabilityService service) {
        this.service = service;
    }

    /**
     * Calculates the loan's estimated profitability for one reporting period and stores a snapshot of the result
     * (once per distinct set of inputs). The request therefore records data on first use, but repeating it is idempotent.
     */
    @GetMapping
    public ProfitabilityResponse calculate(@PathVariable String loanId, @RequestParam String period) {
        return service.calculate(loanId, period);
    }

    @GetMapping("/history")
    public List<SnapshotSummaryResponse> history(@PathVariable String loanId, @RequestParam String period) {
        return service.history(loanId, period);
    }
}
