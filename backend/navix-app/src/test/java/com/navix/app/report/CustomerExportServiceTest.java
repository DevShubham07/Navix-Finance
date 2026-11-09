package com.navix.app.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.navix.app.report.CustomerExportFields.Field;
import com.navix.app.report.CustomerExportFields.Grain;
import com.navix.app.report.CustomerExportService.MatchRequest;
import com.navix.app.report.CustomerExportService.RowsRequest;
import com.navix.common.exception.BusinessException;
import com.navix.common.security.ActorContext;
import com.navix.common.security.CurrentActor;
import com.navix.loan.repository.LoanRepository;
import com.navix.loan.service.CustomerBookQuery;
import com.navix.loan.service.RepaymentService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.LongStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;

/** The gate, the allowlist and the SQL text; the statements themselves are run against Postgres by hand. */
class CustomerExportServiceTest {

    private final CustomerBookQuery book = mock(CustomerBookQuery.class);
    private final CustomerExportService service = new CustomerExportService(mock(JdbcTemplate.class), book,
            mock(LoanRepository.class), mock(RepaymentService.class));

    @AfterEach
    void clear() {
        ActorContext.clear();
    }

    private static MatchRequest match() {
        return new MatchRequest(null, null, "all", List.of(), "customer", false);
    }

    private static RowsRequest rows(List<Long> ids, List<String> fields, boolean idNumbers) {
        return new RowsRequest("customer", ids, fields, idNumbers);
    }

    @Test
    void onlyAdminOnTheRealRoleMayUseAnyEndpoint() {
        for (String role : List.of("CREDIT_HEAD", "ACCOUNTANT", "COLLECTION_HEAD", "DSA", "BORROWER", "TELECALLER")) {
            ActorContext.set(new CurrentActor("5", "x", role));
            assertThatThrownBy(service::catalog).isInstanceOf(BusinessException.class).hasMessageContaining("ADMIN");
            assertThatThrownBy(() -> service.match(match())).isInstanceOf(BusinessException.class);
            assertThatThrownBy(() -> service.rows(rows(List.of(1L), List.of("full_name"), false)))
                    .isInstanceOf(BusinessException.class);
        }
        // A working role of ADMIN does not lift a Credit Head; an ADMIN acting as someone else still passes.
        ActorContext.set(new CurrentActor("5", "x", "CREDIT_HEAD", "ADMIN"));
        assertThatThrownBy(service::catalog).isInstanceOf(BusinessException.class);
        ActorContext.set(new CurrentActor("1", "x", "ADMIN", "CREDIT_HEAD"));
        assertThat(service.catalog().fields()).isNotEmpty();
        ActorContext.clear();
        assertThatThrownBy(service::catalog).isInstanceOf(BusinessException.class);
        verifyNoInteractions(book);
    }

    @Test
    void unknownFieldKeyIsA400AndNothingRuns() {
        ActorContext.set(new CurrentActor("1", "x", "ADMIN"));
        assertThatThrownBy(() -> service.rows(rows(List.of(1L), List.of("full_name", "password_hash"), true)))
                .isInstanceOfSatisfying(CustomerExportException.class, e -> {
                    assertThat(e.status()).isEqualTo(400);
                    assertThat(e.getCode()).isEqualTo("UNKNOWN_FIELD");
                });
        // A key is matched whole: SQL smuggled in as a key is just an unknown key.
        assertThatThrownBy(() -> service.rows(rows(List.of(1L), List.of("a.id; drop table loan"), false)))
                .isInstanceOf(CustomerExportException.class);
    }

