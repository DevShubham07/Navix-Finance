package com.navix.app.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navix.common.exception.BusinessException;
import com.navix.common.security.ActorContext;
import com.navix.verification.client.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ProviderApiWorkbenchService {
    private static final Duration HISTORY = Duration.ofDays(90);
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
                                Map<String, Object> request, Object response, String error, Instant createdAt) {}

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
        try { response = call(op, p, safeInput, timeout); }
        catch (RuntimeException ex) { status = "FAILED"; error = ex.getMessage(); }
        long duration = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        ProviderApiExecution row = new ProviderApiExecution(); row.setOperation(op); row.setProvider(p);
        row.setRequestJson(json(safeInput)); row.setResponseJson(response == null ? null : json(response)); row.setStatus(status);
        row.setErrorMessage(error); row.setDurationMs(duration); row.setApplicationId(applicationId); row.setExpiresAt(Instant.now().plus(HISTORY));
        row = repository.save(row);
        if (!"SUCCESS".equals(status)) throw new BusinessException("PROVIDER_API_FAILED", error == null ? "Provider API failed" : error);
        return view(row, safeInput, response);
    }
    public List<ExecutionView> history() { requireAdmin(); return repository.findTop100ByOrderByCreatedAtDesc().stream().map(this::view).toList(); }
    @Scheduled(cron = "0 15 2 * * *") public void purgeExpiredHistory() { repository.deleteByExpiresAtBefore(Instant.now()); }

    private Object call(String op, String p, Map<String,Object> in, Duration timeout) {
        return within(timeout, () -> switch (op + ":" + p) {
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
    private static <T> T within(Duration timeout, Supplier<T> work) { try { return CompletableFuture.supplyAsync(work).get(timeout.toMillis(), TimeUnit.MILLISECONDS); } catch (TimeoutException e) { throw new BusinessException("PROVIDER_TIMEOUT", "Provider call timed out after " + timeout.toSeconds() + " seconds"); } catch (Exception e) { throw new BusinessException("PROVIDER_API_FAILED", e.getCause() == null ? e.getMessage() : e.getCause().getMessage()); } }
    private ExecutionView view(ProviderApiExecution row) { return new ExecutionView(row.getId(),row.getOperation(),row.getProvider(),row.getStatus(),row.getDurationMs(),read(row.getRequestJson()),readAny(row.getResponseJson()),row.getErrorMessage(),row.getCreatedAt()); }
    private ExecutionView view(ProviderApiExecution row, Map<String,Object> request, Object response) { return new ExecutionView(row.getId(),row.getOperation(),row.getProvider(),row.getStatus(),row.getDurationMs(),request,response,row.getErrorMessage(),row.getCreatedAt()); }
    private String json(Object o) { try { return objectMapper.writeValueAsString(o); } catch (Exception e) { throw new IllegalStateException(e); } }
    @SuppressWarnings("unchecked") private Map<String,Object> read(String s) { try { return s == null ? Map.of() : objectMapper.readValue(s, Map.class); } catch(Exception e) { return Map.of(); } }
    private Object readAny(String s) { try { return s == null ? null : objectMapper.readValue(s, Object.class); } catch(Exception e) { return null; } }
    private static String s(Map<String,Object> m,String key) { Object v=m.get(key); return v == null ? "" : String.valueOf(v).trim(); }
    private static double d(Map<String,Object> m,String key) { return Double.parseDouble(s(m,key)); }
    private static boolean blank(Object v) { return v == null || String.valueOf(v).isBlank(); }
    private static String upper(String s) { return s == null ? "" : s.trim().toUpperCase(); }
    private static void requireAdmin() { if (!"ADMIN".equals(ActorContext.get().role())) throw new BusinessException("FORBIDDEN_ROLE", "Administrator access required"); }
}
