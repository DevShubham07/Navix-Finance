package com.navix.loan.controller;

import com.navix.common.web.ApiResponse;
import com.navix.loan.dto.LoanRegisterDtos.LoanRegisterRow;
import com.navix.common.query.SqlFacets;
import com.navix.loan.service.LoanRegisterService;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
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

    /**
     * One page of the register, narrowed and sorted server-side. {@code q} searches name / PAN / mobile
     * / loan id; {@code seg} is the lifecycle chip ({@code active|overdue|closed}); {@code from}/{@code to}
     * window {@code disbursedOn}; every {@code f.<column>} is a column filter (an unknown one is
     * dropped, never an error). {@code page} is 1-indexed and {@code size} is capped server-side.
     */
    @GetMapping("/page")
    public ApiResponse<LoanRegisterService.Page> page(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String seg,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String dir,
            @RequestParam Map<String, String> all) {
        return ApiResponse.ok(loanRegisterService.page(q, seg, from, to, all, sort, dir, page, size));
    }

    /**
     * The distinct values one filterable column can offer ({@code column}), under the OTHER columns'
     * {@code f.*} filters and the same search / window / chip as the page.
     */
    @GetMapping("/facets")
    public ApiResponse<SqlFacets.Facet> facets(
            @RequestParam("column") String column,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String seg,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam Map<String, String> all) {
        return ApiResponse.ok(loanRegisterService.facet(column, q, seg, from, to, all));
    }
}
