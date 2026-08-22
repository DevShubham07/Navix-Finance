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
import static com.navix.notification.catalog.RecipientPolicy.TO_ADMINS;
import static com.navix.notification.catalog.RecipientPolicy.TO_ASSIGNED_EXECUTIVE;
import static com.navix.notification.catalog.RecipientPolicy.TO_BORROWER;
import static com.navix.notification.catalog.RecipientPolicy.TO_COLLECTION_EXECUTIVES;
import static com.navix.notification.catalog.RecipientPolicy.TO_COLLECTION_HEADS;
import static com.navix.notification.catalog.RecipientPolicy.TO_CREDIT_HEADS;
import static com.navix.notification.catalog.RecipientPolicy.TO_DISBURSEMENT_HEADS;
import static com.navix.notification.catalog.RecipientPolicy.TO_CREDIT_TEAM;
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
    // ADMIN + Credit Heads only (product decision) — a submitted KYC is a queue-owner's signal, not
    // something every Credit Executive needs in their inbox. EMAIL added so it lands outside the app.
    KYC_SUBMITTED(KYC, Set.of(IN_APP, EMAIL), Set.of(TO_CREDIT_HEADS, TO_ADMINS)),
    KYC_APPROVED(KYC, Set.of(IN_APP, SMS, EMAIL), Set.of(TO_BORROWER)),
    KYC_REJECTED(KYC, Set.of(IN_APP, SMS, EMAIL), Set.of(TO_BORROWER)),
    KYC_REMINDER(KYC, Set.of(IN_APP, SMS, EMAIL), Set.of(TO_BORROWER)),
    REBORROW_PREAPPROVED(KYC, Set.of(IN_APP, SMS), Set.of(TO_BORROWER)),
    /** @deprecated The manual reborrow review was retired in V45 (decision 29). Historical rows only. */
    @Deprecated
    REBORROW_REVIEW_PENDING(KYC, Set.of(IN_APP), Set.of(TO_CREDIT_TEAM, TO_BORROWER)),
    REBORROW_REVIEW_APPROVED(KYC, Set.of(IN_APP, SMS), Set.of(TO_BORROWER)),
    REBORROW_REVIEW_REJECTED(KYC, Set.of(IN_APP, SMS), Set.of(TO_BORROWER)),

    // ---- CREDIT ----
    LOAN_APPLIED(CREDIT, Set.of(IN_APP), Set.of(TO_CREDIT_HEADS)),
    CREDIT_ASSIGNED(CREDIT, Set.of(IN_APP), Set.of(TO_ASSIGNED_EXECUTIVE)),
    CREDIT_RECOMMENDED(CREDIT, Set.of(IN_APP), Set.of(TO_CREDIT_HEADS)),
    // HEAD_APPROVE is off the live path since V45 (the credit maker-checker was retired) — this type
    // now only fires on a replayed historical event. TO_ADMINS added for audit-replay parity with the
    // live DISBURSEMENT_PENDING notification below (LOAN_APPLIED_FAST_TRACK).
    CREDIT_APPROVED(CREDIT, Set.of(IN_APP, EMAIL), Set.of(TO_BORROWER, TO_DISBURSEMENT_HEADS, TO_ADMINS)),
    CREDIT_REJECTED(CREDIT, Set.of(IN_APP, SMS, EMAIL), Set.of(TO_BORROWER)),
    /** The Credit Executive's final sanction (V45) — the borrower's offer is ready to accept. */
    LOAN_SANCTIONED(CREDIT, Set.of(IN_APP, SMS, EMAIL), Set.of(TO_BORROWER)),
    // An ADMIN corrected the sanctioned amount post-approval. Deliberately no SMS — a new DLT
    // template would be needed and every DHANBOOST_*_V1 registration is still pending operator
    // approval, so an SMS leg would silently fail (same rationale as SANCTION_LETTER_SIGNED).
    SANCTIONED_AMOUNT_REVISED(CREDIT, Set.of(IN_APP, EMAIL), Set.of(TO_BORROWER)),

    // ---- DISBURSEMENT ----
    // Every route into DISBURSEMENT_PENDING (fast-track reborrow, offer acceptance, and a retry after
    // a failed transfer) — fires this one type for both the borrower and the disbursement desk +
    // admin, so the copy must read generically enough for all three audiences and all three triggers.
    LOAN_APPLIED_FAST_TRACK(DISBURSEMENT, Set.of(IN_APP, EMAIL),
            Set.of(TO_DISBURSEMENT_HEADS, TO_ADMINS, TO_BORROWER)),
    /** @deprecated The accountant disbursement hop was retired in V48; historical rows only. */
    @Deprecated
    DISBURSEMENT_PENDING_ACCOUNTANT(DISBURSEMENT, Set.of(IN_APP), Set.of(TO_ACCOUNTANTS)),
    DISBURSEMENT_FAILED(DISBURSEMENT, Set.of(IN_APP), Set.of(TO_DISBURSEMENT_HEADS)),
    DISBURSEMENT_REJECTED(DISBURSEMENT, Set.of(IN_APP, EMAIL), Set.of(TO_BORROWER)),
    LOAN_DISBURSED(DISBURSEMENT, Set.of(IN_APP, SMS, EMAIL), Set.of(TO_BORROWER)),
    // The signed sanction letter, emailed as an attachment (IN_APP + EMAIL only — no SMS, so no new
    // DLT registration is needed for this one).
    SANCTION_LETTER_SIGNED(DISBURSEMENT, Set.of(IN_APP, EMAIL), Set.of(TO_BORROWER)),

    // ---- REPAYMENT ----
    REPAYMENT_RECORDED(REPAYMENT, Set.of(IN_APP), Set.of(TO_ACCOUNTANTS, TO_BORROWER)),
    REPAYMENT_VERIFIED(REPAYMENT, Set.of(IN_APP, SMS), Set.of(TO_BORROWER)),
    // EMAIL added so the rejection reason (SMS body is DLT-locked, can't carry it) reaches the
    // borrower somewhere other than the in-app inbox.
    REPAYMENT_REJECTED(REPAYMENT, Set.of(IN_APP, SMS, EMAIL), Set.of(TO_BORROWER)),
    // Time-driven reminders from the daily PaymentReminderScheduler.
    PAYMENT_DUE_SOON(REPAYMENT, Set.of(IN_APP, SMS, EMAIL), Set.of(TO_BORROWER)),
    PAYMENT_OVERDUE(REPAYMENT, Set.of(IN_APP, SMS, EMAIL), Set.of(TO_BORROWER)),
    LOAN_CLOSED(REPAYMENT, Set.of(IN_APP, SMS, EMAIL), Set.of(TO_BORROWER)),

    // ---- COLLECTIONS ----
    COLLECTION_CASE_OPENED(COLLECTIONS, Set.of(IN_APP), Set.of(TO_COLLECTION_HEADS, TO_COLLECTION_EXECUTIVES)),
    SETTLEMENT_PROPOSED(COLLECTIONS, Set.of(IN_APP), Set.of(TO_COLLECTION_HEADS)),
    SETTLEMENT_APPROVED(COLLECTIONS, Set.of(IN_APP, SMS), Set.of(TO_BORROWER)),
    SETTLEMENT_REJECTED(COLLECTIONS, Set.of(IN_APP), Set.of(TO_STAFF_SUBJECT)),
    // A collections payment awaits its checker (V47): the Collection Head for a settlement, the
    // Accountant for everything else. Two types, because they are two different desks.
    COLLECTION_PAYMENT_TO_APPROVE(COLLECTIONS, Set.of(IN_APP), Set.of(TO_COLLECTION_HEADS)),
    COLLECTION_PAYMENT_TO_VALIDATE(COLLECTIONS, Set.of(IN_APP), Set.of(TO_ACCOUNTANTS)),
    // The Accountant decided — back to the officer who took the payment AND the Head who owns the
    // case (decision 44), so a rejection can't sit unseen in one person's inbox.
    COLLECTION_PAYMENT_VALIDATED(COLLECTIONS, Set.of(IN_APP), Set.of(TO_STAFF_SUBJECT, TO_COLLECTION_HEADS)),
    COLLECTION_PAYMENT_REJECTED(COLLECTIONS, Set.of(IN_APP), Set.of(TO_STAFF_SUBJECT, TO_COLLECTION_HEADS)),

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