    @Test
    void anIdNumberFieldWithoutTheConfirmationIsRefusedOnTheServer() {
        ActorContext.set(new CurrentActor("1", "x", "ADMIN"));
        for (String key : List.of("aadhaar", "pan", "uan", "salary_account_number", "disbursal_account_number",
                "penny_drop_account_number", "verif_penny_drop_derived")) {
            assertThatThrownBy(() -> service.rows(rows(List.of(1L), List.of("full_name", key), false)))
                    .as(key)
                    .isInstanceOfSatisfying(CustomerExportException.class, e -> {
                        assertThat(e.status()).isEqualTo(400);
                        assertThat(e.getCode()).isEqualTo("ID_NUMBERS_NOT_CONFIRMED");
                    });
        }
    }

    @Test
    void aPageIsCappedAt200Ids() {
        ActorContext.set(new CurrentActor("1", "x", "ADMIN"));
        List<Long> tooMany = LongStream.rangeClosed(1, 201).boxed().toList();
        assertThatThrownBy(() -> service.rows(rows(tooMany, List.of("full_name"), false)))
                .isInstanceOfSatisfying(CustomerExportException.class, e -> assertThat(e.getCode()).isEqualTo("TOO_MANY_IDS"));
    }

    @Test
    void matchRejectsAnUnknownGroupBandGrainOrBackwardsDates() {
        ActorContext.set(new CurrentActor("1", "x", "ADMIN"));
        assertThatThrownBy(() -> service.match(new MatchRequest(null, null, "nope", List.of(), "customer", false)))
                .isInstanceOf(CustomerExportException.class);
        assertThatThrownBy(() -> service.match(new MatchRequest(null, null, "all", List.of("T1_T2"), "customer", false)))
                .isInstanceOf(CustomerExportException.class);
        assertThatThrownBy(() -> service.match(new MatchRequest(null, null, "all", List.of(), "both", false)))
                .isInstanceOf(CustomerExportException.class);
        assertThatThrownBy(() -> service.match(new MatchRequest(java.time.LocalDate.of(2026, 2, 2),
                java.time.LocalDate.of(2026, 1, 1), "all", List.of(), "customer", false)))
                .isInstanceOf(CustomerExportException.class);
    }

    /** Names that must never be reachable through any key, in any field expression or join. */
    private static final List<String> FORBIDDEN = List.of(
            "borrower_credential", "password_reset_token", "password", "token", "digilocker_client_id",
            "credit_brief_facts", "raw_response", "request_json", "response_json", "report_json_url",
            "report_excel_url", "s3_object_key", "file_password", "storage_uri", "authentication_url",
            "profile_change_log", "co_applicant", "digio_mandate_id", "proof_url", "proof_ref", "digilocker_session",
            "signup_application", "kyc_check", "kyc_case", "income_profile", "risk_assessment", "from borrower ");

    @Test
    void neverExportableDataIsNotReachableByAnyKey() {
        String joins = CustomerExportFields.joinNames().stream()
                .map(n -> CustomerExportFields.joinSql(new HashSet<>(List.of(n)), Grain.CUSTOMER)
                        + CustomerExportFields.joinSql(new HashSet<>(List.of(n)), Grain.APPLICATION))
                .collect(Collectors.joining(" ")).toLowerCase();
        for (String bad : FORBIDDEN) {
            assertThat(joins).as("join text mentions " + bad).doesNotContain(bad);
            for (Field f : CustomerExportFields.all()) {
                assertThat(f.key().toLowerCase()).as("key " + f.key()).doesNotContain(bad);
                assertThat(f.sql().toLowerCase()).as("sql of " + f.key() + " mentions " + bad).doesNotContain(bad);
            }
        }
        // application_document.data (the bytes) and the raw verification columns have no expression.
        for (Field f : CustomerExportFields.all()) {
            assertThat(f.sql()).as(f.key()).doesNotContainPattern("\\.data\\b");
            assertThat(f.sql()).as(f.key()).doesNotContainPattern("\\braw_response\\b");
        }
    }

