package com.navix.app.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navix.common.exception.BusinessException;
import com.navix.common.security.ActorContext;
import com.navix.common.security.CurrentActor;
import com.navix.verification.support.ProviderCallContext;
import com.navix.verification.client.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ProviderApiWorkbenchService {
    private static final Duration HISTORY = Duration.ofDays(90);
    private static final int MAX_PAGE_SIZE = 100;
    private final ProviderApiExecutionRepository repository;
    private final ObjectMapper objectMapper;
    private final SignzyPanClient signzyPan;
    private final SignzyEmailClient signzyEmail;
    private final SignzyGeocodeClient signzyAddress;
    private final SignzyExperianClient signzyExperian;
    private final SignzyCrifClient signzyCrif;
    private final SignzyBankVerificationClient signzyBank;
    private final DigitapPanClient digitapPan;
    private final DigitapEmailClient digitapEmail;
    private final DigitapAddressClient digitapAddress;
    private final DigitapCreditClient digitapCredit;
    private final DigitapFaceMatchClient digitapFace;
    private final DigitapUanAdvancedClient digitapUan;

    public record Field(String key, String label, String type, boolean required) {}
    public record CatalogItem(String operation, List<String> providers, List<Field> fields) {}
    public record ExecutionView(Long id, String operation, String provider, String status, long durationMs,
                                Map<String, Object> request, Object response, String error, Instant createdAt,
                                String source, String endpoint, Integer httpStatus, String checkType,
                                Long applicationId, String requestId) {}
    /** History row without payloads — see {@code ProviderApiExecutionRepository.search}. */
    public record ExecutionSummary(Long id, String operation, String provider, String status, Integer httpStatus,
                                   long durationMs, String source, String endpoint, String checkType,
                                   Long applicationId, String requestId, String error, Instant createdAt) {}
    public record HistoryPage(List<ExecutionSummary> rows, int page, int size, long total) {}
    /** Every field optional; a null one is not filtered on. */
    public record HistoryQuery(String provider, String operation, String status, String source,
                               Long applicationId, Instant from, Instant to) {}

    public List<CatalogItem> catalog() {
        return List.of(
            item("PAN", List.of("SIGNZY", "DIGITAP"), f("pan", "PAN", "text", true)),
            item("EMAIL", List.of("SIGNZY", "DIGITAP"), f("email", "Email", "email", true), f("individualName", "Individual name", "text", false), f("establishmentName", "Employer name", "text", false)),
            item("ADDRESS", List.of("SIGNZY", "DIGITAP"), f("latitude", "Latitude", "number", true), f("longitude", "Longitude", "number", true)),
            item("BUREAU", List.of("SIGNZY_EXPERIAN", "SIGNZY_CRIF", "DIGITAP"), f("pan", "PAN", "text", true), f("name", "Full name", "text", true), f("mobile", "Mobile", "text", true), f("dob", "Date of birth", "date", true), f("otp", "Consent OTP", "text", false)),
            item("PENNY_DROP", List.of("SIGNZY"), f("accountNumber", "Account number", "text", true), f("ifsc", "IFSC", "text", true), f("beneficiaryName", "Beneficiary name", "text", true)),
            item("FACE_MATCH", List.of("DIGITAP"), f("personImage", "Selfie image URL/base64", "text", true), f("cardImage", "Document image URL/base64", "text", false)),
            item("UAN", List.of("DIGITAP"), f("pan", "PAN", "text", false), f("mobile", "Mobile", "text", false), f("dob", "Date of birth", "date", false), f("employeeName", "Employee name", "text", false), f("employerName", "Employer name", "text", false)));
    }
    private static CatalogItem item(String op, List<String> providers, Field... fields) { return new CatalogItem(op, providers, List.of(fields)); }
    private static Field f(String key, String label, String type, boolean required) { return new Field(key,label,type,required); }

    public ExecutionView execute(String operation, String provider, Map<String,Object> input, Long applicationId, Duration timeout) {
        requireAdmin();
        String op = upper(operation), p = upper(provider);
        CatalogItem item = catalog().stream().filter(i -> i.operation().equals(op)).findFirst()
            .orElseThrow(() -> new BusinessException("UNKNOWN_PROVIDER_OPERATION", "Unknown provider API operation"));
        if (!item.providers().contains(p)) throw new BusinessException("UNSUPPORTED_PROVIDER", "Provider does not support this operation");
        Map<String,Object> safeInput = input == null ? Map.of() : new LinkedHashMap<>(input);
        for (Field field : item.fields()) if (field.required() && blank(safeInput.get(field.key())))
            throw new BusinessException("MISSING_PROVIDER_INPUT", field.label() + " is required");
        long started = System.nanoTime(); Object response = null; String status = "SUCCESS"; String error = null;
        AtomicReference<Long> executionId = new AtomicReference<>();
        try { response = call(op, p, safeInput, timeout, applicationId, executionId); }
        catch (RuntimeException ex) { status = "FAILED"; error = ex.getMessage(); }
        long duration = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        // The transport layer already wrote the audit row, with the REAL wire payload rather than this
        // form input — so read that back instead of saving a second, less accurate one. It is missing
        // only when the call never reached the provider (bad input, timeout before dispatch).
        ProviderApiExecution row = executionId.get() == null ? null : repository.findById(executionId.get()).orElse(null);
        if (row == null) {
            row = new ProviderApiExecution(); row.setOperation(op); row.setProvider(p);
            row.setRequestJson(json(safeInput)); row.setResponseJson(response == null ? null : json(response)); row.setStatus(status);
            row.setErrorMessage(error); row.setDurationMs(duration); row.setApplicationId(applicationId);
            row.setSource(ProviderCallContext.MANUAL); row.setExpiresAt(Instant.now().plus(HISTORY));
            row = repository.save(row);
        }
        if (!"SUCCESS".equals(status)) throw new BusinessException("PROVIDER_API_FAILED", error == null ? "Provider API failed" : error);
        return view(row, safeInput, response);
    }
    public HistoryPage history(HistoryQuery query, int page, int size) {
        requireAdmin();
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        Page<ProviderApiExecutionSummary> found = repository.search(
                blankToNull(query.provider()), blankToNull(query.operation()), blankToNull(query.status()),
                blankToNull(query.source()), query.applicationId(), query.from(), query.to(),
                PageRequest.of(Math.max(page, 0), safeSize));
        return new HistoryPage(found.getContent().stream().map(ProviderApiWorkbenchService::summary).toList(),
                found.getNumber(), found.getSize(), found.getTotalElements());
    }

    /** One row WITH its raw request/response — the expanded view. */
    public ExecutionView detail(Long id) {
        requireAdmin();
        return repository.findById(id).map(this::view)
                .orElseThrow(() -> new BusinessException("PROVIDER_API_EXECUTION_NOT_FOUND", "No such provider call"));
    }
    @Scheduled(cron = "0 15 2 * * *") public void purgeExpiredHistory() { repository.deleteByExpiresAtBefore(Instant.now()); }

    private Object call(String op, String p, Map<String,Object> in, Duration timeout,
                        Long applicationId, AtomicReference<Long> executionId) {
        return within(timeout, ActorContext.get(), applicationId, executionId, () -> switch (op + ":" + p) {
            case "PAN:SIGNZY" -> signzyPan.verify(s(in,"pan"));
            case "PAN:DIGITAP" -> digitapPan.verify(s(in,"pan"), "admin-workbench");
            case "EMAIL:SIGNZY" -> signzyEmail.verify(s(in,"email"));
            case "EMAIL:DIGITAP" -> digitapEmail.verify(s(in,"email"), s(in,"individualName"), s(in,"establishmentName"), "admin-workbench");
            case "ADDRESS:SIGNZY" -> signzyAddress.reverseGeocode(d(in,"latitude"), d(in,"longitude"));
            case "ADDRESS:DIGITAP" -> digitapAddress.verify(d(in,"latitude"), d(in,"longitude"), "admin-workbench");
            case "BUREAU:SIGNZY_EXPERIAN" -> signzyExperian.pull(s(in,"pan"),s(in,"name"),s(in,"mobile"),s(in,"dob"));
            case "BUREAU:SIGNZY_CRIF" -> signzyCrif.pull(s(in,"pan"),s(in,"name"),s(in,"mobile"),s(in,"dob"));
            case "BUREAU:DIGITAP" -> digitapCredit.pull(s(in,"pan"),s(in,"name"),s(in,"mobile"),s(in,"dob"),s(in,"otp"),"admin-workbench");
            case "PENNY_DROP:SIGNZY" -> signzyBank.verify(s(in,"accountNumber"),s(in,"ifsc"),s(in,"beneficiaryName"));
            case "FACE_MATCH:DIGITAP" -> digitapFace.match(s(in,"personImage"),s(in,"cardImage"),"admin-workbench");
            case "UAN:DIGITAP" -> digitapUan.verify(s(in,"pan"),s(in,"mobile"),s(in,"dob"),s(in,"employeeName"),s(in,"employerName"),"admin-workbench");
            default -> throw new BusinessException("UNSUPPORTED_PROVIDER", "Unsupported provider operation");
        });
    }
    /**
     * The work runs on a ForkJoinPool thread, so every ThreadLocal the request set up — the actor AND
     * the provider-call context — has to be re-established there by hand, and torn down after. Without
     * this the audit row would be attributed to SYSTEM and labelled LIVE instead of MANUAL.
     */
    private static <T> T within(Duration timeout, CurrentActor actor, Long applicationId,
                                AtomicReference<Long> executionId, Supplier<T> work) {
        Supplier<T> scoped = () -> {
            ActorContext.set(actor);
            ProviderCallContext.setSource(ProviderCallContext.MANUAL);
            ProviderCallContext.setApplicationId(applicationId);
            try { return work.get(); }
            finally { executionId.set(ProviderCallContext.lastExecutionId()); ProviderCallContext.clear(); ActorContext.clear(); }
        };
        try { return CompletableFuture.supplyAsync(scoped).get(timeout.toMillis(), TimeUnit.MILLISECONDS); }
        catch (TimeoutException e) { throw new BusinessException("PROVIDER_TIMEOUT", "Provider call timed out after " + timeout.toSeconds() + " seconds"); }
        catch (Exception e) { throw new BusinessException("PROVIDER_API_FAILED", e.getCause() == null ? e.getMessage() : e.getCause().getMessage()); }
    }
    private ExecutionView view(ProviderApiExecution row) { return view(row, read(row.getRequestJson()), readAny(row.getResponseJson())); }
    private ExecutionView view(ProviderApiExecution row, Map<String,Object> request, Object response) { return new ExecutionView(row.getId(),row.getOperation(),row.getProvider(),row.getStatus(),row.getDurationMs(),request,response,row.getErrorMessage(),row.getCreatedAt(),row.getSource(),row.getEndpoint(),row.getHttpStatus(),row.getCheckType(),row.getApplicationId(),row.getRequestId()); }
    private static ExecutionSummary summary(ProviderApiExecutionSummary r) { return new ExecutionSummary(r.getId(),r.getOperation(),r.getProvider(),r.getStatus(),r.getHttpStatus(),r.getDurationMs() == null ? 0L : r.getDurationMs(),r.getSource(),r.getEndpoint(),r.getCheckType(),r.getApplicationId(),r.getRequestId(),r.getErrorMessage(),r.getCreatedAt()); }
    private static String blankToNull(String v) { return v == null || v.isBlank() ? null : v.trim().toUpperCase(); }
    private String json(Object o) { try { return objectMapper.writeValueAsString(o); } catch (Exception e) { throw new IllegalStateException(e); } }
    @SuppressWarnings("unchecked") private Map<String,Object> read(String s) { try { return s == null ? Map.of() : objectMapper.readValue(s, Map.class); } catch(Exception e) { return Map.of(); } }
    private Object readAny(String s) { try { return s == null ? null : objectMapper.readValue(s, Object.class); } catch(Exception e) { return null; } }
    private static String s(Map<String,Object> m,String key) { Object v=m.get(key); return v == null ? "" : String.valueOf(v).trim(); }
    private static double d(Map<String,Object> m,String key) { return Double.parseDouble(s(m,key)); }
    private static boolean blank(Object v) { return v == null || String.valueOf(v).isBlank(); }
    private static String upper(String s) { return s == null ? "" : s.trim().toUpperCase(); }
    private static void requireAdmin() { if (!"ADMIN".equals(ActorContext.get().role())) throw new BusinessException("FORBIDDEN_ROLE", "Administrator access required"); }
}
