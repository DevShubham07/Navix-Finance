package com.navix.notification.listener;

import com.navix.common.notification.ContactInfo;
import com.navix.common.notification.RecipientType;
import com.navix.common.notification.event.ApplicationTransitionedEvent;
import com.navix.common.notification.event.CollectionCaseOpenedEvent;
import com.navix.common.notification.event.RepaymentRecordedEvent;
import com.navix.common.notification.event.RepaymentVerifiedEvent;
import com.navix.common.notification.event.SettlementApprovedEvent;
import com.navix.common.notification.event.SettlementProposedEvent;
import com.navix.common.notification.event.StaffAccountEvent;
import com.navix.notification.catalog.NotificationType;
import com.navix.notification.dispatch.NotificationContext;
import com.navix.notification.dispatch.NotificationDispatcher;
import com.navix.notification.template.NotificationFormat;
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

    public NotificationEventListener(NotificationDispatcher dispatcher) {
        this.dispatcher = dispatcher;
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
                .applicantId(e.applicantId())
                .applicationId(e.applicationId())
                .loanId(e.loanId())
                .assignedExecutiveId(e.assignedExecutiveId())
                .actorId(e.actorId())
                .actorRole(e.actorRole())
                .build());
    }

    @Async("notificationExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onRepaymentRecorded(RepaymentRecordedEvent e) {
        dispatcher.dispatch(NotificationType.REPAYMENT_RECORDED, NotificationContext.builder()
                .applicantId(e.applicantId())
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
                .applicantId(e.applicantId())
                .loanId(e.loanId())
                .put("amount", NotificationFormat.inr(e.amountPaise()))
                .build());
    }

    @Async("notificationExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCollectionCaseOpened(CollectionCaseOpenedEvent e) {
        dispatcher.dispatch(NotificationType.COLLECTION_CASE_OPENED, NotificationContext.builder()
                .applicantId(e.applicantId())
                .loanId(e.loanId())
                .caseId(e.caseId())
                .build());
    }

    @Async("notificationExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSettlementProposed(SettlementProposedEvent e) {
        dispatcher.dispatch(NotificationType.SETTLEMENT_PROPOSED, NotificationContext.builder()
                .applicantId(e.applicantId())
                .loanId(e.loanId())
                .caseId(e.caseId())
                .put("settlementAmount", NotificationFormat.inr(e.amountPaise()))
                .build());
    }

    @Async("notificationExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSettlementApproved(SettlementApprovedEvent e) {
        dispatcher.dispatch(NotificationType.SETTLEMENT_APPROVED, NotificationContext.builder()
                .applicantId(e.applicantId())
                .loanId(e.loanId())
                .caseId(e.caseId())
                .put("settlementAmount", NotificationFormat.inr(e.amountPaise()))
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
                .put("inviteLink", e.inviteToken() == null ? null : "/staff/accept-invite?token=" + e.inviteToken())
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
            case "EXEC_REJECT", "HEAD_REJECT" -> NotificationType.CREDIT_REJECTED;
            case "HEAD_APPROVE" -> NotificationType.CREDIT_APPROVED;
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
