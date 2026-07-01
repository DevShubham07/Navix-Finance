package com.navix.loan.controller;

import com.navix.common.web.ApiResponse;
import com.navix.loan.dto.CustomerDtos.CustomerDetail;
import com.navix.loan.dto.CustomerDtos.CustomerSummary;
import com.navix.loan.dto.CustomerDtos.ProfileChangeView;
import com.navix.loan.dto.CustomerDtos.UpdateCustomerRequest;
import com.navix.loan.dto.ReviewDtos.ProfileView;
import com.navix.loan.service.CustomerService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Staff-facing customer (borrower-centric) endpoints: list/search distinct customers, read one
 * customer's full history, and ADMIN-correct their KYC data. Reads are open to any signed-in staff
 * actor (the BFF injects the staff identity); the profile update is ADMIN-only in the service.
 * Borrower-facing lifecycle actions (cancel, blocklist) reuse {@code /api/applications} and
 * {@code /api/admin/blocklist}.
 */
@RestController
@RequestMapping("/api/customers")
@RequiredArgsConstructor
public class CustomerController {

    private final CustomerService customerService;

    /** All customers, optionally filtered by {@code q} (name contains / customer id). */
    @GetMapping
    public ApiResponse<List<CustomerSummary>> list(@RequestParam(required = false) String q) {
        return ApiResponse.ok(customerService.list(q));
    }

    /** One customer's full history: profile + applications + loans + payments. */
    @GetMapping("/{customerId}")
    public ApiResponse<CustomerDetail> get(@PathVariable Long customerId) {
        return ApiResponse.ok(customerService.detail(customerId));
    }

    /** ADMIN corrects a customer's KYC / salary data (non-identity fields); changes are audited. */
    @PutMapping("/{customerId}/profile")
    public ApiResponse<ProfileView> updateProfile(@PathVariable Long customerId,
                                                  @RequestBody UpdateCustomerRequest req) {
        return ApiResponse.ok(customerService.updateProfile(customerId, req));
    }

    /** One customer's audited profile/salary change history (previous→new, who, when). */
    @GetMapping("/{customerId}/changes")
    public ApiResponse<List<ProfileChangeView>> changes(@PathVariable Long customerId) {
        return ApiResponse.ok(customerService.changeHistory(customerId));
    }
}
