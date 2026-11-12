package com.loanlens.service;

import com.loanlens.dto.LoanCreateRequest;
import com.loanlens.dto.LoanResponse;
import com.loanlens.dto.LoanUpdateRequest;
import com.loanlens.dto.PageResponse;
import com.loanlens.dto.ProfitabilityResponse.ContributionStatus;
import com.loanlens.exception.BadRequestException;
import com.loanlens.entity.Customer;
import com.loanlens.entity.Loan;
import com.loanlens.entity.LoanStatus;
import com.loanlens.entity.LoanType;
import com.loanlens.entity.RiskRating;
import com.loanlens.exception.BusinessRuleException;
import com.loanlens.exception.ConflictException;
import com.loanlens.exception.ResourceNotFoundException;
import com.loanlens.mapper.LoanMapper;
import com.loanlens.repository.CustomerRepository;
import com.loanlens.repository.LoanRepository;
import com.loanlens.repository.LoanSpecifications;
import com.loanlens.repository.RiskAssessmentRepository;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class LoanService {

    private final LoanRepository loans;
    private final CustomerRepository customers;
    private final RiskAssessmentRepository riskAssessments;
    private final ProfitabilityOverviewService overview;

    public LoanService(LoanRepository loans, CustomerRepository customers, RiskAssessmentRepository riskAssessments,
                       ProfitabilityOverviewService overview) {
        this.loans = loans;
        this.customers = customers;
        this.riskAssessments = riskAssessments;
        this.overview = overview;
    }

    /** Criteria for the loan list. {@code period} adds profitability figures; {@code contributionStatus} requires it. */
    public record LoanQuery(String loanCode, String customerName, String search, LoanType loanType, LoanStatus loanStatus,
                            RiskRating riskRating, String period, ContributionStatus contributionStatus) {
    }

    public PageResponse<LoanResponse> search(LoanQuery q, Pageable pageable) {
        if (q.contributionStatus() != null && q.period() == null) {
            throw new BadRequestException("Filtering by contribution status needs a reporting period.",
                    Map.of("period", "Required when contributionStatus is used."));
        }
        var spec = LoanSpecifications.filter(q.loanCode(), q.customerName(), q.search(), q.loanType(), q.loanStatus(), q.riskRating());

        if (q.contributionStatus() != null) {
            // Contribution is computed (not stored), so this filter is applied after calculating it: fetch every loan
            // matching the other filters, keep those with the requested status, then page the result.
            List<Loan> all = loans.findAll(spec, pageable.getSort());
            Map<Long, ProfitabilityOverviewService.LoanContribution> contributions =
                    overview.contributions(all.stream().map(Loan::getId).toList(), q.period());
            List<Loan> matching = all.stream()
                    .filter(l -> {
                        var c = contributions.get(l.getId());
                        return c != null && c.available() && c.result().status() == q.contributionStatus();
                    })
                    .toList();
            int from = (int) Math.min(pageable.getOffset(), matching.size());
            int to = Math.min(from + pageable.getPageSize(), matching.size());
            Page<Loan> page = new PageImpl<>(matching.subList(from, to), pageable, matching.size());
            return toPage(page, q.period(), contributions);
        }

        Page<Loan> page = loans.findAll(spec, pageable);
        Map<Long, ProfitabilityOverviewService.LoanContribution> contributions = q.period() == null ? Map.of()
                : overview.contributions(page.getContent().stream().map(Loan::getId).toList(), q.period());
        return toPage(page, q.period(), contributions);
    }

    private PageResponse<LoanResponse> toPage(Page<Loan> page, String period,
                                              Map<Long, ProfitabilityOverviewService.LoanContribution> contributions) {
        Map<Long, RiskRating> ratings = latestRatings(page.getContent());
        return PageResponse.of(page, page.getContent().stream()
                .map(l -> LoanMapper.toResponse(l, ratings.get(l.getId()),
                        period == null ? null : LoanMapper.toSummary(period, contributions.get(l.getId()))))
                .toList());
    }

    public LoanResponse get(String idOrCode) {
        Loan loan = requireLoan(idOrCode);
        return LoanMapper.toResponse(loan, latestRatings(List.of(loan)).get(loan.getId()));
    }

    @Transactional
    public LoanResponse create(LoanCreateRequest request) {
        Customer customer = customers.findById(request.customerId()).orElseThrow(() -> new BusinessRuleException(
                "The loan refers to a customer that does not exist.",
                Map.of("customerId", "Customer " + request.customerId() + " does not exist.")));

        if (loans.existsByLoanCode(request.loanCode())) {
            throw new ConflictException("A loan with code " + request.loanCode() + " already exists.");
        }
        LoanStatus status = request.loanStatus() == null ? LoanStatus.ACTIVE : request.loanStatus();
        checkBalances(request.originalPrincipal(), request.outstandingBalance(), status);

        Loan loan = new Loan();
        loan.setLoanCode(request.loanCode());
        loan.setCustomer(customer);
        loan.setLoanType(request.loanType());
        loan.setOriginalPrincipal(request.originalPrincipal());
        loan.setOutstandingBalance(request.outstandingBalance());
        loan.setInterestRate(request.interestRate());
        loan.setTenureMonths(request.tenureMonths());
        loan.setStartDate(request.startDate());
        loan.setMaturityDate(request.startDate().plusMonths(request.tenureMonths()));
        loan.setLoanStatus(status);
        Loan saved = loans.saveAndFlush(loan);
        return LoanMapper.toResponse(saved, null);
    }

    @Transactional
    public LoanResponse update(String idOrCode, LoanUpdateRequest request) {
        Loan loan = requireLoan(idOrCode);
        checkBalances(request.originalPrincipal(), request.outstandingBalance(), request.loanStatus());

        loan.setLoanType(request.loanType());
        loan.setOriginalPrincipal(request.originalPrincipal());
        loan.setOutstandingBalance(request.outstandingBalance());
        loan.setInterestRate(request.interestRate());
        loan.setTenureMonths(request.tenureMonths());
        loan.setStartDate(request.startDate());
        loan.setMaturityDate(request.startDate().plusMonths(request.tenureMonths()));
        loan.setLoanStatus(request.loanStatus());
        Loan saved = loans.saveAndFlush(loan);
        return LoanMapper.toResponse(saved, latestRatings(List.of(saved)).get(saved.getId()));
    }

    /**
     * Resolves a path variable that is either the numeric database id or the loan code (e.g. "L1001"),
     * so the frontend, which identifies loans by code, can use the same URLs.
     */
    public Loan requireLoan(String idOrCode) {
        if (idOrCode != null && idOrCode.matches("\\d{1,18}")) {
            return loans.findWithCustomerById(Long.parseLong(idOrCode))
                    .orElseThrow(() -> new ResourceNotFoundException("Loan " + idOrCode + " was not found."));
        }
        String code = idOrCode == null ? "" : idOrCode.trim().toUpperCase();
        return loans.findByLoanCode(code).orElseThrow(() -> new ResourceNotFoundException("Loan " + idOrCode + " was not found."));
    }

    private void checkBalances(BigDecimal principal, BigDecimal outstanding, LoanStatus status) {
        if (outstanding.compareTo(principal) > 0) {
            throw new BusinessRuleException("The outstanding balance cannot exceed the original principal.",
                    Map.of("outstandingBalance", "Cannot exceed the original principal."));
        }
        if (status == LoanStatus.CLOSED && outstanding.signum() != 0) {
            throw new BusinessRuleException("A closed loan must have a zero outstanding balance.",
                    Map.of("outstandingBalance", "Must be 0.00 for a closed loan."));
        }
    }

    private Map<Long, RiskRating> latestRatings(List<Loan> page) {
        if (page.isEmpty()) {
            return Map.of();
        }
        Map<Long, RiskRating> result = new HashMap<>();
        riskAssessments.findLatestRatings(page.stream().map(Loan::getId).collect(Collectors.toSet()))
                .forEach(r -> result.put(r.getLoanId(), r.getRiskRating()));
        return result;
    }
}
