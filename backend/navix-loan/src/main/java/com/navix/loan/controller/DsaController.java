package com.navix.loan.controller;

import com.navix.common.web.ApiResponse;
import com.navix.loan.dto.DsaDtos.CreateDsaLeadRequest;
import com.navix.loan.dto.DsaDtos.DsaCommissionView;
import com.navix.loan.dto.DsaDtos.DsaEarningsSummary;
import com.navix.loan.dto.DsaDtos.DsaLeadView;
import com.navix.loan.dto.DsaDtos.OutreachRequest;
import com.navix.loan.dto.DsaDtos.OutreachResultView;
import com.navix.loan.dto.DsaDtos.UpdateDsaLeadRequest;
import com.navix.loan.service.DsaService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The DSA portal API — lead intake, own-leads, outreach, and the DSA's own commission/earnings
 * view. Every action is scoped to the caller ({@link DsaService} resolves the DSA id from
 * {@code ActorContext}, never a request parameter); {@code SecurityConfig} gates the whole
 * namespace to staff, and {@code DsaService.requireDsaId} narrows it to the DSA role specifically.
 */
@RestController
@RequestMapping("/api/dsa")
@RequiredArgsConstructor
public class DsaController {

    private final DsaService dsaService;

    @PostMapping("/leads")
    public ApiResponse<DsaLeadView> createLead(@Valid @RequestBody CreateDsaLeadRequest req) {
        return ApiResponse.ok(dsaService.createLead(req));
    }

    @GetMapping("/leads")
    public ApiResponse<List<DsaLeadView>> list(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String status) {
        return ApiResponse.ok(dsaService.list(q, status));
    }

    @GetMapping("/leads/{id}")
    public ApiResponse<DsaLeadView> get(@PathVariable Long id) {
        return ApiResponse.ok(dsaService.get(id));
    }

    @PutMapping("/leads/{id}")
    public ApiResponse<DsaLeadView> update(
            @PathVariable Long id, @Valid @RequestBody UpdateDsaLeadRequest req) {
        return ApiResponse.ok(dsaService.update(id, req));
    }

    @PostMapping("/leads/{id}/outreach")
    public ApiResponse<OutreachResultView> outreach(
            @PathVariable Long id, @Valid @RequestBody OutreachRequest req) {
        return ApiResponse.ok(dsaService.outreach(id, req));
    }

    @GetMapping("/commissions")
    public ApiResponse<List<DsaCommissionView>> commissions() {
        return ApiResponse.ok(dsaService.commissions());
    }

    @GetMapping("/earnings")
    public ApiResponse<DsaEarningsSummary> earnings() {
        return ApiResponse.ok(dsaService.earnings());
    }
}
