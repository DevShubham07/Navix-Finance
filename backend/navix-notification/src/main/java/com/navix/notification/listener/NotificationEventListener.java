package com.navix.notification.listener;

import com.navix.common.notification.ContactInfo;
import com.navix.common.notification.RecipientType;
import com.navix.common.notification.event.ApplicationTransitionedEvent;
import com.navix.common.notification.event.CollectionCaseOpenedEvent;
import com.navix.common.notification.event.CollectionPaymentDecidedEvent;
import com.navix.common.notification.event.CollectionPaymentRaisedEvent;
import com.navix.common.notification.event.KycReminderEvent;
import com.navix.common.notification.event.PaymentReminderEvent;
import com.navix.common.notification.event.ReferralPayoutCreatedEvent;
import com.navix.common.notification.event.ReferralRewardCreditedEvent;
import com.navix.common.notification.event.RepaymentRecordedEvent;
import com.navix.common.notification.event.RepaymentRejectedEvent;
import com.navix.common.notification.event.RepaymentVerifiedEvent;
import com.navix.common.notification.event.SanctionLetterSignedEvent;
import com.navix.common.notification.event.SettlementApprovedEvent;
import com.navix.common.notification.event.SettlementProposedEvent;
import com.navix.common.notification.event.SettlementRejectedEvent;
import com.navix.common.notification.event.StaffAccountEvent;
import com.navix.common.storage.DocumentStoragePort;
import com.navix.notification.catalog.NotificationType;
import com.navix.notification.dispatch.NotificationContext;
import com.navix.notification.dispatch.NotificationDispatcher;
import com.navix.notification.email.EmailAttachment;
import com.navix.notification.template.NotificationFormat;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Bridges domain events to the {@link NotificationDispatcher}. Every handler runs <b>after the
 * business transaction commits</b> ({@code AFTER_COMMIT}) and <b>off the request thread</b>
 * ({@code @Async}) so notifications never block, fail, or roll back business work. The async thread
 * has no {@code ActorContext} and no transaction — all data comes from the (inline) event.
 */
@Component
public class NotificationEventListener {

    private static final Logger log = LoggerFactory.getLogger(NotificationEventListener.class);

    private final NotificationDispatcher dispatcher;
    /** Base URL the staff-invite activation link is built from (same property as the reset links). */
    private final String frontendBaseUrl;
    /** Fetches the signed sanction-letter PDF bytes for the SANCTION_LETTER_SIGNED email attachment. */
    private final DocumentStoragePort storage;

    public NotificationEventListener(NotificationDispatcher dispatcher,
            @Value("${navix.app.frontend-base-url:http://localhost:3000}") String frontendBaseUrl,
            DocumentStoragePort storage) {
        this.dispatcher = dispatcher;
        this.frontendBaseUrl = frontendBaseUrl.endsWith("/")
                ? frontendBaseUrl.substring(0, frontendBaseUrl.length() - 1) : frontendBaseUrl;
        this.storage = storage;
    }

    /** The application state-machine: one event per transition, mapped by {@code action} (§5). */
    @Async("notificationExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onApplicationTransitioned(ApplicationTransitionedEvent e) {
        NotificationType type = mapAction(e);
        if (type == null) {
            return; // CREATE / AUTO_ROUTE / VALIDATE_SUCCESS are deliberate no-ops
        }
        dispatcher.dispatch(type, NotificationContext.builder()
                .customerId(e.customerId())
                .applicationId(e.applicationId())
                .loanId(e.loanId())
                .assignedExecutiveId(e.assignedExecutiveId())
                .actorId(e.actorId())
                .actorRole(e.actorRole())
                .build());
    }

    /** Staff-triggered nudge to a borrower with outstanding verification steps (Phase 3.4). */
    @Async("notificationExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onKycReminder(KycReminderEvent e) {
        dispatcher.dispatch(NotificationType.KYC_REMINDER, NotificationContext.builder()
                .customerId(e.customerId())
                .applicationId(e.applicationId())
                .put("pendingSteps", e.pendingSteps())
                .build());
    }

