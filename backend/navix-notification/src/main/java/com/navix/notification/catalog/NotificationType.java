package com.navix.notification.catalog;

import static com.navix.common.notification.NotificationCategory.COLLECTIONS;
import static com.navix.common.notification.NotificationCategory.CREDIT;
import static com.navix.common.notification.NotificationCategory.DISBURSEMENT;
import static com.navix.common.notification.NotificationCategory.KYC;
import static com.navix.common.notification.NotificationCategory.REPAYMENT;
import static com.navix.common.notification.NotificationCategory.STAFF_IAM;
import static com.navix.common.notification.NotificationCategory.SYSTEM;
import static com.navix.common.notification.NotificationChannel.EMAIL;
import static com.navix.common.notification.NotificationChannel.IN_APP;
import static com.navix.common.notification.NotificationChannel.SMS;
import static com.navix.notification.catalog.RecipientPolicy.TO_ACCOUNTANTS;
import static com.navix.notification.catalog.RecipientPolicy.TO_ASSIGNED_EXECUTIVE;
import static com.navix.notification.catalog.RecipientPolicy.TO_BORROWER;
import static com.navix.notification.catalog.RecipientPolicy.TO_COLLECTION_EXECUTIVES;
import static com.navix.notification.catalog.RecipientPolicy.TO_COLLECTION_HEADS;
import static com.navix.notification.catalog.RecipientPolicy.TO_CREDIT_HEADS;
import static com.navix.notification.catalog.RecipientPolicy.TO_DISBURSEMENT_HEADS;
import static com.navix.notification.catalog.RecipientPolicy.TO_KYC_APPROVERS;
import static com.navix.notification.catalog.RecipientPolicy.TO_STAFF_SUBJECT;

import com.navix.common.notification.NotificationCategory;
import com.navix.common.notification.NotificationChannel;
import java.util.Set;

/**
 * The self-describing catalog of every notification DhanBoost can emit. Each constant carries its
 * {@link NotificationCategory}, the {@link NotificationChannel}s to attempt, and the
 * {@link RecipientPolicy} audience. The enum {@code name()} is the template key
 * ({@code NotificationTemplates}). Channels are address-gated per recipient at dispatch (IN_APP
 * always; SMS only with a mobile — so staff never get SMS; EMAIL only with an email).
 */
public enum NotificationType {

    // ---- KYC ----
    KYC_SUBMITTED(KYC, Set.of(IN_APP), Set.of(TO_KYC_APPROVERS)),
    KYC_APPROVED(KYC, Set.of(IN_APP, SMS, EMAIL), Set.of(TO_BORROWER)),
    KYC_REJECTED(KYC, Set.of(IN_APP, SMS, EMAIL), Set.of(TO_BORROWER)),
    KYC_REMINDER(KYC, Set.of(IN_APP, SMS, EMAIL), Set.of(TO_BORROWER)),
    REBORROW_PREAPPROVED(KYC, Set.of(IN_APP, SMS), Set.of(TO_BORROWER)),
    REBORROW_REVIEW_PENDING(KYC, Set.of(IN_APP), Set.of(TO_KYC_APPROVERS, TO_BORROWER)),
    REBORROW_REVIEW_APPROVED(KYC, Set.of(IN_APP, SMS), Set.of(TO_BORROWER)),
    REBORROW_REVIEW_REJECTED(KYC, Set.of(IN_APP, SMS), Set.of(TO_BORROWER)),

    // ---- CREDIT ----
    LOAN_APPLIED(CREDIT, Set.of(IN_APP), Set.of(TO_CREDIT_HEADS)),
    CREDIT_ASSIGNED(CREDIT, Set.of(IN_APP), Set.of(TO_ASSIGNED_EXECUTIVE)),
    CREDIT_RECOMMENDED(CREDIT, Set.of(IN_APP), Set.of(TO_CREDIT_HEADS)),
    CREDIT_APPROVED(CREDIT, Set.of(IN_APP, EMAIL), Set.of(TO_BORROWER, TO_DISBURSEMENT_HEADS)),
    CREDIT_REJECTED(CREDIT, Set.of(IN_APP, SMS, EMAIL), Set.of(TO_BORROWER)),

