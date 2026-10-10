package com.navix.loan.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navix.common.loan.TrustSignals;
import com.navix.common.verification.BureauDetail;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CustomerTrustSignalsServiceTest {

    private static final LocalDate REPORT = LocalDate.of(2026, 8, 15);

    private final LoanApplicationRepository apps = mock(LoanApplicationRepository.class);
    private final ApplicationVerificationRepository verifications = mock(ApplicationVerificationRepository.class);
    private final CustomerProfileRepository profiles = mock(CustomerProfileRepository.class);
    private final CustomerTrustSignalsService service =
            new CustomerTrustSignalsService(apps, verifications, profiles, new ObjectMapper());

    private static BureauReportFacts facts(String... histories) {
        List<BureauTradeline> lines = new ArrayList<>();
        for (String h : histories) {
            lines.add(new BureauTradeline("BANK", "XXXX1", null, null, null, null, null, null, null, null,
                    h, null, null, null));
        }
        return new BureauReportFacts(null, null, null, null, null, null, 700, 1, 1, 0, 0, null, null, null,
                0, "R", new BureauDetail(lines, lines.size(), List.of(), null, null));
    }

    private static BureauReportFacts one(String history, String dateReported, String closedOn) {
        BureauTradeline t = new BureauTradeline("BANK", "XXXX1", null, null, null, null, closedOn, null, null,
                null, history, null, null, null, dateReported);
        return new BureauReportFacts(null, null, null, null, null, null, 700, 1, 1, 0, 0, null, null, null,
                0, "R", new BureauDetail(List.of(t), 1, List.of(), null, null));
    }

    // ---- bureau: 6-month window relative to the report date (Aug 2026 -> window Mar..Aug 2026) ----

    @Test
    void crifDpdFourMonthsAgoFails() {
        String h = "Aug:2026,000/STD|Jul:2026,000/STD|Jun:2026,000/STD|May:2026,030/STD|Apr:2026,000/STD";
        assertThat(CustomerTrustSignalsService.bureau(facts(h), REPORT)).isEqualTo("FAIL");
    }

    @Test
    void crifDpdEightMonthsAgoOnlyPasses() {
        String h = "Aug:2026,000/STD|Jul:2026,000/STD|Jun:2026,000/STD|May:2026,000/STD|Apr:2026,000/STD"
                + "|Mar:2026,000/STD|Feb:2026,000/STD|Jan:2026,000/STD|Dec:2025,045/STD";
        assertThat(CustomerTrustSignalsService.bureau(facts(h), REPORT)).isEqualTo("PASS");
    }

    @Test
    void experianWindowFollowsTheTradelinesOwnDateReported() {
        // Report Aug 2026 -> window Mar..Aug. Char 0 is the tradeline own Date_Reported month.
        assertThat(CustomerTrustSignalsService.bureau(one("000100000", "2026-08-10", null), REPORT)).isEqualTo("FAIL");
        assertThat(CustomerTrustSignalsService.bureau(one("000000010", "2026-08-10", null), REPORT)).isEqualTo("PASS");
        // Reported in May: chars 0..2 = May, Apr, Mar are in the window; char 3 = Feb is not.
        assertThat(CustomerTrustSignalsService.bureau(one("000100", "2026-05-02", null), REPORT)).isEqualTo("PASS");
        assertThat(CustomerTrustSignalsService.bureau(one("001000", "2026-05-02", null), REPORT)).isEqualTo("FAIL");
        // Last reported before the window opened: no evidence at all.
        assertThat(CustomerTrustSignalsService.bureau(one("1111", "2026-02-02", null), REPORT)).isEqualTo("NOT_CHECKED");
    }

    @Test
    void legacyExperianFactsUseClosedOnWhenNoDateReported() {
        assertThat(CustomerTrustSignalsService.bureau(one("1111", null, "2026-01-10"), REPORT)).isEqualTo("NOT_CHECKED");
        // Closed April 2026: only Apr, Mar are in the window.
        assertThat(CustomerTrustSignalsService.bureau(one("001", null, "2026-04-30"), REPORT)).isEqualTo("PASS");
        assertThat(CustomerTrustSignalsService.bureau(one("010", null, "2026-04-30"), REPORT)).isEqualTo("FAIL");
        // Open, no date: current behaviour (first six chars).
        assertThat(CustomerTrustSignalsService.bureau(one("000100", null, null), REPORT)).isEqualTo("FAIL");
    }

    @Test
    void crifNpaAssetClassFailsEvenWithCleanDpd() {
        assertThat(CustomerTrustSignalsService.bureau(facts("Aug:2026,000/SUB|Jul:2026,000/STD"), REPORT)).isEqualTo("FAIL");
        assertThat(CustomerTrustSignalsService.bureau(facts("Aug:2026,XXX/SMA-1"), REPORT)).isEqualTo("FAIL");
        assertThat(CustomerTrustSignalsService.bureau(facts("Jan:2026,000/LSS|Aug:2026,000/STD"), REPORT)).isEqualTo("PASS");
    }

    @Test
    void noHistoryIsNotChecked() {
        assertThat(CustomerTrustSignalsService.bureau(facts("N", ""), REPORT)).isEqualTo("NOT_CHECKED");
        assertThat(CustomerTrustSignalsService.bureau(facts("Aug:2026,XXX/XXX"), REPORT)).isEqualTo("NOT_CHECKED");
        assertThat(CustomerTrustSignalsService.bureau(null, REPORT)).isEqualTo("NOT_CHECKED");
        assertThat(CustomerTrustSignalsService.bureau(new BureauReportFacts(null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null), REPORT)).isEqualTo("NOT_CHECKED");
    }

    // ---- UAN / EMAIL tri-state --------------------------------------------------------------------

    @Test
    void uanTriState() {
        assertThat(service.uan("PASS", "{}")).isEqualTo("PASS");
        assertThat(service.uan("REVIEW", "{\"found\":true,\"uan\":\"1001\"}")).isEqualTo("PASS");
        assertThat(service.uan("REVIEW", "{\"found\":false,\"uan\":null}")).isEqualTo("FAIL");
        assertThat(service.uan("REVIEW", "{\"found\":true}")).isEqualTo("FAIL");
        assertThat(service.uan("PENDING", "{\"found\":true,\"uan\":\"1\"}")).isEqualTo("NOT_CHECKED");
        assertThat(service.uan("REVIEW", "{\"providerError\":true}")).isEqualTo("NOT_CHECKED");
        assertThat(service.uan("REVIEW", "{\"found\":false,\"reason\":\"NO_IDENTIFIER\"}")).isEqualTo("NOT_CHECKED");
        assertThat(service.uan("REVIEW", "{\"found\":false,\"tooManyRecords\":true}")).isEqualTo("PASS");
    }

    @Test
    void emailTriState() {
        assertThat(service.email("PASS", null)).isEqualTo("PASS");
        assertThat(service.email("REVIEW", "{}")).isEqualTo("FAIL");
        assertThat(service.email("FAIL", null)).isEqualTo("FAIL");
        assertThat(service.email("PENDING", null)).isEqualTo("NOT_CHECKED");
    }

    // ---- batching ----------------------------------------------------------------------------------

    private static LoanApplication app(long id, long customerId) {
        LoanApplication a = new LoanApplication();
        a.setId(id);
        a.setCustomerId(customerId);
        return a;
    }

    private static CaseFailureRow row(long appId, String type, String status) {
        CaseFailureRow r = mock(CaseFailureRow.class);
        when(r.getApplicationId()).thenReturn(appId);
        when(r.getCheckType()).thenReturn(type);
        when(r.getStatus()).thenReturn(status);
        return r;
    }

    private static java.time.Instant pulled() {
        return REPORT.atStartOfDay(java.time.ZoneId.of("Asia/Kolkata")).toInstant();
    }

    private static BriefMetaRow meta(long appId) {
        BriefMetaRow m = mock(BriefMetaRow.class);
        when(m.getApplicationId()).thenReturn(appId);
        when(m.getGeneratedAt()).thenReturn(pulled());
        return m;
    }

    private static BriefFactsRow briefFacts(long appId, BureauReportFacts f) throws Exception {
        BriefFactsRow b = mock(BriefFactsRow.class);
        when(b.getApplicationId()).thenReturn(appId);
        when(b.getFacts()).thenReturn(new ObjectMapper().writeValueAsString(f));
        when(b.getGeneratedAt()).thenReturn(pulled());
        return b;
    }

    @Test
    void pageOfCustomersIssuesConstantCallsAndFetchesFactsOnlyForNewestCacheMisses() throws Exception {
        List<Long> customerIds = new ArrayList<>();
        List<LoanApplication> applications = new ArrayList<>();
        for (long c = 1; c <= 50; c++) {
            customerIds.add(c);
            applications.add(app(1000 + c, c));
        }
        applications.add(app(5, 1)); // customer 1 also has an OLDER application that ran bureau
        when(apps.findByCustomerIdIn(any())).thenReturn(applications);
        List<CaseFailureRow> rows = List.of(
                row(1001, "EMAIL", "PASS"), row(1001, "BUREAU", "PASS"), row(5, "BUREAU", "PASS"),
                row(1002, "BUREAU", "PENDING"));
        List<BriefMetaRow> metas = List.of(meta(1001), meta(5));
        List<BriefFactsRow> briefs = List.of(briefFacts(1001, facts("Aug:2026,000/STD|Jul:2026,000/STD")));
        when(verifications.findByApplicationIdInAndCheckTypeIn(any(), any())).thenReturn(rows);
        when(profiles.findBriefMetaByApplicationIdIn(any())).thenReturn(metas);
        when(profiles.findBriefFactsByApplicationIdIn(any())).thenReturn(briefs);

        Map<Long, TrustSignals> out = service.forCustomers(customerIds);
        service.forCustomers(customerIds); // second page view: the verdict is cached

        assertThat(out).hasSize(51 - 1);
        assertThat(out.get(1L).bureau()).isEqualTo("PASS");
        assertThat(out.get(1L).uan()).isEqualTo("NOT_CHECKED");
        assertThat(out.get(1L).email()).isEqualTo("PASS");
        assertThat(out.get(2L)).isEqualTo(TrustSignals.NONE);
        verify(apps, times(2)).findByCustomerIdIn(any());
        verify(verifications, times(2)).findByApplicationIdInAndCheckTypeIn(any(), any());
        verify(profiles, times(2)).findBriefMetaByApplicationIdIn(any());
        verify(profiles, times(1)).findBriefFactsByApplicationIdIn(argThat(c -> c.size() == 1 && c.contains(1001L)));
        verifyNoMoreInteractions(apps, verifications, profiles);
    }

    private static CaseFailureRow bureauRow(long appId, String status, String derived) {
        CaseFailureRow r = row(appId, "BUREAU", status);
        when(r.getDerived()).thenReturn(derived);
        return r;
    }

    private String bureauFor(List<LoanApplication> applications, List<CaseFailureRow> rows,
                             List<BriefMetaRow> metas) throws Exception {
        when(apps.findByCustomerIdIn(any())).thenReturn(applications);
        when(verifications.findByApplicationIdInAndCheckTypeIn(any(), any())).thenReturn(rows);
        when(profiles.findBriefMetaByApplicationIdIn(any())).thenReturn(metas);
        BriefFactsRow bad = briefFacts(20, facts("Aug:2026,030/STD"));
        when(profiles.findBriefFactsByApplicationIdIn(any())).thenReturn(List.of(bad));
        return service.forCustomers(List.of(7L)).get(7L).bureau();
    }

    @Test
    void genuineNewerNoHitHidesAnOlderBadReport() throws Exception {
        List<CaseFailureRow> rows = List.of(bureauRow(20, "PASS", null), bureauRow(21, "REVIEW", "{\"noRecord\":true}"));
        List<BriefMetaRow> metas = List.of(meta(20));
        assertThat(bureauFor(List.of(app(20, 7), app(21, 7)), rows, metas)).isEqualTo("NOT_CHECKED");
        verify(profiles, times(0)).findBriefFactsByApplicationIdIn(any());
    }

    @Test
    void deferredMissingFieldAndProviderErrorRowsNeverDecide() throws Exception {
        for (String derived : List.of("{\"missingProfileField\":\"dob\"}", "{\"providerError\":true}",
                "{\"deferred\":true}")) {
            org.mockito.Mockito.reset(apps, verifications, profiles);
            List<CaseFailureRow> rows = List.of(bureauRow(20, "PASS", null), bureauRow(21, "REVIEW", derived));
            List<BriefMetaRow> metas = List.of(meta(20));
            assertThat(bureauFor(List.of(app(20, 7), app(21, 7)), rows, metas)).isEqualTo("FAIL");
        }
    }

    @Test
    void reborrowWithFactsAndNoBureauRowIsJudgedFromItsFacts() throws Exception {
        List<BriefMetaRow> metas = List.of(meta(20));
        assertThat(bureauFor(List.of(app(20, 7)), List.of(), metas)).isEqualTo("FAIL");
    }

    @Test
    void identityMismatchMakesTheStarNotChecked() throws Exception {
        List<CaseFailureRow> rows = List.of(bureauRow(20, "REVIEW", "{\"identityMismatch\":\"PAN_NAME\"}"));
        List<BriefMetaRow> metas = List.of(meta(20));
        assertThat(bureauFor(List.of(app(20, 7)), rows, metas)).isEqualTo("NOT_CHECKED");
        verify(profiles, times(0)).findBriefFactsByApplicationIdIn(any());
    }

    // ---- reasons ----------------------------------------------------------------------------------

    private static BureauReportFacts lender(String lender, String typeCode, String history, String dateReported) {
        BureauTradeline t = new BureauTradeline(lender, "XXXX1", typeCode, null, null, null, null, null, null,
                null, history, null, null, null, dateReported);
        return new BureauReportFacts(null, null, null, null, null, null, 700, 1, 1, 0, 0, null, null, null,
                0, "R", new BureauDetail(List.of(t), 1, List.of(), null, null));
    }

    @Test
    void crifFailNamesLenderAccountTypeMonthAndDpd() {
        var v = CustomerTrustSignalsService.verdict(
                lender("HDFC BANK", "05", "Aug:2026,000/STD|Jul:2026,030/STD", null), REPORT);
        assertThat(v.star()).isEqualTo("FAIL");
        assertThat(v.why()).startsWith("HDFC BANK · ").endsWith(" · Jul 2026: 30 DPD");
        assertThat(v.why().split(" · ")).hasSize(3);
    }

    @Test
    void crifAssetClassFailReason() {
        var v = CustomerTrustSignalsService.verdict(lender("ICICI", null, "Aug:2026,000/SUB", null), REPORT);
        assertThat(v.why()).isEqualTo("ICICI · Aug 2026: asset class SUB");
    }

    @Test
    void experianBucketFailReason() {
        // char 3 of a tradeline reported 2026-08 is May 2026; bucket 1 = 30-59 DPD
        var v = CustomerTrustSignalsService.verdict(lender("AXIS", null, "000100000", "2026-08-10"), REPORT);
        assertThat(v.why()).isEqualTo("AXIS · May 2026: 30-59 DPD");
    }

    @Test
    void passCarriesAccountCountWindowAndReportDate() {
        var crif = CustomerTrustSignalsService.verdict(facts("Aug:2026,000/STD", "Jul:2026,000/STD"), REPORT);
        assertThat(crif.why()).isEqualTo("No DPD on 2 accounts, 6 months to Aug 2026 (report 15 Aug 2026)");
        var exp = CustomerTrustSignalsService.verdict(one("000000010", "2026-08-10", null), REPORT);
        assertThat(exp.why()).isEqualTo(
                "No DPD on 1 account, 6 months to Aug 2026 (report 15 Aug 2026); Experian shows 30+ DPD only");
    }

    @Test
    void notCheckedReasons() {
        assertThat(CustomerTrustSignalsService.verdict(facts("N"), REPORT).why())
                .isEqualTo("No account history in the last 6 months");
        assertThat(CustomerTrustSignalsService.verdict(null, REPORT).star()).isEqualTo("NOT_CHECKED");
    }

    @Test
    void identityMismatchAndNoRecordReasons() throws Exception {
        List<LoanApplication> one = List.of(app(20, 7));
        List<BriefMetaRow> metas = List.of(meta(20));
        List<CaseFailureRow> mismatch = List.of(bureauRow(20, "REVIEW", "{\"identityMismatch\":\"PAN_NAME\"}"));
        List<CaseFailureRow> noHit = List.of(bureauRow(20, "REVIEW", "{\"noRecord\":true}"));
        when(apps.findByCustomerIdIn(any())).thenReturn(one);
        when(verifications.findByApplicationIdInAndCheckTypeIn(any(), any())).thenReturn(mismatch);
        when(profiles.findBriefMetaByApplicationIdIn(any())).thenReturn(metas);
        assertThat(service.forCustomers(List.of(7L)).get(7L).bureauWhy())
                .isEqualTo("Report may be another person: PAN_NAME");

        when(verifications.findByApplicationIdInAndCheckTypeIn(any(), any())).thenReturn(noHit);
        when(profiles.findBriefMetaByApplicationIdIn(any())).thenReturn(List.of());
        assertThat(service.forCustomers(List.of(7L)).get(7L).bureauWhy())
                .isEqualTo("Bureau has no record for this customer");

        // a newer no-hit (no brief) hides an older report
        List<BriefMetaRow> old = List.of(meta(19));
        List<CaseFailureRow> both = List.of(bureauRow(19, "PASS", null), bureauRow(20, "REVIEW", "{\"noRecord\":true}"));
        when(apps.findByCustomerIdIn(any())).thenReturn(List.of(app(19, 7), app(20, 7)));
        when(verifications.findByApplicationIdInAndCheckTypeIn(any(), any())).thenReturn(both);
        when(profiles.findBriefMetaByApplicationIdIn(any())).thenReturn(old);
        assertThat(service.forCustomers(List.of(7L)).get(7L).bureauWhy())
                .isEqualTo("Bureau has no record for this customer");

        when(apps.findByCustomerIdIn(any())).thenReturn(one);
        when(verifications.findByApplicationIdInAndCheckTypeIn(any(), any())).thenReturn(List.of());
        when(profiles.findBriefMetaByApplicationIdIn(any())).thenReturn(List.of());
        TrustSignals none = service.forCustomers(List.of(7L)).get(7L);
        assertThat(none.bureauWhy()).isEqualTo("No bureau report yet");
        assertThat(none.uanWhy()).isEqualTo("EPFO check not run");
        assertThat(none.emailWhy()).isEqualTo("Work-email check not run");
    }

    @Test
    void uanReasons() {
        assertThat(service.uanVerdict("REVIEW", "{\"found\":true,\"uan\":\"100412345673\",\"uanMasked\":\"1004XXXX5673\","
                + "\"employerName\":\"ACME LTD\",\"declaredEmployer\":\"Globex\"}", null).why())
                .isEqualTo("UAN 100412345673 found · EPFO shows ACME LTD, declared Globex");
        assertThat(service.uanVerdict("PASS", "{\"found\":true,\"uan\":\"1\",\"uanMasked\":\"XX1\","
                + "\"employerName\":\"Acme\",\"declaredEmployer\":\"ACME\",\"dateOfExit\":\"2026-05-01\"}", null).why())
                .isEqualTo("UAN 1 found · exited 2026-05-01");
        assertThat(service.uanVerdict("PASS", "{\"uan\":\"\",\"uanMasked\":\"XX9\"}", null).why())
                .isEqualTo("UAN XX9 found");
        assertThat(service.uanVerdict("REVIEW", "{\"tooManyRecords\":true,\"uanCount\":3}", null).why())
                .isEqualTo("3 UAN records matched");
        assertThat(service.uanVerdict("REVIEW", "{\"reason\":\"NO_IDENTIFIER\"}", null).why())
                .isEqualTo("No identifier to search EPFO");
        assertThat(service.uanVerdict("PENDING", null, null).why()).isEqualTo("EPFO check pending");
        assertThat(service.uanVerdict("REVIEW", "{\"providerError\":true}", null).why())
                .isEqualTo("EPFO unavailable — will retry");
        assertThat(service.uanVerdict("REVIEW", "{\"found\":false}", "No EPFO record").why())
                .isEqualTo("No EPFO record");
    }

    @Test
    void emailReasons() {
        assertThat(service.emailVerdict("PASS", "{\"domain\":\"acme.com\",\"matchedEstablishment\":\"ACME LTD\"}", null).why())
                .isEqualTo("acme.com: email + employer matched (ACME LTD)");
        assertThat(service.emailVerdict("REVIEW", "{\"verified\":true,\"genericEmail\":true,\"domain\":\"gmail.com\"}", null).why())
                .isEqualTo("Not an official email (gmail.com)");
        assertThat(service.emailVerdict("REVIEW", "{\"verified\":false}", null).why()).isEqualTo("Email not verified");
        assertThat(service.emailVerdict("REVIEW", "{\"verified\":true,\"genericEmail\":false}", null).why())
                .isEqualTo("Employer not matched — manual review");
        assertThat(service.emailVerdict("REVIEW", "{\"providerError\":true}", null).star()).isEqualTo("NOT_CHECKED");
    }
}
