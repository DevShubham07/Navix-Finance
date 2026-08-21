package com.navix.loan.controller;

import com.navix.common.exception.BusinessException;
import com.navix.common.security.ActorContext;
import com.navix.common.verification.OtpVerifierPort;
import com.navix.common.web.ApiResponse;
import com.navix.loan.dto.ApplicationDtos.ApplicationView;
import com.navix.loan.dto.CustomerDtos.ActivityEntry;
import com.navix.loan.dto.CustomerDtos.AddCallLogRequest;
import com.navix.loan.dto.CustomerDtos.AddRemarkRequest;
import com.navix.loan.dto.CustomerDtos.ApplicationDocumentGroup;
import com.navix.loan.dto.CustomerDtos.AssignOwnerRequest;
import com.navix.loan.dto.CustomerDtos.CallLogView;
import com.navix.loan.dto.CustomerDtos.ConfirmMobileChangeRequest;
import com.navix.loan.dto.CustomerDtos.ConfirmSanctionedAmountChangeRequest;
import com.navix.loan.dto.CustomerDtos.CustomerDetail;
import com.navix.loan.dto.CustomerDtos.CustomerSummary;
import com.navix.loan.dto.CustomerDtos.ProfileChangeView;
import com.navix.loan.dto.CustomerDtos.RemarkView;
import com.navix.loan.dto.CustomerDtos.RequestMobileChangeRequest;
import com.navix.loan.dto.CustomerDtos.RequestSanctionedAmountChangeRequest;
import com.navix.loan.dto.CustomerDtos.UpdateCustomerRequest;
import com.navix.loan.dto.ReviewDtos.ProfileView;
import com.navix.loan.service.CustomerService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Staff-facing customer (borrower-centric) endpoints: list/search distinct customers, read one
 * customer's full history, and ADMIN-correct their KYC data. All handlers require a staff role
 * (borrower / anonymous → {@code FORBIDDEN_ROLE}). Profile update and delete stay ADMIN-only in
 * the service; owner assignment is CREDIT_HEAD / COLLECTION_HEAD / ADMIN.
 */
@RestController
@RequestMapping("/api/customers")
@RequiredArgsConstructor
public class CustomerController {

    private final CustomerService customerService;

