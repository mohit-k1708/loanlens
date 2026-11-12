package com.loanlens.config;

import com.loanlens.entity.AllocationMethod;
import com.loanlens.entity.CostCategory;
import com.loanlens.entity.Customer;
import com.loanlens.entity.EmploymentType;
import com.loanlens.entity.FinancialRecord;
import com.loanlens.entity.Loan;
import com.loanlens.entity.LoanStatus;
import com.loanlens.entity.LoanType;
import com.loanlens.entity.OperationalCostAllocation;
import com.loanlens.entity.RiskAssessment;
import com.loanlens.repository.CustomerRepository;
import com.loanlens.repository.FinancialRecordRepository;
import com.loanlens.repository.LoanRepository;
import com.loanlens.repository.OperationalCostAllocationRepository;
import com.loanlens.repository.RiskAssessmentRepository;
import com.loanlens.service.MoneyRules;
import com.loanlens.service.RiskRatingPolicy;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Optional demo data (loanlens.seed.enabled=true). Loads the same 13 FICTITIOUS loans as the frontend demo so the
 * backend's results can be compared with the frontend's. Safe to run repeatedly: loans that exist are skipped.
 * All amounts are for reporting period 2026. Never enabled by default.
 */
@Component
@ConditionalOnProperty(name = "loanlens.seed.enabled", havingValue = "true")
public class SampleDataSeeder implements ApplicationRunner {

    public static final String PERIOD = "2026";
    private static final Logger log = LoggerFactory.getLogger(SampleDataSeeder.class);

    /** Demonstration activity-cost rates per loan type (INR): processing, per contact, servicing per 1,000 of balance. */
    private record AbcRates(String processing, String perContact, String servicingPerThousand) {
    }

    private static final Map<LoanType, AbcRates> ABC = new EnumMap<>(Map.of(
            LoanType.PERSONAL, new AbcRates("500", "50", "12.70"),
            LoanType.HOME, new AbcRates("2500", "50", "2"),
            LoanType.AUTO, new AbcRates("800", "50", "6"),
            LoanType.BUSINESS, new AbcRates("5000", "100", "4"),
            LoanType.EDUCATION, new AbcRates("600", "50", "3.5"),
            LoanType.GOLD, new AbcRates("400", "50", "3")));

    /** Percentages are written as percentages here (10 = 10%) and converted to decimal fractions on load. */
    private record Seed(String loanCode, String customerCode, String name, EmploymentType employment, String annualIncome,
                        LoanType type, String principal, String outstanding, String ratePct, int tenure, String start,
                        LoanStatus status, String interestIncome, String feeIncome, String avgBalance, String fundingRatePct,
                        int contacts, String pdPct, String lgdPct) {
    }

