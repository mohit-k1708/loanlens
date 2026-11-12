package com.loanlens.controller;

import com.loanlens.dto.CostAllocationRequest;
import com.loanlens.dto.CostAllocationResponse;
import com.loanlens.service.OperationalCostAllocationService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/loans/{loanId}/operational-cost-allocations")
public class OperationalCostAllocationController {

    private final OperationalCostAllocationService service;

    public OperationalCostAllocationController(OperationalCostAllocationService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CostAllocationResponse create(@PathVariable String loanId, @Valid @RequestBody CostAllocationRequest request) {
        return service.create(loanId, request);
    }

    @GetMapping
    public List<CostAllocationResponse> list(@PathVariable String loanId, @RequestParam String period) {
        return service.list(loanId, period);
    }
}
