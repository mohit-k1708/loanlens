package com.loanlens.controller;

import com.loanlens.dto.LoanCreateRequest;
import com.loanlens.dto.LoanResponse;
import com.loanlens.dto.LoanUpdateRequest;
import com.loanlens.dto.PageResponse;
import com.loanlens.dto.ProfitabilityResponse.ContributionStatus;
import com.loanlens.entity.LoanStatus;
import com.loanlens.entity.LoanType;
import com.loanlens.entity.RiskRating;
import com.loanlens.service.LoanService;
import com.loanlens.validation.ReportingPeriods;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** {loanId} is either the numeric id or the loan code (e.g. L1001). */
@RestController
@RequestMapping("/api/loans")
public class LoanController {

    private final LoanService service;

    public LoanController(LoanService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<LoanResponse> create(@Valid @RequestBody LoanCreateRequest request) {
        LoanResponse created = service.create(request);
        return ResponseEntity.created(URI.create("/api/loans/" + created.loanCode())).body(created);
    }

    /**
     * Filters (all optional, combinable): {@code loanCode} and {@code customerName} are case-insensitive "contains"
     * searches; {@code search} matches loan code OR customer name OR customer code; {@code riskRating} is the rating of
     * the loan's most recent risk assessment.
     *
     * <p>With {@code period=2026} each row also carries its profitability for that period ({@code profitability}),
     * and {@code contributionStatus} (POSITIVE / NEGATIVE / BREAK_EVEN) can filter on it.
     */
    @GetMapping
    public PageResponse<LoanResponse> list(@RequestParam(required = false) String loanCode,
                                           @RequestParam(required = false) String customerName,
                                           @RequestParam(required = false) String search,
                                           @RequestParam(required = false) LoanType loanType,
                                           @RequestParam(required = false) LoanStatus loanStatus,
                                           @RequestParam(required = false) RiskRating riskRating,
                                           @RequestParam(required = false) String period,
                                           @RequestParam(required = false) ContributionStatus contributionStatus,
                                           @PageableDefault(size = 20, sort = "loanCode") Pageable pageable) {
        if (period != null) {
            ReportingPeriods.require(period);
        }
        return service.search(new LoanService.LoanQuery(loanCode, customerName, search, loanType, loanStatus, riskRating,
                period, contributionStatus), pageable);
    }

    @GetMapping("/{loanId}")
    public LoanResponse get(@PathVariable String loanId) {
        return service.get(loanId);
    }

    @PutMapping("/{loanId}")
    public LoanResponse update(@PathVariable String loanId, @Valid @RequestBody LoanUpdateRequest request) {
        return service.update(loanId, request);
    }
}
