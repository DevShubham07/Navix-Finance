package com.navix.notification.listener;

import com.navix.common.notification.ContactInfo;
import com.navix.common.notification.RecipientType;
import com.navix.common.notification.event.ApplicationTransitionedEvent;
import com.navix.common.notification.event.CollectionCaseOpenedEvent;
import com.navix.common.notification.event.KycReminderEvent;
import com.navix.common.notification.event.PaymentReminderEvent;
import com.navix.common.notification.event.ReferralPayoutCreatedEvent;
import com.navix.common.notification.event.ReferralRewardCreditedEvent;
import com.navix.common.notification.event.RepaymentRecordedEvent;
import com.navix.common.notification.event.RepaymentRejectedEvent;
import com.navix.common.notification.event.RepaymentVerifiedEvent;
import com.navix.common.notification.event.SettlementApprovedEvent;
import com.navix.common.notification.event.SettlementProposedEvent;
import com.navix.common.notification.event.SettlementRejectedEvent;
import com.navix.common.notification.event.StaffAccountEvent;
import com.navix.notification.catalog.NotificationType;
import com.navix.notification.dispatch.NotificationContext;
import com.navix.notification.dispatch.NotificationDispatcher;
import com.navix.notification.template.NotificationFormat;
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

    private final NotificationDispatcher dispatcher;
    /** Base URL the staff-invite activation link is built from (same property as the reset links). */
    private final String frontendBaseUrl;

    public NotificationEventListener(NotificationDispatcher dispatcher,
            @Value("${navix.app.frontend-base-url:http://localhost:3000}") String frontendBaseUrl) {
        this.dispatcher = dispatcher;
        this.frontendBaseUrl = frontendBaseUrl.endsWith("/")
                ? frontendBaseUrl.substring(0, frontendBaseUrl.length() - 1) : frontendBaseUrl;
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
        return switch (e.action()) {
            case "SUBMIT_KYC" -> NotificationType.KYC_SUBMITTED;
            case "KYC_APPROVE" -> NotificationType.KYC_APPROVED;
            case "KYC_REJECT" -> NotificationType.KYC_REJECTED;
            case "APPLY" -> NotificationType.LOAN_APPLIED;
            case "APPLY_FAST_TRACK" -> NotificationType.LOAN_APPLIED_FAST_TRACK;
            case "ASSIGN" -> NotificationType.CREDIT_ASSIGNED;
            case "EXEC_APPROVE" -> NotificationType.CREDIT_RECOMMENDED;
            case "EXEC_REJECT", "HEAD_REJECT", "KYC_CREDIT_REJECT" -> NotificationType.CREDIT_REJECTED;
            case "HEAD_APPROVE", "KYC_CREDIT_APPROVE" -> NotificationType.CREDIT_APPROVED;
            case "DISB_ACCEPT", "RETRY" -> NotificationType.DISBURSEMENT_PENDING_ACCOUNTANT;
            case "DISB_REJECT" -> NotificationType.DISBURSEMENT_REJECTED;
            case "VALIDATE_FAIL" -> NotificationType.DISBURSEMENT_FAILED;
            case "ACTIVATE" -> NotificationType.LOAN_DISBURSED;
            case "REPAID" -> NotificationType.LOAN_CLOSED;
            case "CANCEL" -> NotificationType.APPLICATION_CANCELLED;
            case "REVIEW_APPROVE" -> NotificationType.REBORROW_REVIEW_APPROVED;
            case "REVIEW_REJECT" -> NotificationType.REBORROW_REVIEW_REJECTED;
            case "REBORROW" -> "PRE_APPROVED".equals(e.toStatus())
                    ? NotificationType.REBORROW_PREAPPROVED
                    : NotificationType.REBORROW_REVIEW_PENDING;
            default -> null; // CREATE, AUTO_ROUTE, VALIDATE_SUCCESS
        };
    }
}
