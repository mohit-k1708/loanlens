package com.loanlens.controller;

import com.loanlens.dto.FinancialRecordRequest;
import com.loanlens.dto.FinancialRecordResponse;
import com.loanlens.service.FinancialRecordService;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/loans/{loanId}/financial-records")
public class FinancialRecordController {

    private final FinancialRecordService service;

    public FinancialRecordController(FinancialRecordService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<FinancialRecordResponse> create(@PathVariable String loanId, @Valid @RequestBody FinancialRecordRequest request) {
        FinancialRecordResponse created = service.create(loanId, request);
        return ResponseEntity.created(URI.create("/api/loans/" + created.loanCode() + "/financial-records/" + created.reportingPeriod()))
                .body(created);
    }

    @GetMapping
    public List<FinancialRecordResponse> list(@PathVariable String loanId) {
        return service.list(loanId);
    }

    @GetMapping("/{period}")
    public FinancialRecordResponse get(@PathVariable String loanId, @PathVariable String period) {
        return service.get(loanId, period);
    }

    /** Corrects the record for a period. Profitability results change accordingly; earlier snapshots are kept as history. */
    @PutMapping("/{period}")
    public FinancialRecordResponse update(@PathVariable String loanId, @PathVariable String period,
                                          @Valid @RequestBody FinancialRecordRequest request) {
        return service.update(loanId, period, request);
    }
}