    private static final List<Seed> DATA = List.of(
            new Seed("L1001", "C2001", "Rahul Sharma", EmploymentType.SALARIED, "900000", LoanType.PERSONAL, "500000", "500000", "10", 60, "2025-04-01", LoanStatus.ACTIVE, "60000", "2000", "500000", "7", 3, "10", "60"),
            new Seed("L1002", "C2002", "Priya Nair", EmploymentType.PROFESSIONAL, "2400000", LoanType.HOME, "4500000", "4120000", "8.75", 240, "2023-07-15", LoanStatus.ACTIVE, "372400", "9000", "4250000", "7", 3, "1.2", "25"),
            new Seed("L1003", "C2003", "Amit Verma", EmploymentType.SALARIED, "1100000", LoanType.AUTO, "900000", "610000", "9.5", 60, "2024-02-10", LoanStatus.ACTIVE, "66000", "4500", "700000", "7.2", 4, "3.5", "45"),
            new Seed("L1004", "C2004", "Sneha Iyer", EmploymentType.BUSINESS_OWNER, "3800000", LoanType.BUSINESS, "2500000", "2150000", "12.5", 48, "2024-09-01", LoanStatus.ACTIVE, "287000", "25000", "2300000", "7.5", 12, "4.5", "50"),
            new Seed("L1005", "C2005", "Vikram Singh", EmploymentType.BUSINESS_OWNER, "9500000", LoanType.BUSINESS, "8000000", "7400000", "11.75", 60, "2024-05-20", LoanStatus.UNDER_WATCH, "905000", "60000", "7700000", "7.5", 24, "9", "55"),
            new Seed("L1006", "C2006", "Ananya Desai", EmploymentType.OTHER, "300000", LoanType.EDUCATION, "1200000", "1050000", "9", 84, "2024-08-01", LoanStatus.ACTIVE, "98500", "1500", "1100000", "7", 4, "2", "45"),
            new Seed("L1007", "C2007", "Rohan Mehta", EmploymentType.SELF_EMPLOYED, "650000", LoanType.PERSONAL, "300000", "240000", "14", 36, "2025-01-05", LoanStatus.ACTIVE, "37000", "3500", "270000", "7", 6, "12", "60"),
            new Seed("L1008", "C2008", "Kavita Reddy", EmploymentType.PROFESSIONAL, "3100000", LoanType.HOME, "7500000", "7150000", "8.5", 300, "2025-02-01", LoanStatus.ACTIVE, "615000", "30000", "7300000", "7", 3, "1", "20"),
            new Seed("L1009", "C2009", "Suresh Patil", EmploymentType.SELF_EMPLOYED, "720000", LoanType.GOLD, "400000", "400000", "11", 12, "2025-03-20", LoanStatus.ACTIVE, "38000", "2400", "380000", "7", 2, "1.5", "15"),
            new Seed("L1010", "C2010", "Neha Gupta", EmploymentType.SALARIED, "840000", LoanType.PERSONAL, "750000", "670000", "12", 48, "2024-11-12", LoanStatus.UNDER_WATCH, "85500", "6500", "700000", "7", 6, "14", "60"),
            new Seed("L1011", "C2011", "Farhan Ali", EmploymentType.SALARIED, "1500000", LoanType.AUTO, "1400000", "1180000", "9.25", 72, "2024-06-18", LoanStatus.ACTIVE, "117000", "7000", "1260000", "7.2", 4, "5", "45"),
            new Seed("L1012", "C2012", "Meera Joshi", EmploymentType.RETIRED, "1200000", LoanType.HOME, "3200000", "2780000", "8.9", 180, "2022-12-05", LoanStatus.ACTIVE, "258000", "6000", "2880000", "7", 3, "2.5", "25"),
            new Seed("L1013", "C2013", "Aman Khanna", EmploymentType.SALARIED, "960000", LoanType.PERSONAL, "500000", "450000", "10.5", 60, "2024-10-01", LoanStatus.ACTIVE, "52000", "2000", "480000", "7", 4, "3", "60"));

    private final CustomerRepository customers;
    private final LoanRepository loans;
    private final FinancialRecordRepository records;
    private final RiskAssessmentRepository assessments;
    private final OperationalCostAllocationRepository allocations;
    private final RiskRatingPolicy ratingPolicy;

    public SampleDataSeeder(CustomerRepository customers, LoanRepository loans, FinancialRecordRepository records,
                            RiskAssessmentRepository assessments, OperationalCostAllocationRepository allocations,
                            RiskRatingPolicy ratingPolicy) {
        this.customers = customers;
        this.loans = loans;
        this.records = records;
        this.assessments = assessments;
        this.allocations = allocations;
        this.ratingPolicy = ratingPolicy;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        int created = 0;
        for (Seed s : DATA) {
            if (loans.existsByLoanCode(s.loanCode())) {
                continue;
            }
            seed(s);
            created++;
        }
        log.info("Sample data seeding finished: {} loan(s) created, {} already present (reporting period {}).",
                created, DATA.size() - created, PERIOD);
    }

