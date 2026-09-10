package com.navix.loan.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.navix.common.exception.BusinessException;
import com.navix.common.security.ActorContext;
import com.navix.common.security.CurrentActor;
import com.navix.loan.dto.LeadDtos.ImportRow;
import com.navix.loan.entity.Lead;
import com.navix.loan.repository.CustomerProfileRepository;
import com.navix.loan.repository.LeadRepository;
import com.navix.loan.service.LeadImportService.ImportRun;
import com.navix.loan.service.LeadImportService.NumberedRow;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The row rules the 2000-row importer encoded, re-asserted against the chunked path that replaced
 * it. These are the semantics that must not drift when the transport changes.
 */
@ExtendWith(MockitoExtension.class)
class LeadImportServiceTest {

    @Mock private LeadRepository leadRepository;
    @Mock private CustomerProfileRepository customerProfileRepository;
    @Mock private JdbcTemplate jdbcTemplate;

    private LeadImportService service;

    @BeforeEach
    void setUp() {
        service = new LeadImportService(leadRepository, customerProfileRepository, jdbcTemplate);
    }

    @AfterEach
    void clear() {
        ActorContext.clear();
    }

    private ImportRun run(boolean merge) {
        return new ImportRun(77L, "Bulk import: leads.csv", merge);
    }

    private static List<NumberedRow> rows(ImportRow... rows) {
        List<NumberedRow> out = new ArrayList<>();
        for (int i = 0; i < rows.length; i++) {
            out.add(new NumberedRow(i + 1, rows[i]));
        }
        return out;
    }

    private static ImportRow row(String name, String mobile, String pan, String pincode, String email) {
        return new ImportRow(name, mobile, pan, pincode, email);
    }

    private static Lead lead(long id, String name, String mobile, String pan, String email,
            String pincode, Long ownerDsaId, String source) {
        Lead l = new Lead();
        l.setId(id);
        l.setName(name);
        l.setMobile(mobile);
        l.setPan(pan);
        l.setEmail(email);
        l.setPincode(pincode);
        l.setOwnerDsaId(ownerDsaId);
        l.setSource(source);
        return l;
    }

    private void noExistingRecords() {
        when(leadRepository.findByMobileIn(any())).thenReturn(List.of());
        when(customerProfileRepository.findMobilesIn(any())).thenReturn(List.of());
    }

    /** The batch args the service handed to JDBC, one entry per inserted row. */
    private int capturedInsertCount() {
        ArgumentCaptor<BatchPreparedStatementSetter> captor =
                ArgumentCaptor.forClass(BatchPreparedStatementSetter.class);
        verify(jdbcTemplate).batchUpdate(anyString(), captor.capture());
        return captor.getValue().getBatchSize();
    }

    @Test
    void insertsNewRows() {
        noExistingRecords();
        ImportRun run = run(false);

        service.processChunk(rows(row("Ravi", "9876543210", null, null, null)), run);

        assertThat(run.getInsertedCount()).isEqualTo(1);
        assertThat(capturedInsertCount()).isEqualTo(1);
    }

    @Test
    void badRowIsReportedAndSkipped_theRestOfTheChunkStillLands() {
        noExistingRecords();
        ImportRun run = run(false);

        // The original importer rejected the WHOLE file on the first bad line. At 200k rows that is
        // unusable, so a bad row is now counted, reported, and stepped over.
        service.processChunk(rows(
                row("Ravi", "9876543210", null, null, null),
                row("", "9876543211", null, null, null),
                row("Asha", "12345", null, null, null),
                row("Meera", "9876543212", null, null, null)), run);

        assertThat(run.getIssueCount()).isEqualTo(2);
        assertThat(run.getIssues()).extracting("field").containsExactly("name", "mobile");
        assertThat(run.getInsertedCount()).isEqualTo(2);
        assertThat(run.getProcessedRows()).isEqualTo(4);
    }

    @Test
    void inFileDuplicateByMobile_dropsLaterRow() {
        noExistingRecords();
        ImportRun run = run(false);

        service.processChunk(rows(
                row("Ravi", "9876543210", null, null, null),
                row("Ravi Again", "9876543210", null, null, null)), run);

        assertThat(run.getInsertedCount()).isEqualTo(1);
        assertThat(run.getSkippedDuplicates()).isEqualTo(1);
    }

    /**
     * The reason the run carries seen-sets rather than deduping per chunk: at 200k rows a mobile's
     * twin is routinely tens of thousands of rows — and several chunks — away.
     */
    @Test
    void inFileDuplicateIsCaughtAcrossChunkBoundaries() {
        noExistingRecords();
        ImportRun run = run(false);

        service.processChunk(rows(row("Ravi", "9876543210", null, null, null)), run);
        service.processChunk(rows(row("Ravi Again", "9876543210", null, null, null)), run);

        assertThat(run.getInsertedCount()).isEqualTo(1);
        assertThat(run.getSkippedDuplicates()).isEqualTo(1);
    }

