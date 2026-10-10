package com.navix.loan.service;

import static com.navix.common.loan.TrustSignals.FAIL;
import static com.navix.common.loan.TrustSignals.NOT_CHECKED;
import static com.navix.common.loan.TrustSignals.PASS;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.navix.common.loan.CustomerTrustDirectory;
import com.navix.common.loan.TrustSignals;
import com.navix.common.verification.BureauCodes;
import com.navix.common.verification.BureauReportFacts;
import com.navix.common.verification.BureauTradeline;
import com.navix.loan.entity.LoanApplication;
import com.navix.loan.repository.ApplicationVerificationRepository;
import com.navix.loan.repository.ApplicationVerificationRepository.CaseFailureRow;
import com.navix.loan.repository.CustomerProfileRepository;
import com.navix.loan.repository.CustomerProfileRepository.BriefFactsRow;
import com.navix.loan.repository.CustomerProfileRepository.BriefMetaRow;
import com.navix.loan.repository.LoanApplicationRepository;
import java.time.LocalDate;
import java.time.Month;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The three trust stars beside a customer on every staff table. Three queries per call (applications,
 * EMAIL+EMPLOYMENT verification rows, stored bureau facts), whatever the page size.
 *
 * <p>Each customer is read off their <b>latest application that has the check</b>, so a reborrow that
 * carried a bureau pull or a verification over still resolves, and a fresh DRAFT with nothing yet does
 * not mask the previous file's result.
 *
 * <ul>
 *   <li><b>BUREAU</b> - PASS when no tradeline of the latest bureau report shows a day past due in the
 *       last 6 reporting months; FAIL on any. The window is anchored to the <b>report date</b>
 *       (the brief generation time, i.e. when it was pulled), not today: the Experian profile string
 *       is positional - char 0 is the tradeline own Date_Reported month, which can lag the pull - and
 *       carries no dates of its own, and a report is a
 *       statement about the past as of its pull - a stale pull must not read as "clean for the last
 *       6 months" just because the calendar moved on. Experian reports bucket characters, so only
 *       30+ DPD (buckets 1-6) and B/D/M asset classes are visible; 1-29 DPD is not distinguishable
 *       from 0 in that feed. CRIF reports exact days, so any DPD &gt; 0 counts. NOT_CHECKED when there
 *       is no report or no readable monthly history in the window.</li>
 *   <li><b>UAN</b> - PASS when the EPFO lookup returned a record carrying a UAN
 *       ({@code derived.found && derived.uan}) or the check itself passed; a REVIEW caused by an
 *       employer-name mismatch or an exit still proves the UAN exists. FAIL when it ran and found
 *       none. NOT_CHECKED when never run, parked PENDING, or a vendor outage ({@code providerError}).</li>
 *   <li><b>EMAIL</b> - PASS when the work-email provider check ({@code EMAIL}, not the OTP proofs)
 *       passed; FAIL when it ran and did not; NOT_CHECKED otherwise.</li>
 * </ul>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class CustomerTrustSignalsService implements CustomerTrustDirectory {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final int WINDOW_MONTHS = 6;

    private final LoanApplicationRepository applicationRepository;
    private final ApplicationVerificationRepository verificationRepository;
    private final CustomerProfileRepository profileRepository;
    private final ObjectMapper objectMapper;

    /** Bureau verdict per (application, pull time): parsing tradeline JSON for a whole register on
     *  every poll is the only expensive step. ponytail: cleared wholesale past 50k entries. */
    private final Map<String, Verdict> bureauCache = new ConcurrentHashMap<>();

    /** A star and the one-line reason behind it. */
    record Verdict(String star, String why) {}

    private static final Verdict NO_REPORT = new Verdict(NOT_CHECKED, "No bureau report yet");

    @Override
    @Transactional(readOnly = true)
    public Map<Long, TrustSignals> forCustomers(Collection<Long> customerIds) {
        List<Long> ids = customerIds == null ? List.of()
                : customerIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, List<LoanApplication>> appsByCustomer = new HashMap<>();
        List<Long> appIds = new ArrayList<>();
        for (LoanApplication a : applicationRepository.findByCustomerIdIn(ids)) {
            appsByCustomer.computeIfAbsent(a.getCustomerId(), k -> new ArrayList<>()).add(a);
            appIds.add(a.getId());
        }
        if (appIds.isEmpty()) {
            return Map.of();
        }
        Map<String, CaseFailureRow> rows = new HashMap<>();
        for (CaseFailureRow r : verificationRepository.findByApplicationIdInAndCheckTypeIn(appIds,
                List.of(ApplicationVerificationService.EMAIL, ApplicationVerificationService.EMPLOYMENT,
                        ApplicationVerificationService.BUREAU))) {
            rows.put(r.getApplicationId() + ":" + r.getCheckType(), r);
        }
        Map<Long, List<Long>> newestFirstByCustomer = new HashMap<>();
        appsByCustomer.forEach((c, list) -> newestFirstByCustomer.put(c, list.stream()
                .sorted(Comparator.comparing(LoanApplication::getId).reversed())
                .map(LoanApplication::getId).toList()));

        // Which applications hold a stored report, and when it was pulled (no JSON).
        Map<Long, BriefMetaRow> meta = new HashMap<>();
        for (BriefMetaRow m : profileRepository.findBriefMetaByApplicationIdIn(appIds)) {
            meta.put(m.getApplicationId(), m);
        }
        // Only a real report or a genuine no-hit (derived.noRecord) decides the star, newest first.
        // Deferred consent, challenges, missing profile fields, provider errors and PENDING rows never
        // did a pull, so they must neither decide nor hide an older report.
        Map<Long, Long> decidingByCustomer = new HashMap<>();
        Map<Long, String> unusable = new HashMap<>(); // deciding app -> why the report cannot be used (null = usable)
        newestFirstByCustomer.forEach((c, ids2) -> {
            for (Long id : ids2) {
                CaseFailureRow r = rows.get(id + ":" + ApplicationVerificationService.BUREAU);
                JsonNode d = r == null ? null : json(r.getDerived());
                boolean noRecord = d != null && d.path("noRecord").asBoolean(false);
                if (noRecord || meta.containsKey(id)) {
                    decidingByCustomer.put(c, id);
                    // The report may belong to someone else when the identity check disagreed.
                    boolean mismatch = d != null && !d.path("identityMismatch").asText("").isBlank();
                    unusable.put(id, noRecord ? "Bureau has no record for this customer"
                            : mismatch ? "Report may be another person: " + d.path("identityMismatch").asText() : null);
                    return;
                }
            }
        });
        // Fetch the (large) facts JSON only for reports we have not already judged.
        List<Long> misses = decidingByCustomer.values().stream().distinct()
                .filter(id -> unusable.get(id) == null && !bureauCache.containsKey(
                        cacheKey(id, meta.get(id).getGeneratedAt())))
                .toList();
        if (!misses.isEmpty()) {
            for (BriefFactsRow f : profileRepository.findBriefFactsByApplicationIdIn(misses)) {
                judge(f);
            }
        }

        Map<Long, TrustSignals> out = new HashMap<>();
        for (Long customerId : appsByCustomer.keySet()) {
            List<Long> newestFirst = newestFirstByCustomer.get(customerId);
            Long bureauApp = decidingByCustomer.get(customerId);
            BriefMetaRow m = bureauApp == null ? null : meta.get(bureauApp);
            Verdict bureau = bureauApp == null ? NO_REPORT
                    : unusable.get(bureauApp) != null ? new Verdict(NOT_CHECKED, unusable.get(bureauApp))
                    : m == null ? NO_REPORT
                    : bureauCache.getOrDefault(cacheKey(bureauApp, m.getGeneratedAt()), NO_REPORT);
            Verdict uan = latest(newestFirst, rows, ApplicationVerificationService.EMPLOYMENT, true);
            Verdict email = latest(newestFirst, rows, ApplicationVerificationService.EMAIL, false);
            out.put(customerId, new TrustSignals(bureau.star(), uan.star(), email.star(),
                    bureau.why(), uan.why(), email.why()));
        }
        return out;
    }

    private Verdict latest(List<Long> newestFirst, Map<String, CaseFailureRow> rows, String type, boolean uan) {
        Verdict fallback = null; // newest row's reason when nothing decided (pending, outage, no identifier)
        for (Long id : newestFirst) {
            CaseFailureRow r = rows.get(id + ":" + type);
            if (r == null) {
                continue;
            }
            Verdict v = uan ? uanVerdict(r.getStatus(), r.getDerived(), r.getMessage())
                    : emailVerdict(r.getStatus(), r.getDerived(), r.getMessage());
            if (!NOT_CHECKED.equals(v.star())) {
                return v;
            }
            if (fallback == null) {
                fallback = v;
            }
        }
        return fallback != null ? fallback
                : new Verdict(NOT_CHECKED, uan ? "EPFO check not run" : "Work-email check not run");
    }

    // ---- rules (package-visible for tests) -------------------------------------------------------

    String uan(String status, String derivedJson) {
        return uanVerdict(status, derivedJson, null).star();
    }

    String email(String status, String derivedJson) {
        return emailVerdict(status, derivedJson, null).star();
    }

    Verdict uanVerdict(String status, String derivedJson, String message) {
        JsonNode d = json(derivedJson);
        if (ApplicationVerificationService.PENDING.equals(status)) {
            return new Verdict(NOT_CHECKED, "EPFO check pending");
        }
        if (d.path("providerError").asBoolean(false)) {
            return new Verdict(NOT_CHECKED, "EPFO unavailable \u2014 will retry");
        }
        if ("NO_IDENTIFIER".equals(d.path("reason").asText(""))) {
            return new Verdict(NOT_CHECKED, "No identifier to search EPFO");
        }
        // Several UANs matched: ambiguous for the reviewer, but a UAN demonstrably exists.
        if (d.path("tooManyRecords").asBoolean(false)) {
            int n = d.path("uanCount").asInt(0);
            return new Verdict(PASS, (n > 1 ? n + " UAN records" : "Several UAN records") + " matched");
        }
        boolean hasUan = d.path("found").asBoolean(false) && !d.path("uan").asText("").isBlank();
        if (!ApplicationVerificationService.PASS.equals(status) && !hasUan) {
            return new Verdict(FAIL, message != null && !message.isBlank() ? message : "No UAN found in EPFO");
        }
        String masked = d.path("uan").asText("");
        if (masked.isBlank()) {
            masked = d.path("uanMasked").asText("");
        }
        StringBuilder w = new StringBuilder(masked.isBlank() ? "UAN found" : "UAN " + masked + " found");
        String shown = d.path("employerName").asText("").trim();
        String declared = d.path("declaredEmployer").asText("").trim();
        String exit = d.path("dateOfExit").asText("").trim();
        if (!shown.isEmpty() && !declared.isEmpty() && !shown.equalsIgnoreCase(declared)) {
            w.append(" \u00b7 EPFO shows ").append(shown).append(", declared ").append(declared);
        } else if (!exit.isEmpty()) {
            w.append(" \u00b7 exited ").append(exit);
        }
        return new Verdict(PASS, w.toString());
    }

    Verdict emailVerdict(String status, String derivedJson, String message) {
        JsonNode d = json(derivedJson);
        if (ApplicationVerificationService.PENDING.equals(status)) {
            return new Verdict(NOT_CHECKED, "Work-email check pending");
        }
        if (d.path("providerError").asBoolean(false)) {
            return new Verdict(NOT_CHECKED, "Email check unavailable \u2014 will retry");
        }
        String domain = d.path("domain").asText("");
        if (ApplicationVerificationService.PASS.equals(status)) {
            String est = d.path("matchedEstablishment").asText("");
            return new Verdict(PASS, (domain.isBlank() ? "" : domain + ": ") + "email + employer matched"
                    + (est.isBlank() ? "" : " (" + est + ")"));
        }
        if (d.has("verified") && !d.path("verified").asBoolean(true)) {
            return new Verdict(FAIL, "Email not verified");
        }
        if (d.path("genericEmail").asBoolean(false)) {
            return new Verdict(FAIL, "Not an official email" + (domain.isBlank() ? "" : " (" + domain + ")"));
        }
        return new Verdict(FAIL, "Employer not matched \u2014 manual review");
    }

    private JsonNode json(String raw) {
        try {
            return raw == null || raw.isBlank() ? objectMapper.missingNode() : objectMapper.readTree(raw);
        } catch (Exception e) {
            return objectMapper.missingNode();
        }
    }

    private static String cacheKey(Long appId, java.time.Instant generatedAt) {
        return appId + ":" + generatedAt;
    }

    private void judge(BriefFactsRow row) {
        Verdict v = new Verdict(NOT_CHECKED, "Bureau report could not be read");
        try {
            BureauReportFacts f = objectMapper.readValue(row.getFacts(), BureauReportFacts.class);
            LocalDate reportDate = row.getGeneratedAt() == null ? LocalDate.now(IST)
                    : row.getGeneratedAt().atZone(IST).toLocalDate();
            v = verdict(f, reportDate);
        } catch (Exception e) {
            log.debug("Trust signal: unreadable bureau facts for application {}", row.getApplicationId());
        }
        if (bureauCache.size() > 50_000) {
            bureauCache.clear();
        }
        bureauCache.put(cacheKey(row.getApplicationId(), row.getGeneratedAt()), v);
    }

    /** PASS / FAIL / NOT_CHECKED over the last 6 reporting months ending at {@code reportDate}. */
    static String bureau(BureauReportFacts facts, LocalDate reportDate) {
        return verdict(facts, reportDate).star();
    }

    private static final java.time.format.DateTimeFormatter DAY =
            java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

    static Verdict verdict(BureauReportFacts facts, LocalDate reportDate) {
        if (facts == null || facts.detail() == null || facts.detail().tradelines() == null) {
            return new Verdict(NOT_CHECKED, "Bureau report could not be read");
        }
        YearMonth report = YearMonth.from(reportDate);
        YearMonth cutoff = report.minusMonths(WINDOW_MONTHS - 1L);
        int clean = 0;
        boolean anyExperian = false;
        for (BureauTradeline t : facts.detail().tradelines()) {
            Win w = windowDpd(t, report, cutoff);
            if (w.r == 1) {
                return new Verdict(FAIL, w.why);
            }
            if (w.r == 0) {
                clean++;
                anyExperian |= !w.crif;
            }
        }
        if (clean == 0) {
            return new Verdict(NOT_CHECKED, "No account history in the last 6 months");
        }
        return new Verdict(PASS, "No DPD on " + clean + (clean == 1 ? " account" : " accounts")
                + ", 6 months to " + monthLabel(report) + " (report " + reportDate.format(DAY) + ")"
                + (anyExperian ? "; Experian shows 30+ DPD only" : ""));
    }

    private static String monthLabel(YearMonth m) {
        return m.getMonth().getDisplayName(java.time.format.TextStyle.SHORT, Locale.ENGLISH) + " " + m.getYear();
    }

    /** r: 1 = a delinquent month in the window, 0 = readable and clean, -1 = nothing readable in it. */
    private record Win(int r, boolean crif, String why) {}

    private static final Win NONE = new Win(-1, false, null);

    private static String lenderLine(BureauTradeline t, String detail) {
        List<String> parts = new ArrayList<>();
        if (t.lender() != null && !t.lender().isBlank()) {
            parts.add(t.lender().trim());
        }
        String code = t.accountTypeCode();
        if (code != null && !code.isBlank()) {
            parts.add(BureauCodes.accountType(code).orElse(code.trim()));
        }
        parts.add(detail);
        return String.join(" \u00b7 ", parts);
    }

    /** CRIF asset classes that mean a non-performing account regardless of the DPD figure. */
    private static boolean npaClass(String cls) {
        String c = cls == null ? "" : cls.trim().toUpperCase(Locale.ROOT);
        return c.equals("SUB") || c.equals("DBT") || c.equals("LSS") || c.startsWith("SMA");
    }

    private static Win windowDpd(BureauTradeline t, YearMonth report, YearMonth cutoff) {
        String history = t.paymentHistory();
        if (history == null || history.isBlank() || "N".equals(history.trim())) {
            return NONE;
        }
        String h = history.trim();
        boolean known = false;
        boolean crif = h.contains(",");
        if (crif) { // CRIF: "Aug:2026,000/STD|Jul:2026,030/XXX|..." newest first
            for (String seg : h.split("\\|")) {
                int comma = seg.indexOf(',');
                YearMonth m = comma < 0 ? null : month(seg.substring(0, comma));
                if (m == null || m.isBefore(cutoff)) {
                    continue;
                }
                String[] parts = seg.substring(comma + 1).split("/", 2);
                if (parts.length > 1 && npaClass(parts[1])) {
                    return new Win(1, true, lenderLine(t, monthLabel(m) + ": asset class "
                            + parts[1].trim().toUpperCase(Locale.ROOT)));
                }
                try {
                    int dpd = Integer.parseInt(parts[0].trim());
                    known = true;
                    if (dpd > 0) {
                        return new Win(1, true, lenderLine(t, monthLabel(m) + ": " + dpd + " DPD"));
                    }
                } catch (NumberFormatException notReported) {
                    // "XXX" = not reported for that month: neither clean nor delinquent
                }
            }
            return known ? new Win(0, crif, null) : NONE;
        }
        // Experian: one bucket char per month, char 0 = the tradeline OWN Date_Reported month (not the
        // pull month), so the window is offset by how far that lags the report. 1-6 = 30+ DPD, B/D/M = NPA.
        YearMonth first = isoMonth(t.dateReported());
        if (first == null) {
            // Facts stored before dateReported existed: a closed account stopped reporting at closure.
            first = isoMonth(t.closedOn());
            if (first == null || first.isAfter(report)) {
                first = report;
            }
        }
        int skip = Math.max(0, (int) java.time.temporal.ChronoUnit.MONTHS.between(report, first));
        int last = (int) java.time.temporal.ChronoUnit.MONTHS.between(cutoff, first); // inclusive char index
        if (last < 0) {
            return NONE; // the newest char is already older than the window
        }
        for (int i = skip; i <= last && i < h.length(); i++) {
            char c = h.charAt(i);
            if ("123456BDM".indexOf(c) >= 0) {
                String bucket = BureauCodes.paymentHistoryBucket(String.valueOf(c)).orElse(String.valueOf(c));
                return new Win(1, false, lenderLine(t, monthLabel(first.minusMonths(i)) + ": " + bucket));
            }
            known |= c == '0' || c == 'S';
        }
        return known ? new Win(0, crif, null) : NONE;
    }

    private static YearMonth isoMonth(String iso) {
        try {
            return iso == null || iso.length() < 7 ? null : YearMonth.parse(iso.substring(0, 7));
        } catch (Exception e) {
            return null;
        }
    }

    private static YearMonth month(String token) { // "Aug:2026"
        try {
            String[] p = token.trim().split(":");
            String mon = p[0].trim().toUpperCase(Locale.ROOT);
            for (Month mo : Month.values()) {
                if (mo.name().startsWith(mon) && mon.length() >= 3) {
                    return YearMonth.of(Integer.parseInt(p[1].trim()), mo);
                }
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }
}
