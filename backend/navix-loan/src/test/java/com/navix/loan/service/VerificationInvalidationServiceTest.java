package com.navix.loan.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.navix.loan.entity.CustomerProfile;
import com.navix.loan.entity.ApplicationVerification;
import com.navix.loan.repository.CustomerProfileRepository;
import com.navix.loan.repository.ApplicationVerificationRepository;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class VerificationInvalidationServiceTest {

    private static final Long APP = 4L;

    @Mock private ApplicationVerificationRepository verificationRepo;
    @Mock private CustomerProfileRepository profileRepo;

    private VerificationInvalidationService service() {
        return new VerificationInvalidationService(verificationRepo, profileRepo);
    }

    private ApplicationVerification row(String type, String status) {
        ApplicationVerification v = new ApplicationVerification();
        v.setApplicationId(APP);
        v.setCheckType(type);
        v.setStatus(status);
        return v;
    }

    @Test
    void changingAddressResetsAddressCheckToPendingAndClearsFlag() {
        ApplicationVerification address = row("ADDRESS", "PASS");
        CustomerProfile profile = new CustomerProfile();
        profile.setApplicationId(APP);
        profile.setAddressVerified(true);
        when(verificationRepo.findByApplicationIdAndCheckType(APP, "ADDRESS")).thenReturn(Optional.of(address));
        when(profileRepo.findByApplicationId(APP)).thenReturn(Optional.of(profile));

        service().invalidateForFields(APP, Set.of("address"));

        assertThat(address.getStatus()).isEqualTo("PENDING");
        assertThat(profile.getAddressVerified()).isFalse();
        verify(verificationRepo).save(address);
        verify(profileRepo).save(profile);
    }

    @Test
    void salaryAndBankMapToSalaryAndPennyDropChecks() {
        ApplicationVerification salary = row("SALARY", "PASS");
        ApplicationVerification penny = row("PENNY_DROP", "PASS");
        CustomerProfile profile = new CustomerProfile();
        profile.setApplicationId(APP);
        profile.setPennyDropVerified(true);
        when(verificationRepo.findByApplicationIdAndCheckType(APP, "SALARY")).thenReturn(Optional.of(salary));
        when(verificationRepo.findByApplicationIdAndCheckType(APP, "PENNY_DROP")).thenReturn(Optional.of(penny));
        when(profileRepo.findByApplicationId(APP)).thenReturn(Optional.of(profile));

        service().invalidateForFields(APP, Set.of("monthlySalaryPaise", "salaryBank"));

        assertThat(salary.getStatus()).isEqualTo("PENDING");
        assertThat(penny.getStatus()).isEqualTo("PENDING");
        assertThat(profile.getPennyDropVerified()).isFalse();
    }

    @Test
    void nonVerificationFieldIsANoOp() {
        service().invalidateForFields(APP, Set.of("emergencyContactName"));
        verify(verificationRepo, never()).save(any());
        verify(profileRepo, never()).save(any());
    }
}
