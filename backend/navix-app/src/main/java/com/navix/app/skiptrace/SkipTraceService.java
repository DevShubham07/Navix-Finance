package com.navix.app.skiptrace;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navix.common.exception.BusinessException;
import com.navix.common.security.ActorContext;
import com.navix.common.security.CurrentActor;
import com.navix.common.verification.ProviderCallContext;
import com.navix.loan.dto.ApplicationDtos.ApplicationView;
import com.navix.loan.dto.CustomerDtos.CustomerDetail;
import com.navix.loan.dto.LoanDtos.LoanView;
import com.navix.loan.dto.ReviewDtos.ProfileView;
import com.navix.loan.service.CustomerService;
import com.navix.verification.client.DigitapSkipTraceClient;
import com.navix.verification.dto.DigitapDtos.SkipTraceResponse;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * On-demand Digitap Skip Tracing Lite for collections — see V77 and {@link DigitapSkipTraceClient}.
 *
 * <p>Two rules, both enforced here and nowhere else:
 * <ul>
 *   <li><b>Running</b> costs money and returns a person's alternate contacts, so only
 *       {@code COLLECTION_HEAD} (and ADMIN, who bypasses every role check) may trigger it.</li>
 *   <li><b>Reading</b> an existing result is free and shows nothing beyond what the customer page
 *       already shows the same roles, so any staff but DSA may read — the same firewall
 *       {@code CustomerService.detail} applies, which is why both paths go through it.</li>
 * </ul>
 *
 * <p>Every run is persisted, including failures: {@link #run} is deliberately <b>not</b> transactional,
 * so the {@code FAILED} row is committed before the exception reaches the controller.
 */
@Service
@RequiredArgsConstructor
public class SkipTraceService {

    private static final Logger log = LoggerFactory.getLogger(SkipTraceService.class);
    private static final String MANAGER_ROLE = "COLLECTION_HEAD";
    private static final String CHECK_TYPE = "SKIP_TRACE";

    private final SkipTraceRepository repository;
    private final CustomerService customerService;
    private final DigitapSkipTraceClient client;
    private final ObjectMapper objectMapper;

    public record SkipTraceView(Long id, Long customerId, Long applicationId, Long loanId, String status,
                                Integer resultCode, String message, String providerRequestId,
                                Long runByStaffId, String runByName, String runByRole, Instant createdAt,
                                Long durationMs, Map<String, Object> request, Object response) {}

    /** Newest first. Visibility = the customer page's (DSA rejected, scope applied, 404 if unknown). */
    public List<SkipTraceView> history(Long customerId) {
        customerService.detail(customerId);
        return repository.findByCustomerIdOrderByCreatedAtDesc(customerId).stream().map(this::view).toList();
    }

    /** One billable lookup for this customer, recorded whatever the outcome. */
    public SkipTraceView run(Long customerId) {
        requireManagerOrAdmin();
        CustomerDetail detail = customerService.detail(customerId);
        ProfileView p = detail.profile();
        String pan = p == null ? null : blankToNull(p.pan());
        String mobile = p == null ? null : blankToNull(p.mobile());
        if (pan == null && mobile == null) {
            throw new BusinessException("SKIP_TRACE_NO_IDENTIFIER",
                    "This customer has neither a PAN nor a mobile number on file to trace");
        }
        String name = p == null ? null : blankToNull(p.fullName());
        String address = p == null ? null : blankToNull(p.address());
        Long applicationId = detail.applications().stream().map(ApplicationView::id).max(Comparator.naturalOrder()).orElse(null);
        Long loanId = detail.loans().stream().map(LoanView::id).max(Comparator.naturalOrder()).orElse(null);

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("pan", pan);
        request.put("mobile", mobile);
        request.put("name", name);
        request.put("address", address == null ? List.of() : List.of(address));

        CurrentActor actor = ActorContext.get();
        SkipTrace row = new SkipTrace();
        row.setCustomerId(customerId);
        row.setApplicationId(applicationId);
        row.setLoanId(loanId);
        row.setRequestJson(json(request));
        row.setRunByStaffId(actorStaffId(actor));
        row.setRunByName(actor.name());
        row.setRunByRole(actor.role());

        // The client ref carries the application id so the provider_api_execution audit row is
        // attributed to the file automatically; MANUAL marks it as staff-triggered, not lifecycle.
        ProviderCallContext.setSource(ProviderCallContext.MANUAL);
        ProviderCallContext.setApplicationId(applicationId);
        ProviderCallContext.setCheckType(CHECK_TYPE);
        long started = System.currentTimeMillis();
        try {
            SkipTraceResponse r = client.trace(pan, mobile, name, address == null ? null : List.of(address),
                    "navix-" + applicationId + "-" + CHECK_TYPE);
            row.setDurationMs(System.currentTimeMillis() - started);
            row.setProviderRequestId(r.txnId());
            row.setResultCode(r.resultCode());
            row.setMessage(r.message());
            row.setResponseJson(r.rawJson());
            row.setStatus(r.resultCode() != null && r.resultCode() == DigitapSkipTraceClient.RESULT_OK
                    ? SkipTrace.SUCCESS : SkipTrace.NO_RECORD);
            return view(repository.save(row));
        } catch (RuntimeException providerFailure) {
            row.setDurationMs(System.currentTimeMillis() - started);
            row.setStatus(SkipTrace.FAILED);
            row.setMessage(trimTo(providerFailure.getMessage(), 500));
            repository.save(row);
            log.warn("skip trace failed customer={} application={}: {}", customerId, applicationId,
                    providerFailure.toString());
            throw new BusinessException("SKIP_TRACE_FAILED",
                    "Digitap could not complete the skip trace: " + providerFailure.getMessage());
        } finally {
            ProviderCallContext.clear();
        }
    }

    private SkipTraceView view(SkipTrace r) {
        return new SkipTraceView(r.getId(), r.getCustomerId(), r.getApplicationId(), r.getLoanId(), r.getStatus(),
                r.getResultCode(), r.getMessage(), r.getProviderRequestId(), r.getRunByStaffId(), r.getRunByName(),
                r.getRunByRole(), r.getCreatedAt(), r.getDurationMs(), readMap(r.getRequestJson()),
                readAny(r.getResponseJson()));
    }

    /** ADMIN bypasses every role check, as it does throughout the console. */
    private static void requireManagerOrAdmin() {
        String role = ActorContext.get().role();
        if (!"ADMIN".equals(role) && !MANAGER_ROLE.equals(role)) {
            throw new BusinessException("FORBIDDEN_ROLE", "This action requires one of: " + MANAGER_ROLE);
        }
    }

    private static long actorStaffId(CurrentActor actor) {
        try {
            return Long.parseLong(actor.id());
        } catch (NumberFormatException e) {
            throw new BusinessException("ACTOR_NOT_STAFF",
                    "The acting identity is not a real staff id; sign in as staff");
        }
    }

    private String json(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readMap(String s) {
        try {
            return s == null ? Map.of() : objectMapper.readValue(s, Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }

    private Object readAny(String s) {
        try {
            return s == null ? null : objectMapper.readValue(s, Object.class);
        } catch (Exception e) {
            return null;
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String trimTo(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
