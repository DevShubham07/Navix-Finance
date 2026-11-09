package com.navix.app.bsa;

import com.navix.app.bsa.BankStatementAnalysisService.BankAnalysisView;
import com.navix.common.web.ApiResponse;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Banking tab's bank-statement-analysis calls. Mounted under {@code /api/staff/**}, which
 * {@code SecurityConfig} already gates on the staff audience; who may <i>run</i> (the credit desk +
 * ADMIN) versus merely <i>read</i> (any staff but DSA) is decided in
 * {@link BankStatementAnalysisService}, beside the rule it enforces.
 */
@RestController
@RequestMapping("/api/staff/bank-analysis")
@RequiredArgsConstructor
public class BankStatementAnalysisController {

    private final BankStatementAnalysisService service;

    /** The latest analysis, advancing it if Signzy is still working. Null when none has been run. */
    @GetMapping("/customers/{customerId}")
    public ApiResponse<BankAnalysisView> latest(@PathVariable Long customerId) {
        return ApiResponse.ok(service.latest(customerId));
    }

    /** Every run for this customer, newest first — no provider call. */
    @GetMapping("/customers/{customerId}/history")
    public ApiResponse<List<BankAnalysisView>> history(@PathVariable Long customerId) {
        return ApiResponse.ok(service.history(customerId));
    }

    /** One billable submission of the customer's statements. */
    @PostMapping("/customers/{customerId}")
    public ApiResponse<BankAnalysisView> run(@PathVariable Long customerId) {
        return ApiResponse.ok(service.run(customerId));
    }
}
