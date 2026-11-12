package com.loanlens.service;

import com.loanlens.repository.FinancialRecordRepository;
import com.loanlens.repository.RiskAssessmentRepository;
import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Which reporting periods have any data. Lets a client offer only periods that can actually be shown. */
@Service
@Transactional(readOnly = true)
public class ReportingPeriodService {

    private final FinancialRecordRepository financialRecords;
    private final RiskAssessmentRepository riskAssessments;

    public ReportingPeriodService(FinancialRecordRepository financialRecords, RiskAssessmentRepository riskAssessments) {
        this.financialRecords = financialRecords;
        this.riskAssessments = riskAssessments;
    }

    /** Periods with a financial record or a risk assessment, newest first. */
    public List<String> availablePeriods() {
        TreeSet<String> periods = new TreeSet<>(Comparator.reverseOrder());
        periods.addAll(financialRecords.findDistinctPeriods());
        periods.addAll(riskAssessments.findDistinctPeriods());
        return List.copyOf(periods);
    }
}
