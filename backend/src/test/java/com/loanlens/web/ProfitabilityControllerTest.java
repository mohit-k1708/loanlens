package com.loanlens.web;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.loanlens.controller.ProfitabilityController;
import com.loanlens.dto.ProfitabilityResponse;
import com.loanlens.dto.ProfitabilityResponse.ContributionStatus;
import com.loanlens.entity.LoanStatus;
import com.loanlens.entity.LoanType;
import com.loanlens.entity.RiskRating;
import com.loanlens.exception.BadRequestException;
import com.loanlens.exception.BusinessRuleException;
import com.loanlens.exception.GlobalExceptionHandler;
import com.loanlens.exception.MissingInputsException;
import com.loanlens.exception.ResourceNotFoundException;
import com.loanlens.service.ProfitabilityService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** HTTP contract of GET /api/loans/{id}/profitability (the service is mocked; the maths is tested elsewhere). */
@WebMvcTest(ProfitabilityController.class)
@Import(GlobalExceptionHandler.class)
class ProfitabilityControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean ProfitabilityService service;

    private static ProfitabilityResponse rahul() {
        return new ProfitabilityResponse(
                new ProfitabilityResponse.LoanInfo(10L, "L1001", "C2001", "Rahul Sharma", LoanType.PERSONAL, LoanStatus.ACTIVE,
                        new BigDecimal("500000.00"), new BigDecimal("500000.00"), new BigDecimal("0.100000")),
                "2026",
                new BigDecimal("60000.00"), new BigDecimal("2000.00"), new BigDecimal("62000.00"),
                new BigDecimal("500000.00"), new BigDecimal("0.070000"), new BigDecimal("35000.00"), new BigDecimal("7000.00"),
                new BigDecimal("42000.00"), new BigDecimal("20000.00"),
                new ProfitabilityResponse.RiskInfo(new BigDecimal("0.100000"), new BigDecimal("0.600000"), new BigDecimal("500000.00"), RiskRating.HIGH),
                new BigDecimal("30000.00"), new BigDecimal("72000.00"), new BigDecimal("-10000.00"), ContributionStatus.NEGATIVE,
                List.of(), new ProfitabilityResponse.SnapshotInfo(77L, "SIMPLIFIED-1.0", Instant.parse("2026-12-31T10:15:30Z"), true),
                List.of("Expected Credit Loss is an ESTIMATE of potential future credit loss (PD x LGD x EAD). It is not a realised loss."));
    }

    @Test
    @DisplayName("Rahul / 2026: every figure is returned as a plain 2-decimal number, before and after ECL kept separate")
    void rahulResponse() throws Exception {
        when(service.calculate("L1001", "2026")).thenReturn(rahul());

        mvc.perform(get("/api/loans/L1001/profitability").param("period", "2026"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.loan.loanCode").value("L1001"))
                .andExpect(jsonPath("$.reportingPeriod").value("2026"))
                .andExpect(jsonPath("$.totalIncome").value(62000.00))
                .andExpect(jsonPath("$.fundingCost").value(35000.00))
                .andExpect(jsonPath("$.operationalCost").value(7000.00))
                .andExpect(jsonPath("$.contributionBeforeEcl").value(20000.00))
                .andExpect(jsonPath("$.expectedCreditLoss").value(30000.00))
                .andExpect(jsonPath("$.contributionAfterEcl").value(-10000.00))
                .andExpect(jsonPath("$.contributionStatus").value("NEGATIVE"))
                .andExpect(jsonPath("$.averageOutstandingBalance").value(500000.00))
                .andExpect(jsonPath("$.risk.riskRating").value("HIGH"))
                .andExpect(jsonPath("$.snapshot.newlyCreated").value(true))
                // never worded as a realised loss
                .andExpect(jsonPath("$.notes[*]", hasItem(containsString("not a realised loss"))));
    }

    @Test
    @DisplayName("money is serialised as plain decimals with 2 places (no exponent, no float rounding)")
    void plainDecimals() throws Exception {
        when(service.calculate("L1001", "2026")).thenReturn(rahul());

        String json = mvc.perform(get("/api/loans/L1001/profitability").param("period", "2026"))
                .andReturn().getResponse().getContentAsString();

        org.assertj.core.api.Assertions.assertThat(json)
                .contains("\"totalIncome\":62000.00")
                .contains("\"contributionAfterEcl\":-10000.00")
                .doesNotContain("E+").doesNotContain("e+");
    }

    @Test
    void periodIsRequired() throws Exception {
        mvc.perform(get("/api/loans/L1001/profitability")).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void invalidPeriodIsABadRequest() throws Exception {
        when(service.calculate(eq("L1001"), eq("26"))).thenThrow(new BadRequestException("Reporting period must be a 4-digit year.", java.util.Map.of()));

        mvc.perform(get("/api/loans/L1001/profitability").param("period", "26"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void unknownLoanIs404() throws Exception {
        when(service.calculate("NOPE", "2026")).thenThrow(new ResourceNotFoundException("Loan NOPE was not found."));

        mvc.perform(get("/api/loans/NOPE/profitability").param("period", "2026"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Loan NOPE was not found."));
    }

    @Test
    @DisplayName("missing inputs: 422 listing what is missing, with no money figures (never zero)")
    void missingInputsIs422() throws Exception {
        when(service.calculate("L1001", "2027")).thenThrow(new MissingInputsException(
                "Cannot calculate profitability for loan L1001 and reporting period 2027 because required inputs are missing.",
                List.of("Financial record for reporting period 2027", "Risk assessment for reporting period 2027")));

        mvc.perform(get("/api/loans/L1001/profitability").param("period", "2027"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.missingInputs.length()").value(2))
                .andExpect(jsonPath("$.contributionAfterEcl").doesNotExist())
                .andExpect(jsonPath("$.totalIncome").doesNotExist());
    }

    @Test
    void allocationMismatchIs422WithoutLeakingInternals() throws Exception {
        when(service.calculate("L1001", "2026")).thenThrow(new BusinessRuleException(
                "The operational cost allocations total 6500.00 but the financial record's operational cost is 7000.00."));

        mvc.perform(get("/api/loans/L1001/profitability").param("period", "2026"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(containsString("6500.00")))
                .andExpect(jsonPath("$.trace").doesNotExist());
    }
}