    @Async("notificationExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onRepaymentRecorded(RepaymentRecordedEvent e) {
        dispatcher.dispatch(NotificationType.REPAYMENT_RECORDED, NotificationContext.builder()
                .customerId(e.customerId())
                .loanId(e.loanId())
                .put("amount", NotificationFormat.inr(e.amountPaise()))
                .build());
    }

    @Async("notificationExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onRepaymentVerified(RepaymentVerifiedEvent e) {
        if (e.closedTheLoan()) {
            return; // LOAN_CLOSED (the REPAID transition) covers the closing payment
        }
        dispatcher.dispatch(NotificationType.REPAYMENT_VERIFIED, NotificationContext.builder()
                .customerId(e.customerId())
                .loanId(e.loanId())
                .put("amount", NotificationFormat.inr(e.amountPaise()))
                .build());
    }

    @Async("notificationExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onRepaymentRejected(RepaymentRejectedEvent e) {
        dispatcher.dispatch(NotificationType.REPAYMENT_REJECTED, NotificationContext.builder()
                .customerId(e.customerId())
                .loanId(e.loanId())
                .put("amount", NotificationFormat.inr(e.amountPaise()))
                .put("reason", humanizeRejectionReason(e.reason(), e.note()))
                .build());
    }

    /** The fixed rejection-reason codes, worded for a borrower-facing IN_APP/EMAIL body. */
    private static String humanizeRejectionReason(String reason, String note) {
        String base = switch (reason == null ? "" : reason) {
            case "WRONG_REFERENCE" -> "the reference number didn't match our records";
            case "AMOUNT_MISMATCH" -> "the amount didn't match what was recorded";
            case "NOT_RECEIVED" -> "we haven't received this payment yet";
            case "UNREADABLE_PROOF" -> "the proof you shared wasn't readable";
            case "OTHER" -> "it couldn't be matched to a transfer";
            default -> "it couldn't be verified";
        };
        return note != null && !note.isBlank() ? base + " (" + note + ")" : base;
    }

    /**
     * The signed sanction letter, emailed as an attachment. If the PDF can't be fetched from storage
     * (transient S3 issue, missing key), the email still goes out — just without the attachment;
     * notifications must never block or fail business logic (CLAUDE.md §12).
     */
    @Async("notificationExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSanctionLetterSigned(SanctionLetterSignedEvent e) {
        List<EmailAttachment> attachments = List.of();
        if (e.s3ObjectKey() != null && !e.s3ObjectKey().isBlank()) {
            try {
                byte[] bytes = storage.fetch(e.s3ObjectKey());
                attachments = List.of(new EmailAttachment(
                        "sanction-letter-signed.pdf", "application/pdf", bytes));
            } catch (RuntimeException ex) {
                log.warn("Could not fetch signed sanction letter {} for application {} (sending email "
                        + "without the attachment): {}", e.s3ObjectKey(), e.applicationId(), ex.getMessage());
            }
        }
        dispatcher.dispatch(NotificationType.SANCTION_LETTER_SIGNED, NotificationContext.builder()
                .customerId(e.customerId())
                .applicationId(e.applicationId())
                .attachments(attachments)
                .build());
    }

    /**
     * Time-driven payment reminder (from the daily {@code PaymentReminderScheduler}). A plain
     * {@code @Async @EventListener} — there is no business transaction to wait on, so the
     * {@code AFTER_COMMIT} phase used elsewhere would never fire.
     */
    @Async("notificationExecutor")
    @EventListener
    public void onPaymentReminder(PaymentReminderEvent e) {
        NotificationType type = e.overdue() ? NotificationType.PAYMENT_OVERDUE : NotificationType.PAYMENT_DUE_SOON;
        dispatcher.dispatch(type, NotificationContext.builder()
                .customerId(e.customerId())
                .loanId(e.loanId())
                .put("amount", NotificationFormat.inr(e.outstandingPaise()))
                .put(e.overdue() ? "daysOverdue" : "daysToDue", e.days())
                .build());
    }

    @Async("notificationExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCollectionCaseOpened(CollectionCaseOpenedEvent e) {
        dispatcher.dispatch(NotificationType.COLLECTION_CASE_OPENED, NotificationContext.builder()
                .customerId(e.customerId())
                .loanId(e.loanId())
                .caseId(e.caseId())
                .build());
    }