    @Test
    void verificationDetailsAreRestrictedToTheHandoverWhitelist() {
        // The BUREAU check has no whitelist, so it has no details field at all.
        assertThat(CustomerExportFields.byKey("verif_bureau_derived")).isNull();
        assertThat(CustomerExportFields.byKey("verif_bureau_status")).isNotNull();
        String pan = CustomerExportFields.byKey("verif_pan_derived").sql();
        assertThat(pan).contains("'panStatus'").doesNotContain("'url'").contains("jsonb_typeof");
        // Details that can carry a full account number or UAN are ID-number fields.
        assertThat(CustomerExportFields.byKey("verif_penny_drop_derived").sensitive()).isTrue();
        assertThat(CustomerExportFields.byKey("verif_employment_derived").sensitive()).isTrue();
        assertThat(CustomerExportFields.byKey("verif_pan_derived").sensitive()).isFalse();
    }

    @Test
    void keysAreUniqueEveryGroupIsKnownAndIdNumbersAreFlagged() {
        List<Field> all = CustomerExportFields.all();
        assertThat(all.stream().map(Field::key).distinct().count()).isEqualTo(all.size());
        assertThat(all).allSatisfy(f -> assertThat(CustomerExportFields.GROUPS).contains(f.group()));
        assertThat(all.stream().filter(f -> f.group().equals(CustomerExportFields.G_ID))).allMatch(Field::sensitive);
        assertThat(all.stream().filter(Field::sensitive).map(Field::key)).contains("aadhaar", "pan", "uan",
                "salary_account_number", "disbursal_account_number", "penny_drop_account_number");
        // Digit-only identifiers are declared text-numbers so the browser can keep them intact in Excel.
        assertThat(CustomerExportFields.byKey("aadhaar").type()).isEqualTo(CustomerExportFields.Type.TEXTNUM);
        assertThat(CustomerExportFields.byKey("mobile").type()).isEqualTo(CustomerExportFields.Type.TEXTNUM);
    }

    @Test
    void onlyTheJoinsTheSelectedFieldsNeedAreEmitted() {
        List<Field> name = List.of(CustomerExportFields.byKey("full_name"));
        String sql = CustomerExportService.buildSql(Grain.CUSTOMER, name);
        assertThat(sql).contains("customer_profile").doesNotContain("lateral").doesNotContain("payment");
        List<Field> pay = List.of(CustomerExportFields.byKey("last_payment_amount"));
        String paySql = CustomerExportService.buildSql(Grain.APPLICATION, pay);
        // lp pulls in the loan it hangs off; nothing else.
        assertThat(paySql).contains("left join loan l on l.id = a.loan_id").contains(" lp on true")
                .doesNotContain("customer_profile").doesNotContain("v_pan");
        // Live figures need the loan id back, and nothing is exported as the cached loan.outstanding.
        String live = CustomerExportService.buildSql(Grain.CUSTOMER, List.of(CustomerExportFields.byKey("outstanding_live")));
        assertThat(live).contains("l.id as loan_pk").doesNotContain("l.outstanding");
        CustomerExportFields.all().forEach(f -> assertThat(f.sql()).doesNotContain("l.outstanding"));
    }

    @Test
    void everyFieldBuildsIntoOneStatementPerGrain() {
        for (Grain g : Grain.values()) {
            String sql = CustomerExportService.buildSql(g, CustomerExportFields.all());
            assertThat(sql).startsWith("select ").contains(":ids");
            assertThat(sql).doesNotContain("--").doesNotEndWith(";");
        }
    }

    /** Writes the all-fields statements for both grains so they can be run against Postgres by hand. */
    @Test
    @EnabledIfSystemProperty(named = "export.sql.dump", matches = ".+")
    void dumpSql() throws Exception {
        Path dir = Path.of(System.getProperty("export.sql.dump"));
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("customer.sql"), CustomerExportService.buildSql(Grain.CUSTOMER, CustomerExportFields.all()));
        Files.writeString(dir.resolve("application.sql"), CustomerExportService.buildSql(Grain.APPLICATION, CustomerExportFields.all()));
    }
}