    // ---- DISBURSEMENT ----
    LOAN_APPLIED_FAST_TRACK(DISBURSEMENT, Set.of(IN_APP), Set.of(TO_DISBURSEMENT_HEADS, TO_BORROWER)),
    DISBURSEMENT_PENDING_ACCOUNTANT(DISBURSEMENT, Set.of(IN_APP), Set.of(TO_ACCOUNTANTS)),
    DISBURSEMENT_FAILED(DISBURSEMENT, Set.of(IN_APP), Set.of(TO_DISBURSEMENT_HEADS)),
    DISBURSEMENT_REJECTED(DISBURSEMENT, Set.of(IN_APP, EMAIL), Set.of(TO_BORROWER)),
    LOAN_DISBURSED(DISBURSEMENT, Set.of(IN_APP, SMS, EMAIL), Set.of(TO_BORROWER)),

    // ---- REPAYMENT ----
    REPAYMENT_RECORDED(REPAYMENT, Set.of(IN_APP), Set.of(TO_ACCOUNTANTS, TO_BORROWER)),
    REPAYMENT_VERIFIED(REPAYMENT, Set.of(IN_APP, SMS), Set.of(TO_BORROWER)),
    REPAYMENT_REJECTED(REPAYMENT, Set.of(IN_APP, SMS), Set.of(TO_BORROWER)),
    // Time-driven reminders from the daily PaymentReminderScheduler.
    PAYMENT_DUE_SOON(REPAYMENT, Set.of(IN_APP, SMS, EMAIL), Set.of(TO_BORROWER)),
    PAYMENT_OVERDUE(REPAYMENT, Set.of(IN_APP, SMS, EMAIL), Set.of(TO_BORROWER)),
    LOAN_CLOSED(REPAYMENT, Set.of(IN_APP, SMS, EMAIL), Set.of(TO_BORROWER)),

    // ---- COLLECTIONS ----
    COLLECTION_CASE_OPENED(COLLECTIONS, Set.of(IN_APP), Set.of(TO_COLLECTION_HEADS, TO_COLLECTION_EXECUTIVES)),
    SETTLEMENT_PROPOSED(COLLECTIONS, Set.of(IN_APP), Set.of(TO_COLLECTION_HEADS)),
    SETTLEMENT_APPROVED(COLLECTIONS, Set.of(IN_APP, SMS), Set.of(TO_BORROWER)),
    SETTLEMENT_REJECTED(COLLECTIONS, Set.of(IN_APP), Set.of(TO_STAFF_SUBJECT)),

    // ---- SYSTEM ----
    APPLICATION_CANCELLED(SYSTEM, Set.of(IN_APP), Set.of(TO_BORROWER)),

    // ---- REFERRAL ----
    // A referral qualified (referred borrower's first loan disbursed) — alert the Disbursement Heads
    // who settle the two pending ₹-reward payouts.
    REFERRAL_PAYOUT_PENDING(SYSTEM, Set.of(IN_APP), Set.of(TO_DISBURSEMENT_HEADS)),
    // The Disbursement Head paid a reward — tell the beneficiary their reward is credited.
    REFERRAL_REWARD_CREDITED(SYSTEM, Set.of(IN_APP, SMS), Set.of(TO_BORROWER)),

    // ---- STAFF / IAM (the subject themselves) ----
    STAFF_INVITED(STAFF_IAM, Set.of(EMAIL), Set.of(TO_STAFF_SUBJECT)),
    STAFF_CREATED(STAFF_IAM, Set.of(EMAIL), Set.of(TO_STAFF_SUBJECT)),
    STAFF_ROLE_CHANGED(STAFF_IAM, Set.of(IN_APP, EMAIL), Set.of(TO_STAFF_SUBJECT)),
    STAFF_DISABLED(STAFF_IAM, Set.of(EMAIL), Set.of(TO_STAFF_SUBJECT));

    private final NotificationCategory category;
    private final Set<NotificationChannel> channels;
    private final Set<RecipientPolicy> audience;

    NotificationType(NotificationCategory category, Set<NotificationChannel> channels,
                     Set<RecipientPolicy> audience) {
        this.category = category;
        this.channels = channels;
        this.audience = audience;
    }

    public NotificationCategory category() {
        return category;
    }

    public Set<NotificationChannel> channels() {
        return channels;
    }

    public Set<RecipientPolicy> audience() {
        return audience;
    }
}