    @Async("notificationExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSettlementProposed(SettlementProposedEvent e) {
        dispatcher.dispatch(NotificationType.SETTLEMENT_PROPOSED, NotificationContext.builder()
                .customerId(e.customerId())
                .loanId(e.loanId())
                .caseId(e.caseId())
                .put("settlementAmount", NotificationFormat.inr(e.amountPaise()))
                .build());
    }

    @Async("notificationExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSettlementApproved(SettlementApprovedEvent e) {
        dispatcher.dispatch(NotificationType.SETTLEMENT_APPROVED, NotificationContext.builder()
                .customerId(e.customerId())
                .loanId(e.loanId())
                .caseId(e.caseId())
                .put("settlementAmount", NotificationFormat.inr(e.amountPaise()))
                .build());
    }

    /** A proposed settlement was rejected — notify the proposer (a specific staff member). */
    @Async("notificationExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSettlementRejected(SettlementRejectedEvent e) {
        dispatcher.dispatch(NotificationType.SETTLEMENT_REJECTED, NotificationContext.builder()
                .staffSubjectId(e.proposedBy())
                .loanId(e.loanId())
                .caseId(e.caseId())
                .put("settlementAmount", NotificationFormat.inr(e.amountPaise()))
                .build());
    }

    /**
     * A collections payment was recorded (or the Head released a settlement one) — nudge whichever
     * desk it is now waiting on (V47; revamp.md decision 43).
     */
    @Async("notificationExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCollectionPaymentRaised(CollectionPaymentRaisedEvent e) {
        dispatcher.dispatch(
                e.awaitingHead()
                        ? NotificationType.COLLECTION_PAYMENT_TO_APPROVE
                        : NotificationType.COLLECTION_PAYMENT_TO_VALIDATE,
                NotificationContext.builder()
                        .customerId(e.customerId())
                        .loanId(e.loanId())
                        .caseId(e.caseId())
                        .put("paymentAmount", NotificationFormat.inr(e.amountPaise()))
                        .put("paymentKind", e.kind().toLowerCase().replace('_', ' '))
                        .build());
    }

    /**
     * The Accountant validated or rejected a collections payment — tell the officer who took it
     * (the staff subject) and the Collection Head who owns the case (decision 44).
     */
    @Async("notificationExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCollectionPaymentDecided(CollectionPaymentDecidedEvent e) {
        dispatcher.dispatch(
                e.validated()
                        ? NotificationType.COLLECTION_PAYMENT_VALIDATED
                        : NotificationType.COLLECTION_PAYMENT_REJECTED,
                NotificationContext.builder()
                        .staffSubjectId(e.raisedBy())
                        .customerId(e.customerId())
                        .loanId(e.loanId())
                        .caseId(e.caseId())
                        .put("paymentAmount", NotificationFormat.inr(e.amountPaise()))
                        .put("remarks", e.remarks() != null ? e.remarks() : "no reason given")
                        .build());
    }

    /** A referral qualified at disbursement — nudge the Disbursement Heads to settle the two payouts. */
    @Async("notificationExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onReferralPayoutCreated(ReferralPayoutCreatedEvent e) {
        dispatcher.dispatch(NotificationType.REFERRAL_PAYOUT_PENDING, NotificationContext.builder()
                .put("amount", NotificationFormat.inr(e.amountPaise()))
                .build());
    }

    /** A reward payout was paid — tell the beneficiary their referral reward is credited. */
    @Async("notificationExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onReferralRewardCredited(ReferralRewardCreditedEvent e) {
        dispatcher.dispatch(NotificationType.REFERRAL_REWARD_CREDITED, NotificationContext.builder()
                .customerId(e.beneficiaryCustomerId())
                .put("amount", NotificationFormat.inr(e.amountPaise()))
                .put("txnRef", e.txnRef())
                .build());
    }

