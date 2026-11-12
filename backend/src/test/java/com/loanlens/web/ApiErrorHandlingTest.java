package com.loanlens.web;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.loanlens.controller.LoanController;
import com.loanlens.exception.BusinessRuleException;
import com.loanlens.exception.ConflictException;
import com.loanlens.exception.GlobalExceptionHandler;
import com.loanlens.exception.ResourceNotFoundException;
import com.loanlens.service.LoanService;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** Validation and error-contract tests for the HTTP layer (the service is mocked). */
@WebMvcTest(LoanController.class)
@Import(GlobalExceptionHandler.class)
class ApiErrorHandlingTest {

    @Autowired MockMvc mvc;
    @MockitoBean LoanService loanService;

    private static final String VALID = """
            {"loanCode":"L1001","customerId":1,"loanType":"PERSONAL","originalPrincipal":500000.00,
             "outstandingBalance":500000.00,"interestRate":0.10,"tenureMonths":60,"startDate":"2025-04-01"}""";

    private ResultActions postLoan(String json) throws Exception {
        return mvc.perform(post("/api/loans").contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private static String with(String field, String jsonValue) {
        return VALID.replaceAll("\"" + field + "\":[^,}]+", "\"" + field + "\":" + jsonValue);
    }

    @Test
    @DisplayName("negative principal is rejected before reaching the service")
    void negativePrincipal() throws Exception {
        postLoan(with("originalPrincipal", "-1.00"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.fieldErrors.originalPrincipal").value("Original principal must be greater than zero."))
                .andExpect(jsonPath("$.message").exists());
        verifyNoInteractions(loanService);
    }

    @Test
    void zeroPrincipal() throws Exception {
        postLoan(with("originalPrincipal", "0")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.originalPrincipal").exists());
    }

    @Test
    void negativeOutstandingBalance() throws Exception {
        postLoan(with("outstandingBalance", "-5.00")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.outstandingBalance").value("Outstanding balance cannot be negative."));
    }

    @Test
    @DisplayName("an interest rate sent as a percentage (10 instead of 0.10) gets a helpful message")
    void interestRateAsPercentage() throws Exception {
        postLoan(with("interestRate", "10")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.interestRate").value(containsString("decimal fraction")));
    }

    @Test
    void zeroAndNegativeInterestRate() throws Exception {
        postLoan(with("interestRate", "0")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors.interestRate").exists());
        postLoan(with("interestRate", "-0.05")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors.interestRate").exists());
    }

    @Test
    void moreThanTwoDecimalPlacesOnMoneyIsRejectedNotRounded() throws Exception {
        postLoan(with("originalPrincipal", "500000.005")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.originalPrincipal").value(containsString("2 decimal places")));
    }

    @Test
    void futureStartDateAndBadTenure() throws Exception {
        postLoan(with("startDate", "\"2999-01-01\"")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors.startDate").exists());
        postLoan(with("tenureMonths", "0")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors.tenureMonths").exists());
        postLoan(with("tenureMonths", "481")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors.tenureMonths").exists());
    }

    @Test
    void badLoanCodeAndMissingFieldsAreAllReportedTogether() throws Exception {
        postLoan("{\"loanCode\":\"1001\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.loanCode").exists())
                .andExpect(jsonPath("$.fieldErrors.customerId").exists())
                .andExpect(jsonPath("$.fieldErrors.originalPrincipal").exists())
                .andExpect(jsonPath("$.fieldErrors.interestRate").exists());
    }

    @Test
    void unknownFieldIsRejected() throws Exception {
        postLoan(VALID.replace("{", "{\"intrestRate\":0.1,")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("Unknown field 'intrestRate'")));
    }

    @Test
    void invalidEnumValueListsTheAllowedOnes() throws Exception {
        postLoan(with("loanType", "\"YACHT\"")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.loanType").value(containsString("PERSONAL")));
    }

    @Test
    void malformedJson() throws Exception {
        postLoan("{not json").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("The request body is missing or is not valid JSON."));
    }

    @Test
    void wrongContentType() throws Exception {
        mvc.perform(post("/api/loans").contentType(MediaType.TEXT_PLAIN).content(VALID)).andExpect(status().isUnsupportedMediaType());
    }

    @Test
    void invalidEnumQueryParameter() throws Exception {
        mvc.perform(get("/api/loans").param("loanType", "YACHT")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.loanType").value(containsString("Allowed values")));
    }

    @Test
    void unsupportedMethod() throws Exception {
        mvc.perform(delete("/api/loans/1")).andExpect(status().isMethodNotAllowed());
    }

    @Test
    void serviceErrorsMapToTheRightStatus() throws Exception {
        when(loanService.get("L404")).thenThrow(new ResourceNotFoundException("Loan L404 was not found."));
        mvc.perform(get("/api/loans/L404")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Loan L404 was not found."))
                .andExpect(jsonPath("$.path").value("/api/loans/L404"));

        when(loanService.create(any())).thenThrow(new ConflictException("A loan with code L1001 already exists."));
        postLoan(VALID).andExpect(status().isConflict()).andExpect(jsonPath("$.status").value(409));

        // doThrow().when(): re-stubbing with when(...) would invoke the previously stubbed exception.
        doThrow(new BusinessRuleException("The loan refers to a customer that does not exist.",
                Map.of("customerId", "Customer 1 does not exist."))).when(loanService).create(any());
        postLoan(VALID).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.fieldErrors.customerId").exists());
    }

    @Test
    @DisplayName("unexpected errors return a generic message and an errorId, never internal details")
    void unexpectedErrorLeaksNothing() throws Exception {
        when(loanService.get("L1")).thenThrow(new IllegalStateException("jdbc:postgresql://secret-host/loanlens_db password=hunter2"));

        mvc.perform(get("/api/loans/L1"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("An unexpected error occurred. Quote the errorId when reporting it."))
                .andExpect(jsonPath("$.errorId").exists())
                .andExpect(content().string(not(containsString("hunter2"))))
                .andExpect(content().string(not(containsString("secret-host"))))
                .andExpect(content().string(not(containsString("IllegalStateException"))))
                .andExpect(content().string(not(containsString("at com.loanlens"))));
    }
}
