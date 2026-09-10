package com.navix.loan.controller;

import com.navix.common.web.ApiResponse;
import com.navix.loan.dto.LeadDtos.CreateLeadRequest;
import com.navix.loan.dto.LeadDtos.DispositionRequest;
import com.navix.loan.dto.LeadDtos.ImportFileRequest;
import com.navix.loan.dto.LeadDtos.ImportJobView;
import com.navix.loan.dto.LeadDtos.LeadStats;
import com.navix.loan.dto.LeadDtos.LeadView;
import com.navix.loan.dto.LeadDtos.UpdateLeadRequest;
import com.navix.loan.service.LeadImportJobService;
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
 * {@code /stats} tracker aggregates for ADMIN; {@code /import*} the bulk lead import, which ANY
 * staff role may use (guards live in the services, not here).
 */
@RestController
@RequestMapping("/api/leads")
@RequiredArgsConstructor
public class LeadController {

    private final LeadService leadService;
    private final LeadImportJobService leadImportJobService;

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

    /**
     * Start an import of a file already uploaded to S3. The body carries the key, never the rows —
     * a 200k-row list is ~12 MB and would not survive the hop to here (the BFF buffers the whole
     * body and the platform caps it at 4.5 MB).
     */
    @PostMapping("/import/file")
    public ApiResponse<ImportJobView> importFile(@Valid @RequestBody ImportFileRequest req) {
        return ApiResponse.ok(leadImportJobService.start(req));
    }

    /** Poll one import's progress. */
    @GetMapping("/import/jobs/{id}")
    public ApiResponse<ImportJobView> importJob(@PathVariable Long id) {
        return ApiResponse.ok(leadImportJobService.get(id));
    }

    /** The caller's own recent imports. */
    @GetMapping("/import/jobs")
    public ApiResponse<List<ImportJobView>> importJobs() {
        return ApiResponse.ok(leadImportJobService.mine());
    }
}