    @Async("notificationExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onStaffAccount(StaffAccountEvent e) {
        NotificationType type = switch (e.changeType()) {
            case INVITED -> NotificationType.STAFF_INVITED;
            case CREATED -> NotificationType.STAFF_CREATED;
            case ROLE_CHANGED -> NotificationType.STAFF_ROLE_CHANGED;
            case DISABLED -> NotificationType.STAFF_DISABLED;
        };
        // The recipient is the subject themselves; an INVITED subject has no staff row yet, so we
        // supply the contact explicitly (id 0 sentinel — these are email-only / not in any inbox).
        Long subjectId = e.staffId() != null ? e.staffId() : 0L;
        ContactInfo subject = new ContactInfo(RecipientType.STAFF, subjectId, e.name(), e.email(), null, e.role());
        dispatcher.dispatch(type, NotificationContext.builder()
                .staffSubjectId(subjectId)
                .explicitStaffSubject(subject)
                .put("inviteLink", e.inviteToken() == null ? null
                        : frontendBaseUrl + "/staff/activate?token=" + e.inviteToken())
                .build());
    }

    /** Map the transition {@code action} (+ {@code toStatus} for the REBORROW fork) to a type, or null. */
    private static NotificationType mapAction(ApplicationTransitionedEvent e) {
        // The engine's action carries the rule that fired ("AUTO_REJECT_LOW_BUREAU_SCORE"), so it can
        // never match a case label — these fell through to `default` and notified nobody. It deliberately
        // maps to the SAME reasonless notification a manual credit rejection sends: revamp.md decision 31,
        // the borrower is never told which rule fired.
        if (e.action() != null && e.action().startsWith("AUTO_REJECT_")) {
            return NotificationType.CREDIT_REJECTED;
        }
        return switch (e.action()) {
            case "SUBMIT_KYC" -> NotificationType.KYC_SUBMITTED;
            case "KYC_APPROVE" -> NotificationType.KYC_APPROVED;
            case "KYC_REJECT" -> NotificationType.KYC_REJECTED;
            case "APPLY" -> NotificationType.LOAN_APPLIED;
            case "APPLY_FAST_TRACK" -> NotificationType.LOAN_APPLIED_FAST_TRACK;
            case "ASSIGN" -> NotificationType.CREDIT_ASSIGNED;
            case "EXEC_APPROVE" -> NotificationType.CREDIT_RECOMMENDED;
            // "Reject lead" reaches the borrower with NO reason given (revamp.md decision 31) — the
            // executive's remarks stay in the staff-only rejection register.
            case "EXEC_REJECT", "HEAD_REJECT", "REJECT_LEAD" -> NotificationType.CREDIT_REJECTED;
            case "HEAD_APPROVE" -> NotificationType.CREDIT_APPROVED;
            case "SANCTION" -> NotificationType.LOAN_SANCTIONED;
            case "ACCEPT_OFFER" -> NotificationType.LOAN_APPLIED_FAST_TRACK;
            // DISB_ACCEPT / VALIDATE_FAIL were the accountant hop, retired in V48 — no live action
            // emits them.
            // RETRY is the third route into DISBURSEMENT_PENDING (alongside APPLY_FAST_TRACK and
            // ACCEPT_OFFER, above) — now notifies too, since the audience includes ADMIN, who didn't
            // click anything (product decision; it used to be silent on the grounds that it only
            // returns the file to the Head who just clicked retry).
            case "RETRY" -> NotificationType.LOAN_APPLIED_FAST_TRACK;
            case "DISB_REJECT" -> NotificationType.DISBURSEMENT_REJECTED;
            case "ACTIVATE" -> NotificationType.LOAN_DISBURSED;
            case "REPAID" -> NotificationType.LOAN_CLOSED;
            case "CANCEL" -> NotificationType.APPLICATION_CANCELLED;
            case "REVIEW_APPROVE" -> NotificationType.REBORROW_REVIEW_APPROVED;
            case "REVIEW_REJECT" -> NotificationType.REBORROW_REVIEW_REJECTED;
            // Since V45 the delinquent fork auto-rejects rather than queuing a manual review.
            case "REBORROW" -> "PRE_APPROVED".equals(e.toStatus())
                    ? NotificationType.REBORROW_PREAPPROVED
                    : NotificationType.CREDIT_REJECTED;
            // MARK_PENDING is deliberately silent: a staff-only tag the borrower never sees (decision 30).
            default -> null; // CREATE, AUTO_ROUTE, VALIDATE_SUCCESS, MARK_PENDING
        };
    }
}
