package com.loanlens.controller;

import com.loanlens.dto.RiskAssessmentRequest;
import com.loanlens.dto.RiskAssessmentResponse;
import com.loanlens.service.RiskAssessmentService;
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
@RequestMapping("/api/loans/{loanId}/risk-assessments")
public class RiskAssessmentController {

    private final RiskAssessmentService service;

    public RiskAssessmentController(RiskAssessmentService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<RiskAssessmentResponse> create(@PathVariable String loanId, @Valid @RequestBody RiskAssessmentRequest request) {
        RiskAssessmentResponse created = service.create(loanId, request);
        return ResponseEntity.created(URI.create("/api/loans/" + created.loanCode() + "/risk-assessments/" + created.reportingPeriod()))
                .body(created);
    }

    @GetMapping
    public List<RiskAssessmentResponse> list(@PathVariable String loanId) {
        return service.list(loanId);
    }

    @GetMapping("/{period}")
    public RiskAssessmentResponse get(@PathVariable String loanId, @PathVariable String period) {
        return service.get(loanId, period);
    }

    @PutMapping("/{period}")
    public RiskAssessmentResponse update(@PathVariable String loanId, @PathVariable String period,
                                         @Valid @RequestBody RiskAssessmentRequest request) {
        return service.update(loanId, period, request);
    }
}
