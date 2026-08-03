package com.navix.loan.controller;

import com.navix.common.web.ApiResponse;
import com.navix.loan.dto.LeadDtos.CreateLeadRequest;
import com.navix.loan.dto.LeadDtos.DispositionRequest;
import com.navix.loan.dto.LeadDtos.LeadStats;
import com.navix.loan.dto.LeadDtos.LeadView;
import com.navix.loan.dto.LeadDtos.UpdateLeadRequest;
import com.navix.loan.service.LeadService;
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
 * Telecaller leads API — create/list/update/disposition for TELECALLER+ADMIN;
 * {@code /stats} tracker aggregates for ADMIN.
 */
@RestController
@RequestMapping("/api/leads")
@RequiredArgsConstructor
public class LeadController {

    private final LeadService leadService;

    @PostMapping
    public ApiResponse<LeadView> create(@Valid @RequestBody CreateLeadRequest req) {
        return ApiResponse.ok(leadService.create(req));
    }

    @GetMapping
    public ApiResponse<List<LeadView>> list(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String callStatus,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) Long createdBy,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Integer minRating,
            @RequestParam(required = false) Integer maxRating) {
        return ApiResponse.ok(leadService.list(
                q, callStatus, source, createdBy, from, to, minRating, maxRating));
    }

    @GetMapping("/stats")
    public ApiResponse<LeadStats> stats(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long createdBy) {
        return ApiResponse.ok(leadService.stats(from, to, createdBy));
    }

    @GetMapping("/{id}")
    public ApiResponse<LeadView> get(@PathVariable Long id) {
        return ApiResponse.ok(leadService.get(id));
    }

    @PutMapping("/{id}")
    public ApiResponse<LeadView> update(
            @PathVariable Long id, @Valid @RequestBody UpdateLeadRequest req) {
        return ApiResponse.ok(leadService.update(id, req));
    }

    @PutMapping("/{id}/disposition")
    public ApiResponse<LeadView> disposition(
            @PathVariable Long id, @Valid @RequestBody DispositionRequest req) {
        return ApiResponse.ok(leadService.disposition(id, req));
    }
}
