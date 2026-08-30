package com.navix.loan.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.navix.common.exception.BusinessException;
import com.navix.common.security.ActorContext;
import com.navix.common.security.CurrentActor;
import com.navix.loan.dto.LeadDtos.ImportDuplicate;
import com.navix.loan.dto.LeadDtos.ImportExistingCustomer;
import com.navix.loan.dto.LeadDtos.ImportInFileDuplicate;
import com.navix.loan.dto.LeadDtos.ImportPreview;
import com.navix.loan.dto.LeadDtos.ImportRequest;
import com.navix.loan.dto.LeadDtos.ImportResult;
import com.navix.loan.dto.LeadDtos.ImportRow;
import com.navix.loan.entity.Lead;
import com.navix.loan.repository.CustomerProfileRepository;
import com.navix.loan.repository.LeadRepository;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class LeadImportServiceTest {

    @Mock private LeadRepository leadRepository;
    @Mock private CustomerProfileRepository customerProfileRepository;

    private LeadImportService service;

    @BeforeEach
    void setUp() {
        service = new LeadImportService(leadRepository, customerProfileRepository);
    }

    @AfterEach
    void clear() {
        ActorContext.clear();
    }

    private static void setAdmin() {
        ActorContext.set(new CurrentActor("77", "Ops Admin", "ADMIN"));
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

    @Test
    void preview_forbiddenForNonAdmin() {
        ActorContext.set(new CurrentActor("5", "Tara", "TELECALLER"));
        ImportRequest req = new ImportRequest("leads.csv",
                List.of(new ImportRow("Ravi", "9876543210", null, null, null)), false);

        assertThatThrownBy(() -> service.preview(req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("ADMIN");
    }

    @Test
    void preview_inFileDuplicate_dropsLaterRow() {
        setAdmin();
        when(leadRepository.findByMobileIn(any())).thenReturn(List.of());
        when(customerProfileRepository.findMobilesIn(any())).thenReturn(List.of());

        ImportRequest req = new ImportRequest("leads.csv", List.of(
                new ImportRow("Ravi", "9876543210", null, null, null),
                new ImportRow("Ravi Duplicate", "9876543210", null, null, null)), false);

        ImportPreview preview = service.preview(req);

        assertThat(preview.totalRows()).isEqualTo(2);
        assertThat(preview.newRows()).isEqualTo(1);
        assertThat(preview.inFileDuplicates()).hasSize(1);
        ImportInFileDuplicate dup = preview.inFileDuplicates().get(0);
        assertThat(dup.row()).isEqualTo(2);
        assertThat(dup.duplicateOfRow()).isEqualTo(1);
        assertThat(dup.matchedOn()).isEqualTo("MOBILE");
    }

    @Test
    void preview_existingLeadDuplicateByMobile_fillsEmailAndPincodeOnly() {
        setAdmin();
        Lead existing = lead(5L, "Anita K", "9812345678", null, null, null, null, "TELECALLER");
        when(leadRepository.findByMobileIn(any())).thenReturn(List.of(existing));
        when(customerProfileRepository.findMobilesIn(any())).thenReturn(List.of());

        ImportRequest req = new ImportRequest("leads.csv", List.of(
                new ImportRow("Anita Kumar", "9812345678", null, "560001", "anita@example.com")), false);

        ImportPreview preview = service.preview(req);

        assertThat(preview.duplicates()).hasSize(1);
        ImportDuplicate dup = preview.duplicates().get(0);
        assertThat(dup.matchedOn()).isEqualTo("MOBILE");
        assertThat(dup.existingLeadId()).isEqualTo(5L);
        assertThat(dup.fillableFields()).containsExactlyInAnyOrder("email", "pincode");
    }

    @Test
    void preview_dsaOwnedExistingLead_neverOffersPanAsFillable() {
        setAdmin();
        Lead existing = lead(6L, "Bob D", "9812345679", null, null, null, 99L, "TELECALLER");
        when(leadRepository.findByMobileIn(any())).thenReturn(List.of(existing));
        when(leadRepository.findByPanIn(any())).thenReturn(List.of());
        when(customerProfileRepository.findMobilesIn(any())).thenReturn(List.of());
        when(customerProfileRepository.findPansIn(any())).thenReturn(List.of());

        ImportRequest req = new ImportRequest("leads.csv", List.of(
                new ImportRow("Bob Duplicate", "9812345679", "ABCDE1234F", null, null)), false);

        ImportPreview preview = service.preview(req);

        assertThat(preview.duplicates()).hasSize(1);
        assertThat(preview.duplicates().get(0).fillableFields()).doesNotContain("pan");

        verify(leadRepository, times(1)).findByMobileIn(any());
        verify(leadRepository, times(1)).findByPanIn(any());
        verify(customerProfileRepository, times(1)).findMobilesIn(any());
        verify(customerProfileRepository, times(1)).findPansIn(any());
    }

    @Test
    void preview_mobileAndPanResolveToDifferentLeads_resolvesToMobileMatchAndBlocksPanFill() {
        setAdmin();
        Lead leadX = lead(10L, "Carl X", "9812345670", null, null, null, null, "TELECALLER");
        Lead leadY = lead(11L, "Someone Else", "9000000000", "FGHIJ5678K", "y@example.com", "400002", null, "OTHER");
        when(leadRepository.findByMobileIn(any())).thenReturn(List.of(leadX));
        when(leadRepository.findByPanIn(any())).thenReturn(List.of(leadY));
        when(customerProfileRepository.findMobilesIn(any())).thenReturn(List.of());
        when(customerProfileRepository.findPansIn(any())).thenReturn(List.of());

        ImportRequest req = new ImportRequest("leads.csv", List.of(
                new ImportRow("Carl", "9812345670", "FGHIJ5678K", "400001", "carl@example.com")), false);

        ImportPreview preview = service.preview(req);

        assertThat(preview.duplicates()).hasSize(1);
        ImportDuplicate dup = preview.duplicates().get(0);
        assertThat(dup.matchedOn()).isEqualTo("MOBILE");
        assertThat(dup.existingLeadId()).isEqualTo(10L);
        assertThat(dup.fillableFields()).contains("email", "pincode").doesNotContain("pan");
    }

    @Test
    void preview_existingCustomerByPanOrMobile_alwaysSkipped_panMaskedCorrectly() {
        setAdmin();
        when(leadRepository.findByMobileIn(any())).thenReturn(List.of());
        when(leadRepository.findByPanIn(any())).thenReturn(List.of());
        when(customerProfileRepository.findPansIn(any())).thenReturn(List.of("ABCDE1234F"));
        when(customerProfileRepository.findMobilesIn(any())).thenReturn(List.of("9812340000"));

        ImportRequest req = new ImportRequest("leads.csv", List.of(
                new ImportRow("Known By Pan", "9111111111", "ABCDE1234F", null, null),
                new ImportRow("Known By Mobile", "9812340000", null, null, null)), false);

        ImportPreview preview = service.preview(req);

        assertThat(preview.existingCustomers()).hasSize(2);
        ImportExistingCustomer byPan = preview.existingCustomers().get(0);
        assertThat(byPan.panMasked()).isEqualTo("ABXXXXX34F");
        ImportExistingCustomer byMobile = preview.existingCustomers().get(1);
        assertThat(byMobile.panMasked()).isNull();
        assertThat(preview.newRows()).isEqualTo(0);
    }

    @Test
    void commit_insertsNewLeads_unattributed() {
        setAdmin();
        when(leadRepository.findByMobileIn(any())).thenReturn(List.of());
        when(customerProfileRepository.findMobilesIn(any())).thenReturn(List.of());
        when(leadRepository.saveAll(any())).thenAnswer(inv -> {
            List<Lead> arg = inv.getArgument(0);
            List<Lead> saved = new ArrayList<>();
            long id = 100L;
            for (Lead l : arg) {
                l.setId(id++);
                saved.add(l);
            }
            return saved;
        });

        ImportRequest req = new ImportRequest("bulk-leads.csv", List.of(
                new ImportRow("Ravi", "9876543210", null, null, null),
                new ImportRow("Deepa", "9876543211", null, null, null)), false);

        ImportResult result = service.commit(req);

        assertThat(result.inserted()).isEqualTo(2);
        assertThat(result.merged()).isEqualTo(0);
        assertThat(result.skippedDuplicates()).isEqualTo(0);
        assertThat(result.skippedCustomers()).isEqualTo(0);
        assertThat(result.insertedIds()).containsExactly(100L, 101L);

        var captor = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(leadRepository).saveAll(captor.capture());
        List<Lead> saved = captor.getValue();
        assertThat(saved).hasSize(2);
        for (Lead l : saved) {
            assertThat(l.getSource()).isEqualTo("OTHER");
            assertThat(l.getOwnerDsaId()).isNull();
            assertThat(l.getCreatedByStaffId()).isEqualTo(77L);
            assertThat(l.getCallStatus()).isEqualTo("NOT_CALLED");
            assertThat(l.getSourceDetail()).isEqualTo("CSV import: bulk-leads.csv");
        }
    }

    @Test
    void commit_withMerge_fillsOnlyBlanks_nameAndMobileUntouched() {
        setAdmin();
        Lead existing = lead(5L, "Anita K", "9812345678", null, null, null, null, "TELECALLER");
        when(leadRepository.findByMobileIn(any())).thenReturn(List.of(existing));
        when(customerProfileRepository.findMobilesIn(any())).thenReturn(List.of());
        when(leadRepository.saveAll(any())).thenReturn(List.of());

        ImportRequest req = new ImportRequest("leads.csv", List.of(
                new ImportRow("Anita Kumar Renamed", "9812345678", null, "560001", "anita@example.com")), true);

        ImportResult result = service.commit(req);

        assertThat(result.inserted()).isEqualTo(0);
        assertThat(result.merged()).isEqualTo(1);
        assertThat(result.skippedDuplicates()).isEqualTo(0);

        // Merge fills blanks only — name (and mobile) are never overwritten.
        assertThat(existing.getName()).isEqualTo("Anita K");
        assertThat(existing.getMobile()).isEqualTo("9812345678");
        assertThat(existing.getEmail()).isEqualTo("anita@example.com");
        assertThat(existing.getPincode()).isEqualTo("560001");
    }

    @Test
    void commit_withIssues_throwsCsvInvalid() {
        setAdmin();
        ImportRequest req = new ImportRequest("leads.csv", List.of(
                new ImportRow("", "9876543210", null, null, null)), false);

        assertThatThrownBy(() -> service.commit(req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Row 1");
    }

    @Test
    void commit_moreThan2000Rows_throwsCsvTooLarge() {
        setAdmin();
        List<ImportRow> rows = new ArrayList<>();
        for (int i = 0; i < 2001; i++) {
            rows.add(new ImportRow("Person " + i, "9000000000", null, null, null));
        }
        ImportRequest req = new ImportRequest("huge.csv", rows, false);

        assertThatThrownBy(() -> service.commit(req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("2000");
    }
}
