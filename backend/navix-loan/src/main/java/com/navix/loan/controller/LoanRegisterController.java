package com.navix.loan.controller;

import com.navix.common.web.ApiResponse;
import com.navix.loan.dto.LoanRegisterDtos.LoanRegisterRow;
import com.navix.loan.service.LoanRegisterService;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The staff loan register: every loan (any status), searchable and date-ranged. Deliberately
 * <b>plural</b> ({@code /api/loans}) and a separate controller from {@code LoanController}'s
 * <b>singular</b> {@code /api/loan} — that one reads a single disbursed loan's ledger; this one
 * rolls up every loan the platform has ever disbursed for ADMIN/COLLECTION_HEAD oversight.
 *
 * <p>Authorization (ADMIN unconditional bypass, else {@code COLLECTION_HEAD}, DSA explicitly
 * rejected) is enforced in {@link LoanRegisterService#list}, not here — see that class for why.
 */
@RestController
@RequestMapping("/api/loans")
@RequiredArgsConstructor
public class LoanRegisterController {

    private final LoanRegisterService loanRegisterService;

    @GetMapping
    public ApiResponse<List<LoanRegisterRow>> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ApiResponse.ok(loanRegisterService.list(status, q, from, to));
    }
}