    /**
     * All customers, optionally filtered by {@code q} (name / PAN / mobile / customer id) and an
     * inclusive {@code [from, to]} date window over the customer's latest application. The window is
     * resolved in IST by the service, matching the live-applications queues.
     */
    @GetMapping
    public ApiResponse<List<CustomerSummary>> list(
            @RequestParam(required = false) String q,
            @RequestParam(required = false)
            @org.springframework.format.annotation.DateTimeFormat(
                    iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate from,
            @RequestParam(required = false)
            @org.springframework.format.annotation.DateTimeFormat(
                    iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate to) {
        requireStaff();
        return ApiResponse.ok(customerService.list(q, from, to));
    }

    /** One customer's full history: profile + applications + loans + payments. */
    @GetMapping("/{customerId}")
    public ApiResponse<CustomerDetail> get(@PathVariable Long customerId) {
        requireStaff();
        return ApiResponse.ok(customerService.detail(customerId));
    }

    /** ADMIN corrects a customer's KYC / salary data (non-identity fields); changes are audited. */
    @PutMapping("/{customerId}/profile")
    public ApiResponse<ProfileView> updateProfile(@PathVariable Long customerId,
                                                  @RequestBody UpdateCustomerRequest req) {
        requireStaff();
        return ApiResponse.ok(customerService.updateProfile(customerId, req));
    }

    /**
     * ADMIN, step 1 of 2 — send an OTP to a NEW mobile number to correct a customer's mobile on
     * file. KYC-data correction only; does not change which number the borrower logs in with.
     */
    @PostMapping("/{customerId}/mobile/request-otp")
    public ApiResponse<OtpVerifierPort.OtpRequestResult> requestMobileChangeOtp(
            @PathVariable Long customerId, @Valid @RequestBody RequestMobileChangeRequest req) {
        requireStaff();
        return ApiResponse.ok(customerService.requestMobileChangeOtp(customerId, req.newMobile()));
    }

    /** ADMIN, step 2 of 2 — confirm the mobile correction with the code sent to the new number. */
    @PostMapping("/{customerId}/mobile/confirm")
    public ApiResponse<ProfileView> confirmMobileChange(
            @PathVariable Long customerId, @Valid @RequestBody ConfirmMobileChangeRequest req) {
        requireStaff();
        return ApiResponse.ok(customerService.confirmMobileChange(customerId, req.newMobile(), req.otp()));
    }

    /**
     * ADMIN, step 1 of 2 — send an OTP to the customer's mobile on file to correct the amount
     * credit approved (sanctioned) for {@code applicationId} — verified server-side to be this
     * customer's own, sanctioned, not-yet-disbursed application.
     */
    @PostMapping("/{customerId}/sanctioned-amount/request-otp")
    public ApiResponse<OtpVerifierPort.OtpRequestResult> requestSanctionedAmountOtp(
            @PathVariable Long customerId, @Valid @RequestBody RequestSanctionedAmountChangeRequest req) {
        requireStaff();
        return ApiResponse.ok(customerService.requestSanctionedAmountOtp(
                customerId, req.applicationId(), req.newAmountPaise()));
    }

    /** ADMIN, step 2 of 2 — confirm the sanctioned-amount correction with the customer's OTP. */
    @PostMapping("/{customerId}/sanctioned-amount/confirm")
    public ApiResponse<ApplicationView> confirmSanctionedAmountChange(
            @PathVariable Long customerId, @Valid @RequestBody ConfirmSanctionedAmountChangeRequest req) {
        requireStaff();
        return ApiResponse.ok(customerService.confirmSanctionedAmountChange(
                customerId, req.applicationId(), req.newAmountPaise(), req.otp()));
    }

    /** Every document across ALL of this customer's applications, grouped by application (newest
     *  first) — so a reborrow's prior-application uploads stay reachable (work item 4). */
    @GetMapping("/{customerId}/documents")
    public ApiResponse<List<ApplicationDocumentGroup>> documents(@PathVariable Long customerId) {
        requireStaff();
        return ApiResponse.ok(customerService.documents(customerId));
    }

    /** One customer's audited profile/salary change history (previous→new, who, when). */
    @GetMapping("/{customerId}/changes")
    public ApiResponse<List<ProfileChangeView>> changes(@PathVariable Long customerId) {
        requireStaff();
        return ApiResponse.ok(customerService.changeHistory(customerId));
    }

    /** Unified activity timeline: lifecycle + re-verify + profile edits + remarks + calls. */
    @GetMapping("/{customerId}/activity")
    public ApiResponse<List<ActivityEntry>> activity(@PathVariable Long customerId) {
        requireStaff();
        return ApiResponse.ok(customerService.activity(customerId));
    }

    /** Staff remarks on a customer. */
    @GetMapping("/{customerId}/remarks")
    public ApiResponse<List<RemarkView>> remarks(@PathVariable Long customerId) {
        requireStaff();
        return ApiResponse.ok(customerService.remarks(customerId));
    }

    @PostMapping("/{customerId}/remarks")
    public ApiResponse<RemarkView> addRemark(@PathVariable Long customerId,
                                             @Valid @RequestBody AddRemarkRequest req) {
        requireStaff();
        return ApiResponse.ok(customerService.addRemark(customerId, req.body()));
    }

    /** Assign (or clear) the staff owner of a customer. {@code staffId} null → unallocate. */
    @PostMapping("/{customerId}/owner")
    public ApiResponse<CustomerDetail> assignOwner(@PathVariable Long customerId,
                                                   @RequestBody AssignOwnerRequest req) {
        requireStaff();
        return ApiResponse.ok(customerService.assignOwner(customerId, req.staffId()));
    }

    /** Staff call logs on a customer. */
    @GetMapping("/{customerId}/call-logs")
    public ApiResponse<List<CallLogView>> callLogs(@PathVariable Long customerId) {
        requireStaff();
        return ApiResponse.ok(customerService.callLogs(customerId));
    }

    @PostMapping("/{customerId}/call-logs")
    public ApiResponse<CallLogView> addCallLog(@PathVariable Long customerId,
                                               @Valid @RequestBody AddCallLogRequest req) {
        requireStaff();
        return ApiResponse.ok(customerService.addCallLog(customerId, req));
    }

    /** ADMIN — permanently delete a customer and all of their data (irreversible cascade). */
    @DeleteMapping("/{customerId}")
    public ApiResponse<CustomerService.DeletionResult> delete(@PathVariable Long customerId) {
        requireStaff();
        return ApiResponse.ok(customerService.deleteCustomer(customerId));
    }

    /**
     * Reject borrower / anonymous callers — this controller is staff-only.
     *
     * <p>DSAs are excluded too: they are staff, but the whole point of the role is that an external
     * commission agent never sees customer data. Every endpoint here funnels through this method, so
     * this is the one place that has to hold (the read paths in {@code CustomerService} repeat the
     * check as defence in depth). It is a deliberate exclusion rather than a role allowlist, because
     * every other staff role legitimately holds the broad {@code customer:view} permission.
     */
    private void requireStaff() {
        String role = ActorContext.get().role();
        if (role == null || "BORROWER".equals(role) || "ANONYMOUS".equals(role)) {
            throw new BusinessException("FORBIDDEN_ROLE", "Staff role required");
        }
        if ("DSA".equals(role)) {
            throw new BusinessException("FORBIDDEN_ROLE", "DSAs cannot view customer data");
        }
    }
}
