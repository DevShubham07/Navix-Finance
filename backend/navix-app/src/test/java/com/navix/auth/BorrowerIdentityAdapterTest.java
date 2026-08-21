package com.navix.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link BorrowerIdentityAdapter} against the real {@code borrower_mobile} claim shape — the guard
 * an ADMIN mobile correction (navix-loan's {@code CustomerService}) relies on to catch a number that
 * collides with a DIFFERENT customer's login identity even when the two numbers are never
 * string-equal (login identity keeps only the last 7 digits — see
 * {@code AuthController.deriveCustomerId}).
 */
@ExtendWith(MockitoExtension.class)
class BorrowerIdentityAdapterTest {

    @Mock private BorrowerMobileRepository mobileRepository;

    private BorrowerIdentityAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new BorrowerIdentityAdapter(mobileRepository);
    }

    private static BorrowerMobile claim(long customerId, String mobile) {
        BorrowerMobile row = new BorrowerMobile();
        row.setCustomerId(customerId);
        row.setMobile(mobile);
        return row;
    }

    @Test
    void noCollisionWhenTheDerivedIdIsUnclaimed() {
        when(mobileRepository.findById(6543210L)).thenReturn(Optional.empty());

        assertThat(adapter.wouldCollideWithAnotherCustomer("9876543210", 9000001L)).isFalse();
    }

    @Test
    void noCollisionWhenTheClaimBelongsToTheSameCustomer() {
        when(mobileRepository.findById(6543210L)).thenReturn(Optional.of(claim(9000001L, "9876543210")));

        assertThat(adapter.wouldCollideWithAnotherCustomer("9876543210", 9000001L)).isFalse();
    }

    @Test
    void collidesWhenTheClaimBelongsToADifferentCustomer() {
        when(mobileRepository.findById(6543210L)).thenReturn(Optional.of(claim(4200002L, "9876543210")));

        assertThat(adapter.wouldCollideWithAnotherCustomer("9876543210", 9000001L)).isTrue();
    }

    /**
     * The whole reason this port exists: TWO DIFFERENT 10-digit numbers sharing the same last 7
     * digits derive the SAME login identity, so a collision must be detected even though the raw
     * mobile strings are never equal — a plain string-uniqueness check would miss this entirely.
     */
    @Test
    void collidesForADifferentNumberThatSharesTheSameLastSevenDigits() {
        // "8006543210" and "9876543210" both end in the same last 7 digits, "6543210".
        when(mobileRepository.findById(6543210L)).thenReturn(Optional.of(claim(4200002L, "9876543210")));

        assertThat(adapter.wouldCollideWithAnotherCustomer("8006543210", 9000001L)).isTrue();
    }

    @Test
    void stripsNonDigitCharactersBeforeDeriving() {
        when(mobileRepository.findById(6543210L)).thenReturn(Optional.empty());

        assertThat(adapter.wouldCollideWithAnotherCustomer("+91 98765 43210", 9000001L)).isFalse();
    }
}
