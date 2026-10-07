package com.navix.loan.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.navix.common.exception.BusinessException;
import com.navix.common.featureflag.FeatureFlagService;
import com.navix.common.notification.event.WaitlistSubmittedEvent;
import com.navix.common.security.ActorContext;
import com.navix.common.security.CurrentActor;
import com.navix.loan.entity.Loan;
import com.navix.loan.entity.OnboardingWaitlist;
import com.navix.loan.repository.CustomerProfileRepository;
import com.navix.loan.repository.LoanRepository;
import com.navix.loan.repository.OnboardingWaitlistRepository;
import com.navix.loan.service.OnboardingWaitlistService.SubmitRequest;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

@ExtendWith(MockitoExtension.class)
class OnboardingWaitlistServiceTest {

    /** A real Aadhaar-shaped number that passes Verhoeff (the util's own test vector). */
    private static final String VALID_AADHAAR = "234123412346";

    @Mock
    private FeatureFlagService featureFlags;
    @Mock
    private OnboardingWaitlistRepository waitlistRepository;
    @Mock
    private LoanRepository loanRepository;
    @Mock
    private CustomerProfileRepository profileRepository;
    @Mock
    private ApplicationEventPublisher eventPublisher;

    private OnboardingWaitlistService service;

    @BeforeEach
    void setUp() {
        service = new OnboardingWaitlistService(featureFlags, waitlistRepository, loanRepository,
                profileRepository, eventPublisher);
        lenient().when(featureFlags.isEnabled(OnboardingWaitlistService.FLAG, false)).thenReturn(true);
        lenient().when(waitlistRepository.save(any())).thenAnswer(i -> {
            OnboardingWaitlist w = i.getArgument(0);
            w.setId(1L);
            return w;
        });
        ActorContext.set(new CurrentActor("7", "7", "BORROWER"));
    }

    @AfterEach
    void clearActor() {
        ActorContext.clear();
    }

    private static SubmitRequest good() {
        return new SubmitRequest(" Rinku ", "rinku@example.com", "+91 73039 36960", "abcde1234f", "2341 2341 2346");
    }

    @Test
    void gateIsOpenWhenTheFlagIsOff() {
        when(featureFlags.isEnabled(OnboardingWaitlistService.FLAG, false)).thenReturn(false);
        assertThat(service.gate().gate()).isEqualTo("OPEN");
        verify(loanRepository, never()).findByCustomerId(any());
    }

    @Test
    void anyoneWithALoanKeepsTheNormalApp() {
        when(loanRepository.findByCustomerId(7L)).thenReturn(List.of(new Loan()));
        assertThat(service.gate().gate()).isEqualTo("OPEN");
        assertThatThrownBy(() -> service.submit(good()))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", "ONBOARDING_OPEN");
    }

    @Test
    void noLoanMeansTheFormThenSubmittedAfterOneSubmission() {
        when(loanRepository.findByCustomerId(7L)).thenReturn(List.of());
        when(waitlistRepository.findByCustomerId(7L)).thenReturn(Optional.empty());
        assertThat(service.gate().gate()).isEqualTo("FORM");

        var view = service.submit(good());

        assertThat(view.fullName()).isEqualTo("Rinku");
        assertThat(view.mobile()).isEqualTo("7303936960");
        assertThat(view.pan()).isEqualTo("ABCDE1234F");
        assertThat(view.aadhaarLast4()).isEqualTo("2346");
        verify(eventPublisher, times(1)).publishEvent(any(WaitlistSubmittedEvent.class));

        OnboardingWaitlist saved = new OnboardingWaitlist();
        saved.setFullName("Rinku");
        saved.setAadhaar(VALID_AADHAAR);
        when(waitlistRepository.findByCustomerId(7L)).thenReturn(Optional.of(saved));
        assertThat(service.gate().gate()).isEqualTo("SUBMITTED");
        assertThat(service.submit(good()).fullName()).isEqualTo("Rinku"); // idempotent echo
        verify(waitlistRepository, times(1)).save(any());
    }

    @Test
    void rejectsBadInputWithoutSaving() {
        when(loanRepository.findByCustomerId(7L)).thenReturn(List.of());
        when(waitlistRepository.findByCustomerId(7L)).thenReturn(Optional.empty());

        assertCode(new SubmitRequest("", "a@b.co", "7303936960", "ABCDE1234F", VALID_AADHAAR), "INVALID_NAME");
        assertCode(new SubmitRequest("R", "not-an-email", "7303936960", "ABCDE1234F", VALID_AADHAAR), "INVALID_EMAIL");
        assertCode(new SubmitRequest("R", "a@b", "7303936960", "ABCDE1234F", VALID_AADHAAR), "INVALID_EMAIL");
        assertCode(new SubmitRequest("R", "a@b.co", "12345", "ABCDE1234F", VALID_AADHAAR), "INVALID_MOBILE");
        assertCode(new SubmitRequest("R", "a@b.co", "7303936960", "ABC123", VALID_AADHAAR), "INVALID_PAN");
        assertCode(new SubmitRequest("R", "a@b.co", "7303936960", "ABCDE1234F", "234123412345"), "INVALID_AADHAAR");
        verify(waitlistRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void refusesAPanOrAadhaarAnotherCustomerHolds() {
        when(loanRepository.findByCustomerId(7L)).thenReturn(List.of());
        when(waitlistRepository.findByCustomerId(7L)).thenReturn(Optional.empty());

        when(profileRepository.existsPanForOtherCustomer("ABCDE1234F", 7L)).thenReturn(true);
        assertCode(good(), "DUPLICATE_PAN");

        when(profileRepository.existsPanForOtherCustomer("ABCDE1234F", 7L)).thenReturn(false);
        when(waitlistRepository.existsByPanAndCustomerIdNot("ABCDE1234F", 7L)).thenReturn(false);
        when(profileRepository.findOtherCustomerIdsByAadhaar(eq(VALID_AADHAAR), eq(7L))).thenReturn(List.of(9L));
        assertCode(good(), "DUPLICATE_AADHAAR");

        when(profileRepository.findOtherCustomerIdsByAadhaar(eq(VALID_AADHAAR), eq(7L))).thenReturn(List.of());
        when(waitlistRepository.existsByAadhaarAndCustomerIdNot(anyString(), eq(7L))).thenReturn(true);
        assertCode(good(), "DUPLICATE_AADHAAR");
        verify(waitlistRepository, never()).save(any());
    }

    @Test
    void onlyAdminReadsTheList() {
        for (String role : new String[] {"TELECALLER", "DSA", "CREDIT_HEAD", "BORROWER"}) {
            ActorContext.set(new CurrentActor("1", "1", role));
            assertThatThrownBy(() -> service.list(null))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("code", "FORBIDDEN_ROLE");
        }
        ActorContext.set(new CurrentActor("1", "1", "ADMIN"));
        OnboardingWaitlist w = new OnboardingWaitlist();
        w.setFullName("Rinku");
        w.setMobile("7303936960");
        w.setEmail("rinku@example.com");
        w.setPan("ABCDE1234F");
        w.setAadhaar(VALID_AADHAAR);
        when(waitlistRepository.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(w));
        assertThat(service.list("rink")).hasSize(1);
        assertThat(service.list("nobody")).isEmpty();
        assertThat(service.list(null).get(0).aadhaar()).isEqualTo(VALID_AADHAAR);
    }

    private void assertCode(SubmitRequest req, String code) {
        assertThatThrownBy(() -> service.submit(req))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", code);
    }
}
