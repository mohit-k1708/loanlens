package com.loanlens.controller;

import com.loanlens.service.ReportingPeriodService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/reporting-periods")
public class ReportingPeriodController {

    private final ReportingPeriodService service;

    public ReportingPeriodController(ReportingPeriodService service) {
        this.service = service;
    }

    /** Reporting periods that have data, newest first, e.g. ["2026","2025"]. */
    @GetMapping
    public List<String> list() {
        return service.availablePeriods();
    }
}
