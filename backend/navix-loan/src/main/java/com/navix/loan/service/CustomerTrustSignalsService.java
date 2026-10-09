package com.navix.loan.service;

import static com.navix.common.loan.TrustSignals.FAIL;
import static com.navix.common.loan.TrustSignals.NOT_CHECKED;
import static com.navix.common.loan.TrustSignals.PASS;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.navix.common.loan.CustomerTrustDirectory;
import com.navix.common.loan.TrustSignals;
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
    private final Map<String, String> bureauCache = new ConcurrentHashMap<>();

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
        Map<Long, Boolean> unusable = new HashMap<>(); // deciding app -> no-hit or identity mismatch
        newestFirstByCustomer.forEach((c, ids2) -> {
            for (Long id : ids2) {
                CaseFailureRow r = rows.get(id + ":" + ApplicationVerificationService.BUREAU);
                JsonNode d = r == null ? null : json(r.getDerived());
                boolean noRecord = d != null && d.path("noRecord").asBoolean(false);
                if (noRecord || meta.containsKey(id)) {
                    decidingByCustomer.put(c, id);
                    // The report may belong to someone else when the identity check disagreed.
                    boolean mismatch = d != null && !d.path("identityMismatch").asText("").isBlank();
                    unusable.put(id, noRecord || mismatch);
                    return;
                }
            }
        });
        // Fetch the (large) facts JSON only for reports we have not already judged.
        List<Long> misses = decidingByCustomer.values().stream().distinct()
                .filter(id -> !unusable.get(id) && !bureauCache.containsKey(
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
            String bureau = m == null || unusable.get(bureauApp) ? NOT_CHECKED
                    : bureauCache.getOrDefault(cacheKey(bureauApp, m.getGeneratedAt()), NOT_CHECKED);
            out.put(customerId, new TrustSignals(bureau,
                    latest(newestFirst, rows, ApplicationVerificationService.EMPLOYMENT, true),
                    latest(newestFirst, rows, ApplicationVerificationService.EMAIL, false)));
        }
        return out;
    }

    private String latest(List<Long> newestFirst, Map<String, CaseFailureRow> rows, String type, boolean uan) {
        for (Long id : newestFirst) {
            CaseFailureRow r = rows.get(id + ":" + type);
            if (r == null) {
                continue;
            }
            String v = uan ? uan(r.getStatus(), r.getDerived()) : email(r.getStatus(), r.getDerived());
            if (!NOT_CHECKED.equals(v)) {
                return v;
            }
        }
        return NOT_CHECKED;
    }

    // ---- rules (package-visible for tests) -------------------------------------------------------

    String uan(String status, String derivedJson) {
        JsonNode d = json(derivedJson);
        if (ApplicationVerificationService.PENDING.equals(status) || d.path("providerError").asBoolean(false)
                || "NO_IDENTIFIER".equals(d.path("reason").asText(""))) {
            return NOT_CHECKED;
        }
        // Several UANs matched: ambiguous for the reviewer, but a UAN demonstrably exists.
        boolean hasUan = d.path("tooManyRecords").asBoolean(false)
                || (d.path("found").asBoolean(false) && !d.path("uan").asText("").isBlank());
        return ApplicationVerificationService.PASS.equals(status) || hasUan ? PASS : FAIL;
    }

    String email(String status, String derivedJson) {
        if (ApplicationVerificationService.PENDING.equals(status)
                || json(derivedJson).path("providerError").asBoolean(false)) {
            return NOT_CHECKED;
        }
        return ApplicationVerificationService.PASS.equals(status) ? PASS : FAIL;
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
        String v = NOT_CHECKED;
        try {
            BureauReportFacts f = objectMapper.readValue(row.getFacts(), BureauReportFacts.class);
            LocalDate reportDate = row.getGeneratedAt() == null ? LocalDate.now(IST)
                    : row.getGeneratedAt().atZone(IST).toLocalDate();
            v = bureau(f, reportDate);
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
        if (facts == null || facts.detail() == null || facts.detail().tradelines() == null) {
            return NOT_CHECKED;
        }
        YearMonth report = YearMonth.from(reportDate);
        YearMonth cutoff = report.minusMonths(WINDOW_MONTHS - 1L);
        boolean known = false;
        for (BureauTradeline t : facts.detail().tradelines()) {
            int r = windowDpd(t, report, cutoff);
            if (r == 1) {
                return FAIL;
            }
            known |= r == 0;
        }
        return known ? PASS : NOT_CHECKED;
    }

    /** CRIF asset classes that mean a non-performing account regardless of the DPD figure. */
    private static boolean npaClass(String cls) {
        String c = cls == null ? "" : cls.trim().toUpperCase(Locale.ROOT);
        return c.equals("SUB") || c.equals("DBT") || c.equals("LSS") || c.startsWith("SMA");
    }

    /** 1 = a delinquent month in the window, 0 = readable and clean, -1 = nothing readable in it. */
    private static int windowDpd(BureauTradeline t, YearMonth report, YearMonth cutoff) {
        String history = t.paymentHistory();
        if (history == null || history.isBlank() || "N".equals(history.trim())) {
            return -1;
        }
        String h = history.trim();
        boolean known = false;
        if (h.contains(",")) { // CRIF: "Aug:2026,000/STD|Jul:2026,030/XXX|..." newest first
            for (String seg : h.split("\\|")) {
                int comma = seg.indexOf(',');
                YearMonth m = comma < 0 ? null : month(seg.substring(0, comma));
                if (m == null || m.isBefore(cutoff)) {
                    continue;
                }
                String[] parts = seg.substring(comma + 1).split("/", 2);
                if (parts.length > 1 && npaClass(parts[1])) {
                    return 1;
                }
                try {
                    int dpd = Integer.parseInt(parts[0].trim());
                    known = true;
                    if (dpd > 0) {
                        return 1;
                    }
                } catch (NumberFormatException notReported) {
                    // "XXX" = not reported for that month: neither clean nor delinquent
                }
            }
            return known ? 0 : -1;
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
            return -1; // the newest char is already older than the window
        }
        for (int i = skip; i <= last && i < h.length(); i++) {
            char c = h.charAt(i);
            if ("123456BDM".indexOf(c) >= 0) {
                return 1;
            }
            known |= c == '0' || c == 'S';
        }
        return known ? 0 : -1;
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
