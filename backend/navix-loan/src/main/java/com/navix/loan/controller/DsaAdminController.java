package com.navix.loan.controller;

import com.navix.common.web.ApiResponse;
import com.navix.loan.domain.DsaCommissionStatus;
import com.navix.loan.dto.DsaDtos.AdminDsaCommissionView;
import com.navix.loan.dto.DsaDtos.AdminDsaLeadView;
import com.navix.loan.dto.DsaDtos.AdminDsaRosterView;
import com.navix.loan.dto.DsaDtos.AdminOutreachView;
import com.navix.loan.dto.DsaDtos.AdminUpdateLeadRequest;
import com.navix.loan.dto.DsaDtos.CreateCommissionRequest;
import com.navix.loan.dto.DsaDtos.PayCommissionRequest;
import com.navix.loan.dto.DsaDtos.ReassignCommissionRequest;
import com.navix.loan.dto.DsaDtos.VoidCommissionRequest;
import com.navix.loan.service.DsaAdminService;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * ADMIN-only administration of the DSA program. {@code /api/admin/**} is already gated to
 * {@code ROLE_STAFF} in {@code SecurityConfig} (the token audience), which a DSA's own JWT also
 * satisfies — so {@link DsaAdminService} enforces its own {@code requireAdmin()} on every method,
 * exactly like the rest of the {@code /api/admin/**} surface.
 */
@RestController
@RequestMapping("/api/admin/dsa")
@RequiredArgsConstructor
public class DsaAdminController {

    private final DsaAdminService dsaAdminService;

    @GetMapping
    public ApiResponse<List<AdminDsaRosterView>> roster() {
        return ApiResponse.ok(dsaAdminService.roster());
    }

    @GetMapping("/leads")
    public ApiResponse<List<AdminDsaLeadView>> leads(
            @RequestParam(required = false) Long dsaId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String q) {
        return ApiResponse.ok(dsaAdminService.leads(dsaId, from, to, q));
    }

    @PutMapping("/leads/{id}")
    public ApiResponse<AdminDsaLeadView> correctLead(
            @PathVariable Long id, @Valid @RequestBody AdminUpdateLeadRequest req) {
        return ApiResponse.ok(dsaAdminService.correctLead(id, req));
    }

    @GetMapping("/commissions")
    public ApiResponse<List<AdminDsaCommissionView>> commissions(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long dsaId) {
        return ApiResponse.ok(dsaAdminService.commissions(parseStatus(status), dsaId));
    }

    @PostMapping("/commissions/{id}/pay")
    public ApiResponse<AdminDsaCommissionView> pay(
            @PathVariable Long id, @Valid @RequestBody PayCommissionRequest req) {
        return ApiResponse.ok(dsaAdminService.pay(id, req.txnRef()));
    }

    @PostMapping("/commissions/{id}/void")
    public ApiResponse<AdminDsaCommissionView> voidCommission(
            @PathVariable Long id, @Valid @RequestBody VoidCommissionRequest req) {
        return ApiResponse.ok(dsaAdminService.voidCommission(id, req.reason()));
    }

    @PostMapping("/commissions/{id}/reassign")
    public ApiResponse<AdminDsaCommissionView> reassign(
            @PathVariable Long id, @Valid @RequestBody ReassignCommissionRequest req) {
        return ApiResponse.ok(dsaAdminService.reassign(id, req.toDsaStaffId(), req.reason()));
    }

    @PostMapping("/commissions")
    public ApiResponse<AdminDsaCommissionView> createManual(@Valid @RequestBody CreateCommissionRequest req) {
        return ApiResponse.ok(dsaAdminService.createManual(req));
    }

    @GetMapping("/outreach")
    public ApiResponse<List<AdminOutreachView>> outreach(
            @RequestParam(required = false) Long dsaId,
            @RequestParam(required = false) Long leadId) {
        return ApiResponse.ok(dsaAdminService.outreach(dsaId, leadId));
    }

    private static DsaCommissionStatus parseStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return DsaCommissionStatus.valueOf(status.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
