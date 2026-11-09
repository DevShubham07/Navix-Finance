package com.navix.app.report;

import com.navix.app.report.CustomerExportService.Catalog;
import com.navix.app.report.CustomerExportService.MatchRequest;
import com.navix.app.report.CustomerExportService.MatchResult;
import com.navix.app.report.CustomerExportService.RowsRequest;
import com.navix.app.report.CustomerExportService.RowsResult;
import com.navix.common.web.ApiError;
import com.navix.common.web.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * ADMIN customer data export ({@link CustomerExportService} enforces the role on the real JWT role).
 * Mapped under {@code /api/reports/customer-export}; the literal path wins over
 * {@code ReportController}'s {@code /{key}}.
 */
@RestController
@RequestMapping("/api/reports/customer-export")
@RequiredArgsConstructor
public class CustomerExportController {

    private final CustomerExportService service;

    @GetMapping("/fields")
    public ApiResponse<Catalog> fields() {
        return ApiResponse.ok(service.catalog());
    }

    @PostMapping("/match")
    public ApiResponse<MatchResult> match(@RequestBody MatchRequest request) {
        return ApiResponse.ok(service.match(request));
    }

    @PostMapping("/rows")
    public ApiResponse<RowsResult> rows(@RequestBody RowsRequest request) {
        return ApiResponse.ok(service.rows(request));
    }

    /** A bad request is a 400 and a busy export a 503, rather than the 422 every other business rejection gets. */
    @ExceptionHandler(CustomerExportException.class)
    public ResponseEntity<ApiResponse<Void>> handle(CustomerExportException ex, HttpServletRequest request) {
        ApiError error = ApiError.builder().code(ex.getCode()).message(ex.getMessage())
                .path(request.getRequestURI()).build();
        return ResponseEntity.status(ex.status()).body(ApiResponse.failure(error));
    }
}