    @Test
    void existingLeadWithoutMerge_isSkippedNotInserted() {
        when(leadRepository.findByMobileIn(any()))
                .thenReturn(List.of(lead(9L, "Ravi", "9876543210", null, null, null, null, "DSA")));
        when(customerProfileRepository.findMobilesIn(any())).thenReturn(List.of());
        ImportRun run = run(false);

        service.processChunk(rows(row("Ravi", "9876543210", null, "560001", "r@x.com")), run);

        assertThat(run.getSkippedDuplicates()).isEqualTo(1);
        assertThat(run.getInsertedCount()).isZero();
        verify(jdbcTemplate, never()).batchUpdate(anyString(), any(BatchPreparedStatementSetter.class));
    }

    @Test
    void merge_fillsOnlyBlanks_nameAndMobileUntouched() {
        Lead existing = lead(9L, "Old Name", "9876543210", null, null, null, null, "DSA");
        when(leadRepository.findByMobileIn(any())).thenReturn(List.of(existing));
        when(leadRepository.findByPanIn(any())).thenReturn(List.of());
        when(customerProfileRepository.findMobilesIn(any())).thenReturn(List.of());
        when(customerProfileRepository.findPansIn(any())).thenReturn(List.of());
        ImportRun run = run(true);

        service.processChunk(rows(row("New Name", "9876543210", "ABCDE1234F", "560001", "r@x.com")), run);

        assertThat(run.getMergedCount()).isEqualTo(1);
        assertThat(existing.getEmail()).isEqualTo("r@x.com");
        assertThat(existing.getPincode()).isEqualTo("560001");
        assertThat(existing.getPan()).isEqualTo("ABCDE1234F");
        assertThat(existing.getName()).isEqualTo("Old Name");
        assertThat(existing.getMobile()).isEqualTo("9876543210");
    }

    /** Keeps V55's {@code uq_lead_dsa_pan} intact — a DSA-owned lead never gets its PAN rewritten. */
    @Test
    void merge_neverFillsPanOnADsaOwnedLead() {
        Lead existing = lead(9L, "Ravi", "9876543210", null, null, null, 42L, "DSA");
        when(leadRepository.findByMobileIn(any())).thenReturn(List.of(existing));
        when(leadRepository.findByPanIn(any())).thenReturn(List.of());
        when(customerProfileRepository.findMobilesIn(any())).thenReturn(List.of());
        when(customerProfileRepository.findPansIn(any())).thenReturn(List.of());
        ImportRun run = run(true);

        service.processChunk(rows(row("Ravi", "9876543210", "ABCDE1234F", null, null)), run);

        assertThat(existing.getPan()).isNull();
        assertThat(run.getSkippedDuplicates()).isEqualTo(1);
        assertThat(run.getMergedCount()).isZero();
    }

    @Test
    void existingCustomerByMobile_isAlwaysSkipped() {
        when(leadRepository.findByMobileIn(any())).thenReturn(List.of());
        when(customerProfileRepository.findMobilesIn(any())).thenReturn(List.of("9876543210"));
        ImportRun run = run(true);

        service.processChunk(rows(row("Ravi", "9876543210", null, null, null)), run);

        assertThat(run.getSkippedCustomers()).isEqualTo(1);
        assertThat(run.getInsertedCount()).isZero();
    }

    @Test
    void existingCustomerByPan_isAlwaysSkipped() {
        when(leadRepository.findByMobileIn(any())).thenReturn(List.of());
        when(leadRepository.findByPanIn(any())).thenReturn(List.of());
        when(customerProfileRepository.findMobilesIn(any())).thenReturn(List.of());
        when(customerProfileRepository.findPansIn(any())).thenReturn(List.of("ABCDE1234F"));
        ImportRun run = run(false);

        service.processChunk(rows(row("Ravi", "9876543210", "ABCDE1234F", null, null)), run);

        assertThat(run.getSkippedCustomers()).isEqualTo(1);
        assertThat(run.getInsertedCount()).isZero();
    }

    /** Also proves a chunk with nothing valid in it costs zero queries. */
    @Test
    void issuesAreCappedButStillCounted() {
        ImportRun run = run(false);
        List<NumberedRow> chunk = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            chunk.add(new NumberedRow(i + 1, row("", "not-a-mobile", null, null, null)));
        }

        service.processChunk(chunk, run);

        assertThat(run.getIssues()).hasSize(LeadImportService.MAX_ISSUES);
        assertThat(run.getIssueCount()).isEqualTo(120); // a blank name AND a bad mobile per row
        assertThat(run.getInsertedCount()).isZero();
        verify(leadRepository, never()).findByMobileIn(any());
    }

    // ---- access -------------------------------------------------------------------------

    @Test
    void anyStaffRoleMayImport_dsaIncluded() {
        for (String role : List.of("ADMIN", "TELECALLER", "ACCOUNTANT", "COLLECTION_EXECUTIVE", "DSA")) {
            ActorContext.set(new CurrentActor("77", "Someone", role));
            assertThat(LeadImportService.requireStaffId()).isEqualTo(77L);
        }
    }

    @Test
    void borrowerMayNotImport() {
        ActorContext.set(new CurrentActor("5", "Ravi", "BORROWER"));
        assertThatThrownBy(LeadImportService::requireStaffId)
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Staff sign-in required");
    }

    @Test
    void anUnidentifiedActorMayNotImport() {
        ActorContext.clear(); // resolves to CurrentActor.SYSTEM, whose id is not a staff id
        assertThatThrownBy(LeadImportService::requireStaffId)
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Staff identity required");
    }
}
