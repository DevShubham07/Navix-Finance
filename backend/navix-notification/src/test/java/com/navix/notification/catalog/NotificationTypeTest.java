package com.navix.notification.catalog;

import static com.navix.common.notification.NotificationChannel.EMAIL;
import static com.navix.common.notification.NotificationChannel.IN_APP;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Catalog-level pins on the audience/channel changes made for the ADMIN + Credit Head KYC alert and
 * the ADMIN + Disbursement Head disbursement alert — the only thing that would catch a future
 * accidental revert (e.g. back to {@code TO_CREDIT_TEAM}, which also notifies Credit Executives).
 */
class NotificationTypeTest {

    @Test
    void kycSubmittedGoesToCreditHeadsAndAdminsOnly() {
        assertThat(NotificationType.KYC_SUBMITTED.audience())
                .containsExactlyInAnyOrder(RecipientPolicy.TO_CREDIT_HEADS, RecipientPolicy.TO_ADMINS);
        assertThat(NotificationType.KYC_SUBMITTED.channels()).contains(IN_APP, EMAIL);
    }

    @Test
    void loanAppliedFastTrackNotifiesDisbursementHeadsAndAdminsAndTheBorrower() {
        assertThat(NotificationType.LOAN_APPLIED_FAST_TRACK.audience()).containsExactlyInAnyOrder(
                RecipientPolicy.TO_DISBURSEMENT_HEADS, RecipientPolicy.TO_ADMINS, RecipientPolicy.TO_BORROWER);
        assertThat(NotificationType.LOAN_APPLIED_FAST_TRACK.channels()).contains(IN_APP, EMAIL);
    }
}
