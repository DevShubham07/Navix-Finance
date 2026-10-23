package com.navix.app.report;

import com.navix.app.report.ReportDtos.Report;
import com.navix.app.report.ReportDtos.ReportDef;
import com.navix.common.exception.BusinessException;
import com.navix.common.web.ApiResponse;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * ADMIN reports centre ({@link ReportService} enforces the role). {@code GET /api/reports} lists what
 * can be generated; {@code GET /api/reports/{key}?from=&to=} generates one for an inclusive IST period
 * ({@code yyyy-MM-dd}; both omitted = all time).
 */
@RestController
@RequestMapping("/api/reports")
@RequiredArgsConstructor
public class ReportController {

    private final ReportService service;

    @GetMapping
    public ApiResponse<List<ReportDef>> catalog() {
        return ApiResponse.ok(service.catalog());
    }

    @GetMapping("/{key}")
    public ApiResponse<Report> run(@PathVariable String key, @RequestParam(required = false) String from,
                                   @RequestParam(required = false) String to) {
        return ApiResponse.ok(service.run(key, date(from), date(to)));
    }

    private static LocalDate date(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return LocalDate.parse(s.trim());
        } catch (DateTimeParseException e) {
            throw new BusinessException("INVALID_DATE", "Dates must be yyyy-MM-dd");
        }
    }
}
