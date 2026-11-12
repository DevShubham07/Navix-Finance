package com.navix.loan.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.navix.common.blocklist.BlocklistKey;
import com.navix.common.security.BorrowerIdentityPort;
import com.navix.loan.entity.CustomerProfile;
import com.navix.loan.entity.LoanApplication;
import com.navix.loan.repository.CustomerProfileRepository;
import com.navix.loan.repository.LoanApplicationRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CustomerBlocklistKeysTest {

    @Mock private LoanApplicationRepository applicationRepository;
    @Mock private CustomerProfileRepository profileRepository;
    @Mock private BorrowerIdentityPort borrowerIdentity;
    @InjectMocks private CustomerBlocklistKeys keys;

    @Test
    void collectsEveryIdentifierAcrossApplicationsNormalisedAndOnce() {
        LoanApplication old = new LoanApplication();
        old.setId(1L);
        old.setDisbursalAccountNumber("1111-2222");
        LoanApplication latest = new LoanApplication();
        latest.setId(2L);
        CustomerProfile p1 = new CustomerProfile();
        p1.setMobile("9876543210");
        p1.setPan("abcde1234f");               // an older PAN still counts
        CustomerProfile p2 = new CustomerProfile();
        p2.setMobile("+91 98765 43210");       // same number, written differently
        p2.setPan("ABCDE1234F");
        p2.setAadhaar("1234 5678 9012");
        p2.setSalaryAccountNumber("333444");
        when(borrowerIdentity.loginMobile(7L)).thenReturn(Optional.of("9876543210"));
        when(applicationRepository.findByCustomerId(7L)).thenReturn(List.of(old, latest));
        when(profileRepository.findByApplicationId(1L)).thenReturn(Optional.of(p1));
        when(profileRepository.findByApplicationId(2L)).thenReturn(Optional.of(p2));

        assertThat(keys.forCustomer(7L)).containsExactlyInAnyOrder(
                new BlocklistKey("PHONE", "9876543210"),
                new BlocklistKey("PAN", "ABCDE1234F"),
                new BlocklistKey("BANK_ACCOUNT", "11112222"),
                new BlocklistKey("AADHAAR_REF", "123456789012"),
                new BlocklistKey("BANK_ACCOUNT", "333444"));
    }

    @Test
    void aBrandNewCustomerIsMatchedOnTheirLoginMobile() {
        when(borrowerIdentity.loginMobile(9L)).thenReturn(Optional.of("9000000001"));
        when(applicationRepository.findByCustomerId(9L)).thenReturn(List.of());

        assertThat(keys.forCustomer(9L)).containsExactly(new BlocklistKey("PHONE", "9000000001"));
    }
}
