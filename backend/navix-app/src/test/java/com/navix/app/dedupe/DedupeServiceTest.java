package com.navix.app.dedupe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navix.app.dedupe.DedupeDtos.DedupeView;
import com.navix.common.exception.BusinessException;
import com.navix.common.security.ActorContext;
import com.navix.common.security.CurrentActor;
import com.navix.iam.domain.BlocklistType;
import com.navix.iam.entity.BlocklistEntry;
import com.navix.iam.repository.BlocklistEntryRepository;
import com.navix.loan.entity.ApplicationRejection;
import com.navix.loan.entity.ApplicationVerification;
import com.navix.loan.entity.CustomerProfile;
import com.navix.loan.entity.LoanApplication;
import com.navix.loan.repository.ApplicationRejectionRepository;
import com.navix.loan.repository.ApplicationVerificationRepository;
import com.navix.loan.repository.CustomerProfileRepository;
import com.navix.loan.repository.LoanApplicationRepository;
import com.navix.loan.service.ApplicationVerificationService;
import com.navix.loan.service.CustomerService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DedupeServiceTest {

    private static final Long CUSTOMER = 77L;
    private static final Long APP = 500L;

    @Mock private LoanApplicationRepository applications;
    @Mock private CustomerProfileRepository profiles;
    @Mock private ApplicationVerificationRepository verifications;
    @Mock private ApplicationRejectionRepository rejections;
    @Mock private BlocklistEntryRepository blocklist;
    @Mock private CustomerService customerService;

    private DedupeService service;

    @BeforeEach
    void setUp() {
        service = new DedupeService(applications, profiles, verifications, rejections, blocklist, new ObjectMapper(), customerService);
    }

    @AfterEach
    void clearActor() {
        ActorContext.clear();
    }

    private static void actingAs(String role) {
        ActorContext.set(new CurrentActor("12", "Someone", role));
    }

    private void customerWithApp(String pan, String mobile, String aadhaar, String account) {
        LoanApplication app = mock(LoanApplication.class);
        lenient().when(app.getId()).thenReturn(APP);
        lenient().when(app.getDisbursalAccountNumber()).thenReturn(account);
        when(applications.findByCustomerId(CUSTOMER)).thenReturn(List.of(app));
        CustomerProfile p = mock(CustomerProfile.class);
        lenient().when(p.getPan()).thenReturn(pan);
        lenient().when(p.getMobile()).thenReturn(mobile);
        lenient().when(p.getAadhaar()).thenReturn(aadhaar);
        lenient().when(p.getFullName()).thenReturn("Asha");
        lenient().when(p.getApplicationId()).thenReturn(APP);
        when(profiles.findByApplicationIdIn(any())).thenReturn(List.of(p));
    }

    private static BlocklistEntry entry(String reason) {
        BlocklistEntry e = new BlocklistEntry();
        e.setReason(reason);
        e.setActive(true);
        return e;
    }

    @Test
    void dsaAndBorrowerAreRejected() {
        actingAs("DSA");
        assertThatThrownBy(() -> service.dedupe(CUSTOMER)).isInstanceOf(BusinessException.class)
                .hasMessage("DSAs cannot view customer data");
        actingAs("BORROWER");
        assertThatThrownBy(() -> service.dedupe(CUSTOMER)).isInstanceOf(BusinessException.class)
                .hasMessage("Staff role required");
    }

    @Test
    void anonymousAndNullActorAreRejected() {
        actingAs("ANONYMOUS");
        assertThatThrownBy(() -> service.dedupe(CUSTOMER)).isInstanceOf(BusinessException.class)
                .hasMessage("Staff role required");
        ActorContext.set(new CurrentActor("1", "x", null));
        assertThatThrownBy(() -> service.dedupe(CUSTOMER)).isInstanceOf(BusinessException.class)
                .hasMessage("Staff role required");
    }

    @Test
    void aadhaarRefHitIsReported() {
        actingAs("ADMIN");
        customerWithApp("ABCDE1234F", "9876543210", "123412341234", null);
        when(verifications.findByApplicationIdAndCheckType(APP, ApplicationVerificationService.AADHAAR_DUPLICATE))
                .thenReturn(Optional.empty());
        lenient().when(blocklist.findByTypeAndValue(any(), org.mockito.ArgumentMatchers.anyString())).thenReturn(Optional.empty());
        when(blocklist.findByTypeAndValue(BlocklistType.AADHAAR_REF, "123412341234")).thenReturn(Optional.of(entry("x")));
        DedupeView v = service.dedupe(CUSTOMER);
        assertThat(v.blocklistHits()).extracting("type").containsExactly("AADHAAR_REF");
        assertThat(v.blocklistHits().get(0).maskedValue()).isEqualTo("XXXXXXXX1234");
    }

    @Test
    void abandonedNewestShellDoesNotHideOlderKycProfile() {
        actingAs("ADMIN");
        LoanApplication newer = mock(LoanApplication.class);
        lenient().when(newer.getId()).thenReturn(600L);
        LoanApplication older = mock(LoanApplication.class);
        lenient().when(older.getId()).thenReturn(APP);
        when(applications.findByCustomerId(CUSTOMER)).thenReturn(List.of(older, newer));
        CustomerProfile shell = mock(CustomerProfile.class);
        lenient().when(shell.getApplicationId()).thenReturn(600L);
        CustomerProfile real = mock(CustomerProfile.class);
        lenient().when(real.getApplicationId()).thenReturn(APP);
        lenient().when(real.getFullName()).thenReturn("Asha");
        lenient().when(real.getPan()).thenReturn("ABCDE1234F");
        when(profiles.findByApplicationIdIn(any())).thenReturn(List.of(shell, real));
        lenient().when(verifications.findByApplicationIdAndCheckType(any(), any())).thenReturn(Optional.empty());
        when(blocklist.findByTypeAndValue(BlocklistType.PAN, "ABCDE1234F")).thenReturn(Optional.of(entry("fraud")));
        assertThat(service.dedupe(CUSTOMER).blocklistHits()).extracting("type").containsExactly("PAN");
    }

    @Test
    void outOfScopeCustomerThrows() {
        actingAs("CREDIT_EXECUTIVE");
        org.mockito.Mockito.doThrow(new BusinessException("NOT_FOUND", "nope")).when(customerService).assertVisible(CUSTOMER);
        assertThatThrownBy(() -> service.dedupe(CUSTOMER)).isInstanceOf(BusinessException.class);
    }

    @Test
    void nonAdminDoesNotSeeReasons() {
        DedupeView v = assemble("CREDIT_HEAD");
        assertThat(v.blocklistHits()).extracting("reason").containsOnlyNulls();
        assertThat(v.rejection().reasonDetail()).isNull();
        assertThat(v.rejection().reasonCode()).isEqualTo("MANUAL");
        assertThat(v.blocklistHits()).extracting("maskedValue").containsExactly("XXXXXX234F", "XXXXXXX3445");
    }

    @Test
    void adminSeesReasons() {
        DedupeView v = assemble("ADMIN");
        assertThat(v.blocklistHits()).extracting("reason").containsExactly("fraud", "chargeback");
        assertThat(v.rejection().reasonDetail()).isEqualTo("cooling off");
    }

    @Test
    void assemblesAadhaarAndBlocklist() {
        DedupeView v = assemble("ADMIN");
        assertThat(v.aadhaar().status()).isEqualTo("REVIEW");
        assertThat(v.aadhaar().otherCustomerIds()).containsExactly(11L, 12L);
        assertThat(v.blocklistHits()).extracting("type").containsExactly("PAN", "BANK_ACCOUNT");
    }

    private DedupeView assemble(String role) {
        actingAs(role);
        customerWithApp("ABCDE1234F", "9876543210", "123412341234", "00112233445");
        ApplicationVerification dup = mock(ApplicationVerification.class);
        when(dup.getStatus()).thenReturn("REVIEW");
        when(dup.getDerived()).thenReturn("{\"otherCustomerIds\":[11,12]}");
        when(dup.getMessage()).thenReturn("held by another");
        when(verifications.findByApplicationIdAndCheckType(APP, ApplicationVerificationService.AADHAAR_DUPLICATE))
                .thenReturn(Optional.of(dup));
        when(blocklist.findByTypeAndValue(BlocklistType.PAN, "ABCDE1234F")).thenReturn(Optional.of(entry("fraud")));
        when(blocklist.findByTypeAndValue(BlocklistType.BANK_ACCOUNT, "00112233445"))
                .thenReturn(Optional.of(entry("chargeback")));
        when(blocklist.findByTypeAndValue(ArgumentMatchers.eq(BlocklistType.PHONE), ArgumentMatchers.anyString()))
                .thenReturn(Optional.empty());
        when(blocklist.findByTypeAndValue(ArgumentMatchers.eq(BlocklistType.AADHAAR_REF), ArgumentMatchers.anyString()))
                .thenReturn(Optional.empty());
        ApplicationRejection r = mock(ApplicationRejection.class);
        Instant until = Instant.now().plusSeconds(86400);
        when(r.getApplicationId()).thenReturn(APP);
        when(r.getReasonCode()).thenReturn("MANUAL");
        lenient().when(r.getReasonDetail()).thenReturn("cooling off");
        when(r.getBlockedUntil()).thenReturn(until);
        when(rejections.findFirstByMobileAndBlockedUntilAfterOrderByBlockedUntilDesc(
                ArgumentMatchers.eq("9876543210"), ArgumentMatchers.any())).thenReturn(Optional.of(r));

        return service.dedupe(CUSTOMER);
    }

    @Test
    void noHitsGivesEmptyListAndNullRejection() {
        actingAs("ADMIN");
        customerWithApp("ABCDE1234F", "9876543210", null, null);
        when(verifications.findByApplicationIdAndCheckType(APP, ApplicationVerificationService.AADHAAR_DUPLICATE))
                .thenReturn(Optional.empty());
        when(blocklist.findByTypeAndValue(ArgumentMatchers.any(), ArgumentMatchers.anyString()))
                .thenReturn(Optional.empty());
        when(rejections.findFirstByMobileAndBlockedUntilAfterOrderByBlockedUntilDesc(
                ArgumentMatchers.anyString(), ArgumentMatchers.any())).thenReturn(Optional.empty());

        DedupeView v = service.dedupe(CUSTOMER);

        assertThat(v.aadhaar().status()).isNull();
        assertThat(v.blocklistHits()).isEmpty();
        assertThat(v.rejection()).isNull();
    }
}