    private void seed(Seed s) {
        Customer customer = new Customer();
        customer.setCustomerCode(s.customerCode());
        customer.setFullName(s.name());
        customer.setEmail(s.name().toLowerCase().replace(' ', '.') + "@example.com");
        customer.setPhone("+91 90000 0" + s.customerCode().substring(1));
        customer.setEmploymentType(s.employment());
        customer.setAnnualIncome(new BigDecimal(s.annualIncome()).setScale(2));
        customer = customers.save(customer);

        Loan loan = new Loan();
        loan.setLoanCode(s.loanCode());
        loan.setCustomer(customer);
        loan.setLoanType(s.type());
        loan.setOriginalPrincipal(new BigDecimal(s.principal()).setScale(2));
        loan.setOutstandingBalance(new BigDecimal(s.outstanding()).setScale(2));
        loan.setInterestRate(fraction(s.ratePct()));
        loan.setTenureMonths(s.tenure());
        LocalDate start = LocalDate.parse(s.start());
        loan.setStartDate(start);
        loan.setMaturityDate(start.plusMonths(s.tenure()));
        loan.setLoanStatus(s.status());
        loan = loans.save(loan);

        // Activity-based operational cost lines; their sum is the record's operational cost.
        AbcRates rates = ABC.get(s.type());
        BigDecimal avg = new BigDecimal(s.avgBalance());
        BigDecimal processing = MoneyRules.round(new BigDecimal(rates.processing()));
        BigDecimal support = MoneyRules.round(new BigDecimal(rates.perContact()).multiply(BigDecimal.valueOf(s.contacts())));
        BigDecimal servicing = MoneyRules.round(avg.divide(new BigDecimal("1000")).multiply(new BigDecimal(rates.servicingPerThousand())));
        addAllocation(loan, CostCategory.LOAN_PROCESSING, "Loan applications processed (1)", processing);
        addAllocation(loan, CostCategory.CUSTOMER_SUPPORT, "Customer service contacts (" + s.contacts() + ")", support);
        addAllocation(loan, CostCategory.LOAN_SERVICING, "INR 1,000 of average outstanding balance", servicing);

        BigDecimal fundingRate = fraction(s.fundingRatePct());
        FinancialRecord record = new FinancialRecord();
        record.setLoan(loan);
        record.setReportingPeriod(PERIOD);
        record.setInterestIncome(new BigDecimal(s.interestIncome()).setScale(2));
        record.setFeeIncome(new BigDecimal(s.feeIncome()).setScale(2));
        record.setAverageOutstandingBalance(MoneyRules.round(avg));
        record.setFundingRate(fundingRate);
        record.setFundingCost(MoneyRules.round(avg.multiply(fundingRate)));
        record.setOperationalCost(processing.add(support).add(servicing));
        records.save(record);

        BigDecimal pd = fraction(s.pdPct());
        RiskAssessment risk = new RiskAssessment();
        risk.setLoan(loan);
        risk.setReportingPeriod(PERIOD);
        risk.setProbabilityOfDefault(pd);
        risk.setLossGivenDefault(fraction(s.lgdPct()));
        risk.setExposureAtDefault(new BigDecimal(s.outstanding()).setScale(2));
        risk.setRiskRating(ratingPolicy.rate(pd));
        risk.setAssessedAt(Instant.now());
        assessments.save(risk);
    }

    private void addAllocation(Loan loan, CostCategory category, String driver, BigDecimal amount) {
        OperationalCostAllocation a = new OperationalCostAllocation();
        a.setLoan(loan);
        a.setReportingPeriod(PERIOD);
        a.setCostCategory(category);
        a.setAllocationMethod(AllocationMethod.ACTIVITY_BASED_COSTING);
        a.setAllocationDriver(driver);
        a.setAllocatedAmount(amount);
        a.setSourceDescription("Demonstration cost rates (fictitious).");
        allocations.save(a);
    }

    /** 8.75 (percent) -> 0.087500 (decimal fraction). */
    private static BigDecimal fraction(String percent) {
        return new BigDecimal(percent).movePointLeft(2).setScale(6);
    }
}
