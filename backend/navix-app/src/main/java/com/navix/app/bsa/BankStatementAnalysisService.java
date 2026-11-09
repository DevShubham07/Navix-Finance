package com.navix.app.bsa;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navix.common.exception.BusinessException;
import com.navix.common.security.ActorContext;
import com.navix.common.security.CurrentActor;
import com.navix.common.verification.ProviderCallContext;
import com.navix.loan.dto.ApplicationDtos.ApplicationView;
import com.navix.loan.dto.CustomerDtos.CustomerDetail;
import com.navix.loan.dto.ReviewDtos.ProfileView;
import com.navix.loan.entity.ApplicationDocument;
import com.navix.loan.repository.ApplicationDocumentRepository;
import com.navix.loan.service.CustomerService;
import com.navix.storage.service.DocumentStorageService;
import com.navix.verification.client.SignzyBankStatementClient;
import com.navix.verification.dto.SignzyDtos.BsaAccount;
import com.navix.verification.dto.SignzyDtos.BsaFile;
import com.navix.verification.dto.SignzyDtos.BsaReport;
import com.navix.verification.dto.SignzyDtos.BsaReportFiles;
import com.navix.verification.exception.VerificationException;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * On-demand Signzy Bank Statement Analysis for a customer — see V81 and
 * {@link SignzyBankStatementClient}.
 *
 * <p>Three rules live here and nowhere else:
 * <ul>
 *   <li><b>Running</b> is billed per statement, so only the credit desk may trigger it:
 *       {@code CREDIT_EXECUTIVE}, {@code CREDIT_HEAD} (and ADMIN, which bypasses every role check).</li>
 *   <li><b>Reading</b> the stored result is free and shows nothing the customer page does not already
 *       show the same roles, so any staff but DSA may read — both paths go through
 *       {@code CustomerService.detail}, which is where that firewall lives.</li>
 *   <li><b>One run at a time.</b> Signzy's flow is asynchronous; a second submission while the first
 *       is still {@code IN PROGRESS} would pay twice for the same statements.</li>
 * </ul>
 *
 * <p>Signzy never calls us back (we pass no callbackUrl — that would mean a new public endpoint):
 * the row is the source of truth and {@link #latest} advances it, throttled by
 * {@code last_polled_at}, whenever a staffer opens the Banking tab.
 *
 * <p>{@link #run} is deliberately <b>not</b> transactional, so a {@code FAILED} row is committed
 * before the exception reaches the controller.
 */
@Service
@RequiredArgsConstructor
public class BankStatementAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(BankStatementAnalysisService.class);
    private static final Set<String> CREDIT_ROLES = Set.of("CREDIT_EXECUTIVE", "CREDIT_HEAD");
    private static final String CHECK_TYPE = "BANK_STATEMENT";
    private static final String DOC_TYPE = "BANK_STATEMENT";
    /** What we ask Signzy to look for — the borrower uploads six months at intake. */
    private static final String STATEMENT_PERIOD = "6m";
    /** Floor between two progress polls, so refetch-on-focus cannot hammer Signzy. */
    private static final Duration POLL_EVERY = Duration.ofSeconds(10);

    private final BankStatementAnalysisRepository repository;
    private final CustomerService customerService;
    private final ApplicationDocumentRepository documents;
    private final DocumentStorageService storage;
    private final SignzyBankStatementClient client;
    private final ObjectMapper objectMapper;

    /** Matches the frontend's {@code BankAnalysisView} — the Banking tab and BRE rule 9 read this. */
    public record BankAnalysisView(Long id, Long customerId, Long applicationId, String status,
                                   String reportId, String reportStatus, String reportStatusDescription,
                                   String bankScore, String bank, List<AccountView> accounts,
                                   Object salary, String excelUrl, String jsonUrl, String message,
                                   Long runByStaffId, String runByName, String runByRole,
                                   Instant createdAt, Instant updatedAt) {}

    public record AccountView(String maskedNumber, String type, Integer txnCount, String status,
                              String ifsc, String micr, String facility, String branch,
                              String periodFrom, String periodTo, String holderName, String authenticity,
                              Long openingBalancePaise, Long closingBalancePaise) {}

    /**
     * The latest analysis for this customer, or null when none has been run. Advances a still-running
     * report first. Visibility = the customer page's (DSA rejected, scope applied, 404 if unknown).
     */
    public BankAnalysisView latest(Long customerId) {
        customerService.detail(customerId);
        BankStatementAnalysis row = repository.findFirstByCustomerIdOrderByCreatedAtDesc(customerId).orElse(null);
        if (row == null) {
            return null;
        }
        return view(advance(row));
    }

    /** Every run for this customer, newest first — free to read, no provider call. */
    public List<BankAnalysisView> history(Long customerId) {
        customerService.detail(customerId);
        return repository.findByCustomerIdOrderByCreatedAtDesc(customerId).stream().map(this::view).toList();
    }

    /** Submit this customer's uploaded statements for analysis. Billed per statement. */
    public BankAnalysisView run(Long customerId) {
        requireCreditOrAdmin();
        repository.findFirstByCustomerIdOrderByCreatedAtDesc(customerId)
                .filter(r -> BankStatementAnalysis.PENDING.equals(r.getStatus()))
                .ifPresent(r -> {
                    throw new BusinessException("BSA_IN_PROGRESS",
                            "An analysis for this customer is still running — wait for it rather than paying twice");
                });

        CustomerDetail detail = customerService.detail(customerId);
        ProfileView p = detail.profile();
        Long applicationId = detail.applications().stream()
                .map(ApplicationView::id).max(Comparator.naturalOrder()).orElse(null);

        List<ApplicationDocument> statements = statementsOf(detail);
        if (statements.isEmpty()) {
            throw new BusinessException("BSA_NO_STATEMENTS",
                    "This customer has no bank statement on file in object storage to analyse");
        }
        List<BsaFile> files = statements.stream()
                .map(d -> new BsaFile(storage.presignDownload(d.getS3ObjectKey()), blankToNull(d.getFilePassword())))
                .toList();

        // The presigned URLs are credentials, so THIS row records the object keys instead; the
        // passwords are not written here at all. The provider audit row and the PROVIDER_CALL log
        // see the real request, so both strip these keys centrally — ProviderJson.record and
        // ProviderLogRedactor.CREDENTIAL_KEYS. Without that the promise on this line was false:
        // a presigned URL is a bearer token for a borrower's statement, and the PDF password is a
        // borrower secret that, unlike the URL, never expires.
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("objectKeys", statements.stream().map(ApplicationDocument::getS3ObjectKey).toList());
        request.put("customerName", p == null ? null : p.fullName());
        request.put("statementPeriod", STATEMENT_PERIOD);

        CurrentActor actor = ActorContext.get();
        BankStatementAnalysis row = new BankStatementAnalysis();
        row.setCustomerId(customerId);
        row.setApplicationId(applicationId);
        row.setRequestJson(json(request));
        row.setRunByStaffId(actorStaffId(actor));
        row.setRunByName(actor.name());
        row.setRunByRole(actor.role());

        ProviderCallContext.setSource(ProviderCallContext.MANUAL);
        ProviderCallContext.setApplicationId(applicationId);
        ProviderCallContext.setCheckType(CHECK_TYPE);
        long started = System.currentTimeMillis();
        try {
            BsaReport report = client.analyze(files, p == null ? null : p.fullName(), STATEMENT_PERIOD);
            row.setDurationMs(System.currentTimeMillis() - started);
            apply(row, report);
            row.setLastPolledAt(Instant.now());
            return view(repository.save(row));
        } catch (RuntimeException providerFailure) {
            row.setDurationMs(System.currentTimeMillis() - started);
            row.setStatus(BankStatementAnalysis.FAILED);
            row.setMessage(trimTo(failureMessage(providerFailure), 500));
            repository.save(row);
            log.warn("bank statement analysis failed customer={} application={}: {}", customerId,
                    applicationId, providerFailure.toString());
            throw new BusinessException("BSA_FAILED",
                    "Signzy could not start the analysis: " + failureMessage(providerFailure));
        } finally {
            ProviderCallContext.clear();
        }
    }

    /**
     * Poll a still-running report, and fetch the report URLs the first time it reports ANALYSED. A
     * provider failure here is never fatal: the read returns the row as it stands, with the reason.
     */
    private BankStatementAnalysis advance(BankStatementAnalysis row) {
        boolean running = BankStatementAnalysis.PENDING.equals(row.getStatus()) && row.getReportId() != null;
        boolean needsUrls = BankStatementAnalysis.ANALYSED.equals(row.getStatus())
                && row.getReportId() != null && row.getReportExcelUrl() == null;
        if ((!running && !needsUrls) || polledRecently(row)) {
            return row;
        }
        ProviderCallContext.setSource(ProviderCallContext.MANUAL);
        ProviderCallContext.setApplicationId(row.getApplicationId());
        ProviderCallContext.setCheckType(CHECK_TYPE);
        try {
            if (running) {
                apply(row, client.retrieveWorkOrder(row.getReportId()));
            }
            if (BankStatementAnalysis.ANALYSED.equals(row.getStatus()) && row.getReportExcelUrl() == null) {
                BsaReportFiles report = client.downloadReport(row.getReportId());
                row.setReportJsonUrl(report.jsonUrl());
                row.setReportExcelUrl(report.excelUrl());
            }
        } catch (RuntimeException e) {
            // Not fatal: the next read tries again. Staff see the stored state plus this reason.
            row.setMessage(trimTo(failureMessage(e), 500));
            log.warn("bank statement analysis poll failed customer={} report={}: {}",
                    row.getCustomerId(), row.getReportId(), e.toString());
        } finally {
            ProviderCallContext.clear();
        }
        row.setLastPolledAt(Instant.now());
        return repository.save(row);
    }

    private boolean polledRecently(BankStatementAnalysis row) {
        return row.getLastPolledAt() != null
                && row.getLastPolledAt().isAfter(Instant.now().minus(POLL_EVERY));
    }

    /** Copies a Signzy envelope onto the row, including the status mapping. */
    private void apply(BankStatementAnalysis row, BsaReport report) {
        row.setReportId(report.reportId() != null ? report.reportId() : row.getReportId());
        row.setReportStatus(report.reportStatus());
        row.setReportStatusDesc(trimTo(report.reportStatusDescription(), 200));
        row.setBankScore(report.bankScore());
        row.setResponseJson(report.rawJson());
        row.setStatus(statusOf(report.reportStatus()));
        if (BankStatementAnalysis.FAILED.equals(row.getStatus())) {
            row.setMessage(trimTo(report.reportStatusDescription() == null
                    ? report.reportStatus() : report.reportStatusDescription(), 500));
        }
    }

    /** Signzy's three report states, mapped onto ours. An unknown state is still in flight. */
    static String statusOf(String reportStatus) {
        if (reportStatus == null) {
            return BankStatementAnalysis.PENDING;
        }
        return switch (reportStatus.trim().toUpperCase()) {
            case "ANALYSED" -> BankStatementAnalysis.ANALYSED;
            case "ANALYSIS ON HOLD" -> BankStatementAnalysis.FAILED;
            default -> BankStatementAnalysis.PENDING;
        };
    }

    /** Every S3-backed BANK_STATEMENT upload across the customer's applications, newest first. */
    private List<ApplicationDocument> statementsOf(CustomerDetail detail) {
        List<Long> applicationIds = detail.applications().stream().map(ApplicationView::id).toList();
        if (applicationIds.isEmpty()) {
            return List.of();
        }
        return documents.findByApplicationIdInOrderByApplicationIdDescIdAsc(applicationIds).stream()
                .filter(d -> DOC_TYPE.equalsIgnoreCase(d.getDocType()))
                // Pre-S3 rows hold their bytes inline and have no URL to hand Signzy. Rare, and the
                // borrower can be asked to re-upload, which is cheaper than a multipart code path.
                .filter(d -> d.getS3ObjectKey() != null && !d.getS3ObjectKey().isBlank())
                .toList();
    }

    private BankAnalysisView view(BankStatementAnalysis r) {
        List<BsaAccount> accounts = accountsOf(r);
        String bank = accounts.stream().map(BsaAccount::bankName).filter(s -> s != null && !s.isBlank())
                .distinct().reduce((a, b) -> a + " · " + b).orElse(null);
        return new BankAnalysisView(r.getId(), r.getCustomerId(), r.getApplicationId(), r.getStatus(),
                r.getReportId(), r.getReportStatus(), r.getReportStatusDesc(), r.getBankScore(), bank,
                accounts.stream().map(BankStatementAnalysisService::accountView).toList(),
                // Salary identification lives in the downloadable report JSON, whose schema is
                // template-driven and not yet known for our template. Until a real report has been
                // read, the Banking tab and BRE rule 9 show "not identified" rather than a guess.
                null,
                r.getReportExcelUrl(), r.getReportJsonUrl(), r.getMessage(), r.getRunByStaffId(),
                r.getRunByName(), r.getRunByRole(), r.getCreatedAt(), r.getUpdatedAt());
    }

    private static AccountView accountView(BsaAccount a) {
        return new AccountView(mask(a.accountNumber()), a.accountTypeName(), a.txnCount(), a.status(),
                a.ifsc(), a.micr(), a.accountTypeName(), a.branchName(), a.startDate(), a.endDate(),
                a.accountHolderName(), a.authenticity(), null, null);
    }

    /** Signzy returns some account numbers in full; the console only ever needs the last four. */
    static String mask(String accountNumber) {
        if (accountNumber == null || accountNumber.isBlank()) {
            return null;
        }
        String s = accountNumber.trim();
        return s.length() <= 4 ? s : "X".repeat(s.length() - 4) + s.substring(s.length() - 4);
    }

    /** Re-reads the accounts out of the stored envelope, so the row stays the only state we keep. */
    private List<BsaAccount> accountsOf(BankStatementAnalysis r) {
        if (r.getResponseJson() == null) {
            return List.of();
        }
        try {
            Set<String> seen = new LinkedHashSet<>();
            return SignzyBankStatementClient.accountsOf(objectMapper.readTree(r.getResponseJson())).stream()
                    .filter(a -> a.accountNumber() == null || seen.add(a.accountNumber()))
                    .toList();
        } catch (Exception e) {
            return List.of();
        }
    }

    /** ADMIN bypasses every role check, as it does throughout the console. */
    private static void requireCreditOrAdmin() {
        String role = ActorContext.get().role();
        if (!"ADMIN".equals(role) && !CREDIT_ROLES.contains(role)) {
            throw new BusinessException("FORBIDDEN_ROLE",
                    "This action requires one of: " + String.join(", ", CREDIT_ROLES));
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

    /** The provider's safe detail when there is one — "HTTP 400 from /x" alone tells staff nothing. */
    private static String failureMessage(RuntimeException e) {
        if (e instanceof VerificationException ve && ve.safeDetail() != null && !ve.safeDetail().isBlank()) {
            return e.getMessage() + " — " + ve.safeDetail();
        }
        return e.getMessage();
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
