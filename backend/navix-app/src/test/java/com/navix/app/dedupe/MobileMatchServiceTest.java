package com.navix.app.dedupe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navix.app.dedupe.DedupeDtos.MobileMatch;
import com.navix.app.dedupe.DedupeDtos.MobileMatchView;
import com.navix.common.exception.BusinessException;
import com.navix.common.security.ActorContext;
import com.navix.common.security.CurrentActor;
import com.navix.common.staff.StaffDirectory;
import com.navix.loan.domain.ApplicationStatus;
import com.navix.loan.entity.ApplicationReference;
import com.navix.loan.entity.BureauPhone;
import com.navix.loan.entity.CustomerProfile;
import com.navix.loan.entity.Lead;
import com.navix.loan.entity.LoanApplication;
import com.navix.loan.repository.ApplicationReferenceRepository;
import com.navix.loan.repository.ApplicationVerificationRepository;
import com.navix.loan.repository.ApplicationVerificationRepository.BureauRawRow;
import com.navix.loan.repository.BureauPhoneRepository;
import com.navix.loan.repository.CustomerProfileRepository;
import com.navix.loan.repository.LeadRepository;
import com.navix.loan.repository.LoanApplicationRepository;
import com.navix.loan.service.CustomerService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MobileMatchServiceTest {

    private static final Long ME = 1L;
    private static final String MY_MOBILE = "9000000001";
    private static final String BUREAU_MOBILE = "9111111111";
    private static final String REF_MOBILE = "9222222222";

    @Mock private LoanApplicationRepository applications;
    @Mock private CustomerProfileRepository profiles;
    @Mock private ApplicationVerificationRepository verifications;
    @Mock private ApplicationReferenceRepository references;
    @Mock private BureauPhoneRepository bureauPhones;
    @Mock private LeadRepository leads;
    @Mock private StaffDirectory staffDirectory;
    @Mock private CustomerService customerService;

    private MobileMatchService service;

    @BeforeEach
    void setUp() {
        service = new MobileMatchService(applications, profiles, verifications, references, bureauPhones, leads,
                staffDirectory, new ObjectMapper(), customerService);
        actingAs("CREDIT_HEAD");
        LoanApplication old = app(10L, ME);
        LoanApplication newest = app(11L, ME);
        when(applications.findByCustomerId(ME)).thenReturn(List.of(old, newest));
        CustomerProfile p = new CustomerProfile();
        p.setApplicationId(11L);
        p.setMobile(MY_MOBILE);
        when(profiles.findByApplicationIdIn(List.of(11L, 10L))).thenReturn(List.of(p));
        when(profiles.findOtherBorrowersByMobileIn(anyCollection(), any())).thenReturn(List.of());
        when(references.findByCustomerId(ME)).thenReturn(List.of());
        when(references.findOtherReferencesByMobileIn(anyCollection(), any())).thenReturn(List.of());
        when(bureauPhones.findOtherCustomersByMobileIn(anyCollection(), any())).thenReturn(List.of());
        when(leads.findByMobileIn(anyCollection())).thenReturn(List.of());
        when(verifications.findBureauRaw(anyCollection())).thenReturn(List.of());
        when(customerService.visibleAmong(anyCollection())).thenAnswer(i -> Set.copyOf((java.util.Collection<Long>) i.getArgument(0)));
    }

    @AfterEach
    void clear() {
        ActorContext.clear();
    }

    private static void actingAs(String role) {
        ActorContext.set(new CurrentActor("12", "Someone", role));
    }

    private static LoanApplication app(Long id, Long customerId) {
        LoanApplication a = new LoanApplication();
        a.setId(id);
        a.setCustomerId(customerId);
        a.setStatus(ApplicationStatus.ACTIVE);
        return a;
    }

    private static BureauRawRow raw(Long appId, String json, String derived) {
        return new BureauRawRow() {
            public Long getApplicationId() { return appId; }
            public String getRawResponse() { return json; }
            public Instant getUpdatedAt() { return Instant.parse("2026-08-10T00:00:00Z"); }
            public String getProvider() { return "fintrix"; }
            public String getDerived() { return derived; }
        };
    }

    private static String crif(String mobile) {
        return "{\"data\":{\"PERSONAL-INFO-VARIATION\":{\"PHONE-NUMBER-VARIATIONS\":{\"VARIATION\":"
                + "{\"VALUE\":\"" + mobile + "\",\"REPORTED-DATE\":\"31-07-2026\"}}}}}";
    }

    private static Lead lead(Long id, String mobile, Long ownerDsa) {
        Lead l = new Lead();
        l.setId(id);
        l.setName("Lena");
        l.setMobile(mobile);
        l.setSource("WALK_IN");
        l.setCreatedByStaffId(5L);
        l.setOwnerDsaId(ownerDsa);
        return l;
    }

    private static Object[] borrowerRow(String mobile, Long cid, Long appId) {
        return new Object[] {mobile, cid, appId, ApplicationStatus.ACTIVE, "Other Person",
                Instant.parse("2026-07-01T00:00:00Z")};
    }

    @Test
    void borrowerDsaAndAnonymousAreRejected() {
        for (String role : List.of("BORROWER", "DSA", "ANONYMOUS")) {
            actingAs(role);
            assertThatThrownBy(() -> service.matches(ME)).isInstanceOf(BusinessException.class);
        }
    }

    @Test
    void borrowerMatchIsOnePerOtherCustomerNewestFirst() {
        when(profiles.findOtherBorrowersByMobileIn(anyCollection(), any())).thenReturn(
                List.of(borrowerRow(MY_MOBILE, 2L, 30L), borrowerRow(MY_MOBILE, 2L, 20L)));
        MobileMatchView v = service.matches(ME);
        assertThat(v.matches()).hasSize(1);
        MobileMatch m = v.matches().get(0);
        assertThat(m.kind()).isEqualTo("BORROWER");
        assertThat(m.applicationId()).isEqualTo(30L);
        assertThat(m.borrowerName()).isEqualTo("Other Person");
        assertThat(m.inBook()).isTrue();
        assertThat(v.checked()).extracting(c -> c.mobile()).containsExactly(MY_MOBILE);
    }

    @Test
    void outOfBookMatchIsRedacted() {
        when(profiles.findOtherBorrowersByMobileIn(anyCollection(), any()))
                .thenReturn(List.<Object[]>of(borrowerRow(MY_MOBILE, 2L, 30L)));
        when(customerService.visibleAmong(anyCollection())).thenReturn(Set.of());
        MobileMatch m = service.matches(ME).matches().get(0);
        assertThat(m.inBook()).isFalse();
        assertThat(m.customerId()).isNull();
        assertThat(m.applicationId()).isNull();
        assertThat(m.applicationStatus()).isNull();
        assertThat(m.borrowerName()).isNull();
        assertThat(m.contactName()).isNull();
        assertThat(m.relation()).isNull();
        assertThat(m.at()).isNull();
        assertThat(m.kind()).isEqualTo("BORROWER");
        assertThat(m.mobile()).isEqualTo(MY_MOBILE);
    }

    @Test
    void leadsDroppedForCreditHeadKeptForTelecaller() {
        when(leads.findByMobileIn(anyCollection())).thenReturn(List.of(lead(9L, MY_MOBILE, null)));
        // MY_MOBILE is this customer's registered number: the own-origin lead is excluded either way.
        assertThat(service.matches(ME).matches()).isEmpty();

        // a lead on the bureau-reported number is a real match for the telecaller, invisible to a credit head
        when(verifications.findBureauRaw(anyCollection())).thenReturn(List.of(raw(11L, crif(BUREAU_MOBILE), null)));
        when(leads.findByMobileIn(anyCollection())).thenReturn(List.of(lead(9L, BUREAU_MOBILE, null)));
        when(staffDirectory.namesFor(anyCollection())).thenReturn(Map.of(5L, "Tessa"));
        assertThat(service.matches(ME).matches()).isEmpty(); // CREDIT_HEAD
        actingAs("TELECALLER");
        List<MobileMatch> tele = service.matches(ME).matches();
        assertThat(tele).hasSize(1);
        assertThat(tele.get(0).kind()).isEqualTo("LEAD");
        assertThat(tele.get(0).addedBy()).isEqualTo("Tessa");
        actingAs("ADMIN");
        assertThat(service.matches(ME).matches()).hasSize(1);
    }

    @Test
    void leadAuthorisationUsesRealRoleNotActingRole() {
        when(verifications.findBureauRaw(anyCollection())).thenReturn(List.of(raw(11L, crif(BUREAU_MOBILE), null)));
        when(leads.findByMobileIn(anyCollection())).thenReturn(List.of(lead(9L, BUREAU_MOBILE, null)));
        ActorContext.set(new CurrentActor("12", "Someone", "CREDIT_HEAD", "TELECALLER"));
        assertThat(service.matches(ME).matches()).isEmpty();
    }

    @Test
    void dsaOwnedLeadsAreExcluded() {
        actingAs("ADMIN");
        when(verifications.findBureauRaw(anyCollection())).thenReturn(List.of(raw(11L, crif(BUREAU_MOBILE), null)));
        when(leads.findByMobileIn(anyCollection())).thenReturn(List.of(lead(9L, BUREAU_MOBILE, 44L)));
        assertThat(service.matches(ME).matches()).isEmpty();
    }

    @Test
    void identityMismatchNumbersAreFlaggedButNotSearched() {
        when(verifications.findBureauRaw(anyCollection())).thenReturn(
                List.of(raw(11L, crif(BUREAU_MOBILE), "{\"identityMismatch\":\"PAN differs\"}")));
        MobileMatchView v = service.matches(ME);
        assertThat(v.bureau().identityMismatch()).isTrue();
        assertThat(v.bureau().numbers()).hasSize(1);
        assertThat(v.checked()).extracting(c -> c.mobile()).containsExactly(MY_MOBILE);
    }

    @Test
    void reborrowReadsBureauRawFromOlderApplication() {
        when(verifications.findBureauRaw(anyCollection())).thenReturn(List.of(raw(10L, crif(BUREAU_MOBILE), null)));
        MobileMatchView v = service.matches(ME);
        assertThat(v.bureau().applicationId()).isEqualTo(10L);
        assertThat(v.checked()).extracting(c -> c.mobile()).containsExactly(MY_MOBILE, BUREAU_MOBILE);
        assertThat(v.checked().get(1).sources()).containsExactly("BUREAU");
    }

    @Test
    void bureauVsBureauAndReferencesMatch() {
        when(verifications.findBureauRaw(anyCollection())).thenReturn(List.of(raw(11L, crif(BUREAU_MOBILE), null)));
        ApplicationReference mine = new ApplicationReference();
        mine.setMobile(REF_MOBILE);
        mine.setFullName("Rita");
        when(references.findByCustomerId(ME)).thenReturn(List.of(mine));

        BureauPhone bp = new BureauPhone();
        bp.setCustomerId(3L);
        bp.setApplicationId(40L);
        bp.setMobile(BUREAU_MOBILE);
        bp.setPulledAt(Instant.parse("2026-08-01T00:00:00Z"));
        when(bureauPhones.findOtherCustomersByMobileIn(anyCollection(), any())).thenReturn(List.of(bp));
        CustomerProfile other = new CustomerProfile();
        other.setApplicationId(40L);
        other.setFullName("Bureau Other");
        when(profiles.findByApplicationIdIn(List.of(40L))).thenReturn(List.of(other));
        when(applications.findAllById(List.of(40L))).thenReturn(List.of(app(40L, 3L)));

        ApplicationReference theirs = new ApplicationReference();
        theirs.setApplicationId(50L);
        theirs.setCustomerId(4L);
        theirs.setMobile(REF_MOBILE);
        theirs.setFullName("Rita K");
        theirs.setRelation("FRIEND");
        when(references.findOtherReferencesByMobileIn(anyCollection(), any()))
                .thenReturn(List.<Object[]>of(new Object[] {theirs, "Borrower Four", ApplicationStatus.CLOSED}));

        MobileMatchView v = service.matches(ME);
        assertThat(v.checked()).extracting(c -> c.mobile()).containsExactly(MY_MOBILE, BUREAU_MOBILE, REF_MOBILE);
        assertThat(v.checked().get(2).referenceName()).isEqualTo("Rita");
        assertThat(v.matches()).extracting(MobileMatch::kind).containsExactly("BUREAU", "REFERENCE");
        assertThat(v.matches().get(0).borrowerName()).isEqualTo("Bureau Other");
        assertThat(v.matches().get(1).contactName()).isEqualTo("Rita K");
        assertThat(v.matches().get(1).applicationStatus()).isEqualTo("CLOSED");
    }

    @Test
    void queryCountIsBoundedRegardlessOfMatches() {
        when(profiles.findOtherBorrowersByMobileIn(anyCollection(), any())).thenReturn(List.of(
                borrowerRow(MY_MOBILE, 2L, 30L), borrowerRow(MY_MOBILE, 3L, 31L), borrowerRow(MY_MOBILE, 4L, 32L)));
        service.matches(ME);
        verify(profiles, atMost(1)).findOtherBorrowersByMobileIn(anyCollection(), any());
        verify(customerService, atMost(1)).visibleAmong(anyCollection());
        verify(references, atMost(1)).findOtherReferencesByMobileIn(anyCollection(), any());
        verify(bureauPhones, atMost(1)).findOtherCustomersByMobileIn(anyCollection(), any());
    }

    @Test
    void sameCustomerViaRegisteredAndBureauIsCountedOnce() {
        when(profiles.findOtherBorrowersByMobileIn(anyCollection(), any()))
                .thenReturn(List.<Object[]>of(borrowerRow(MY_MOBILE, 2L, 30L)));
        BureauPhone bp = new BureauPhone();
        bp.setCustomerId(2L);
        bp.setApplicationId(30L);
        bp.setMobile(MY_MOBILE);
        when(bureauPhones.findOtherCustomersByMobileIn(anyCollection(), any())).thenReturn(List.of(bp));
        assertThat(service.matches(ME).matches()).extracting(MobileMatch::kind).containsExactly("BORROWER");
    }
}
