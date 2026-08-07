package com.navix.loan.controller;

import com.navix.common.exception.BusinessException;
import com.navix.common.security.ActorContext;
import com.navix.common.staff.StaffSummary;
import com.navix.common.web.ApiResponse;
import com.navix.loan.service.DecisionHistoryService;
import com.navix.loan.service.DecisionHistoryService.DecisionView;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Decision history (revamp.md decision 32) — what a staffer has decided, off the application-event
 * trail. Deliberately NOT under {@code StaffController}: that whole namespace is ADMIN-only, and
 * every staffer must be able to read their own history.
 */
@RestController
@RequestMapping("/api/staff/decisions")
@RequiredArgsConstructor
public class DecisionHistoryController {

    private final DecisionHistoryService decisions;

    @GetMapping
    public ApiResponse<List<DecisionView>> list(@RequestParam(required = false) Long staffId) {
        requireStaff();
        return ApiResponse.ok(decisions.decisions(staffId));
    }

    /** Staff whose history the caller may open (a Head's team; everything for ADMIN). */
    @GetMapping("/inspectable")
    public ApiResponse<List<StaffSummary>> inspectable() {
        requireStaff();
        return ApiResponse.ok(decisions.inspectable());
    }

    private void requireStaff() {
        String role = ActorContext.get().role();
        if (role == null || "BORROWER".equals(role) || "ANONYMOUS".equals(role)) {
            throw new BusinessException("FORBIDDEN_ROLE", "Staff only");
        }
    }
}
