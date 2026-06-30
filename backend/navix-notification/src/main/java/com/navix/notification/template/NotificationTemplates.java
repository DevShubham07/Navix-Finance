package com.navix.notification.template;

import static com.navix.common.notification.NotificationChannel.EMAIL;
import static com.navix.common.notification.NotificationChannel.IN_APP;
import static com.navix.common.notification.NotificationChannel.SMS;

import com.navix.common.notification.NotificationChannel;
import com.navix.notification.catalog.NotificationType;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Code-defined template registry keyed by {@code (NotificationType, NotificationChannel)} — versioned
 * and type-safe (SMS bodies are DLT-locked anyway, so DB-editable templates aren't worth it for v1).
 * Placeholders use {@code {key}} and are resolved by {@link TemplateRenderer}; an unknown key renders
 * as {@code —}. Available model keys: {@code name}, {@code role}, {@code applicationId}, {@code loanId},
 * {@code amount}, {@code netDisbursed}, {@code totalRepayable}, {@code outstanding}, {@code dueDate},
 * {@code settlementAmount}, {@code inviteLink}.
 */
@Component
public class NotificationTemplates {

    private final Map<NotificationType, Map<NotificationChannel, TemplateDef>> registry =
            new EnumMap<>(NotificationType.class);

    public NotificationTemplates() {
        // ---------------- KYC ----------------
        inApp(NotificationType.KYC_SUBMITTED, "New KYC to review",
                "A borrower submitted KYC for application #{applicationId}. Review it in your queue.");

        inApp(NotificationType.KYC_APPROVED, "KYC approved",
                "Your KYC is verified. Log in to choose your loan amount.");
        sms(NotificationType.KYC_APPROVED,
                "NAVIX: Your KYC is verified. Log in to choose your loan amount.");
        email(NotificationType.KYC_APPROVED, "Your NAVIX KYC is approved",
                "Hi {name},\n\nGood news — your KYC is verified. You can now log in and choose your "
                        + "loan amount.\n\n— NAVIX Finance");

        inApp(NotificationType.KYC_REJECTED, "KYC could not be verified",
                "We couldn't verify your KYC. Please review your details and resubmit.");
        sms(NotificationType.KYC_REJECTED,
                "NAVIX: We couldn't verify your KYC. Please log in to review and resubmit.");
        email(NotificationType.KYC_REJECTED, "About your NAVIX KYC",
                "Hi {name},\n\nWe weren't able to verify your KYC this time. Please log in to review "
                        + "your details and resubmit.\n\n— NAVIX Finance");

        inApp(NotificationType.KYC_REMINDER, "Finish your verification",
                "You still have pending verification steps: {pendingSteps}. Log in to complete them.");
        sms(NotificationType.KYC_REMINDER,
                "NAVIX: Please complete your pending verification: {pendingSteps}. Log in to finish.");
        email(NotificationType.KYC_REMINDER, "Complete your NAVIX verification",
                "Hi {name},\n\nA few verification steps are still pending on your application: "
                        + "{pendingSteps}.\n\nPlease log in to complete them so we can proceed.\n\n— NAVIX Finance");

        inApp(NotificationType.REBORROW_PREAPPROVED, "You're pre-approved",
                "Welcome back! You're pre-approved — choose your amount to continue.");
        sms(NotificationType.REBORROW_PREAPPROVED,
                "NAVIX: Welcome back! You're pre-approved. Log in to choose your amount.");

        inApp(NotificationType.REBORROW_REVIEW_PENDING, "Reborrow under review",
                "Reborrow application #{applicationId} needs review.");

        inApp(NotificationType.REBORROW_REVIEW_APPROVED, "Reborrow approved",
                "Your repeat application is approved — choose your amount.");
        sms(NotificationType.REBORROW_REVIEW_APPROVED,
                "NAVIX: Your repeat application is approved. Log in to choose your amount.");

        inApp(NotificationType.REBORROW_REVIEW_REJECTED, "Reborrow declined",
                "We're unable to approve your repeat application at this time.");
        sms(NotificationType.REBORROW_REVIEW_REJECTED,
                "NAVIX: We're unable to approve your repeat application at this time.");

        // ---------------- CREDIT ----------------
        inApp(NotificationType.LOAN_APPLIED, "New application to review",
                "A borrower applied on application #{applicationId} — it's in your credit queue.");

        inApp(NotificationType.CREDIT_ASSIGNED, "Application assigned to you",
                "Application #{applicationId} has been assigned to you to recommend.");

        inApp(NotificationType.CREDIT_RECOMMENDED, "Awaiting your approval",
                "Application #{applicationId} has been recommended — it needs your final approval.");

        inApp(NotificationType.CREDIT_APPROVED, "Credit approved",
                "Application #{applicationId} is approved by credit and moving to disbursement.");
        email(NotificationType.CREDIT_APPROVED, "Your NAVIX loan is approved",
                "Hi {name},\n\nLoan application #{applicationId} has been approved by the credit team "
                        + "and is ready for the next step (disbursement).\n\n— NAVIX Finance");

        inApp(NotificationType.CREDIT_REJECTED, "Application declined",
                "Application #{applicationId} was declined at credit review.");
        sms(NotificationType.CREDIT_REJECTED,
                "NAVIX: We're unable to approve your loan application at this time.");
        email(NotificationType.CREDIT_REJECTED, "About your NAVIX loan application",
                "Hi {name},\n\nAfter review, we're unable to approve your loan application "
                        + "#{applicationId} at this time.\n\n— NAVIX Finance");

        // ---------------- DISBURSEMENT ----------------
        inApp(NotificationType.LOAN_APPLIED_FAST_TRACK, "Fast-track disbursal",
                "Pre-approved application #{applicationId} is ready for disbursement (fast-track).");

        inApp(NotificationType.DISBURSEMENT_PENDING_ACCOUNTANT, "Transfer to validate",
                "Application #{applicationId} is awaiting your transfer validation.");

        inApp(NotificationType.DISBURSEMENT_FAILED, "Disbursal failed",
                "The transfer for application #{applicationId} failed. Please retry.");

        inApp(NotificationType.DISBURSEMENT_REJECTED, "Disbursal declined",
                "Application #{applicationId} was declined at disbursement.");
        email(NotificationType.DISBURSEMENT_REJECTED, "About your NAVIX loan",
                "Hi {name},\n\nUnfortunately your loan application #{applicationId} was declined at the "
                        + "disbursement stage.\n\n— NAVIX Finance");

        inApp(NotificationType.LOAN_DISBURSED, "Money on the way",
                "Your loan is disbursed: {netDisbursed} credited. Repay {totalRepayable} by {dueDate}.");
        sms(NotificationType.LOAN_DISBURSED,
                "NAVIX: {netDisbursed} has been disbursed to your account. Repay {totalRepayable} by {dueDate}.");
        email(NotificationType.LOAN_DISBURSED, "Your NAVIX loan has been disbursed",
                "Hi {name},\n\n{netDisbursed} has been disbursed to your bank account. Your total "
                        + "repayable is {totalRepayable}, due on {dueDate}. You can pay early to save on "
                        + "interest.\n\n— NAVIX Finance");

        // ---------------- REPAYMENT ----------------
        inApp(NotificationType.REPAYMENT_RECORDED, "Payment recorded",
                "Repayment of {amount} for loan #{loanId} is pending verification.");

        inApp(NotificationType.REPAYMENT_VERIFIED, "Payment confirmed",
                "Your payment of {amount} is confirmed. Outstanding: {outstanding}.");
        sms(NotificationType.REPAYMENT_VERIFIED,
                "NAVIX: Your payment of {amount} is confirmed. Outstanding: {outstanding}.");

        inApp(NotificationType.LOAN_CLOSED, "Loan closed",
                "Your loan is fully repaid and closed. Thank you!");
        sms(NotificationType.LOAN_CLOSED,
                "NAVIX: Your loan is fully repaid and closed. Thank you!");
        email(NotificationType.LOAN_CLOSED, "Your NAVIX loan is closed",
                "Hi {name},\n\nYour loan is fully repaid and now closed. Thank you for choosing NAVIX.\n\n"
                        + "— NAVIX Finance");

        // ---------------- COLLECTIONS ----------------
        inApp(NotificationType.COLLECTION_CASE_OPENED, "New collections case",
                "A collections case was opened on loan #{loanId}.");

        inApp(NotificationType.SETTLEMENT_PROPOSED, "Settlement to approve",
                "A settlement of {settlementAmount} was proposed on loan #{loanId} — your approval is needed.");

        inApp(NotificationType.SETTLEMENT_APPROVED, "Settlement approved",
                "A full & final settlement of {settlementAmount} is approved on your loan. Pay it to close.");
        sms(NotificationType.SETTLEMENT_APPROVED,
                "NAVIX: A full & final settlement of {settlementAmount} is approved on your loan. Pay it to close.");

        // ---------------- SYSTEM ----------------
        inApp(NotificationType.APPLICATION_CANCELLED, "Application cancelled",
                "Your loan application #{applicationId} has been cancelled.");

        // ---------------- STAFF / IAM ----------------
        email(NotificationType.STAFF_INVITED, "You're invited to NAVIX",
                "Hi {name},\n\nYou've been invited to NAVIX as {role}. Accept your invite and set your "
                        + "password here:\n{inviteLink}\n\n— NAVIX Finance");
        email(NotificationType.STAFF_CREATED, "Your NAVIX account is ready",
                "Hi {name},\n\nYour NAVIX staff account has been created with the role {role}. You can "
                        + "now sign in.\n\n— NAVIX Finance");
        inApp(NotificationType.STAFF_ROLE_CHANGED, "Your role changed",
                "Your role is now {role}.");
        email(NotificationType.STAFF_ROLE_CHANGED, "Your NAVIX role has changed",
                "Hi {name},\n\nYour NAVIX role has been updated to {role}.\n\n— NAVIX Finance");
        email(NotificationType.STAFF_DISABLED, "Your NAVIX access has been disabled",
                "Hi {name},\n\nYour NAVIX staff access has been disabled. If you believe this is in "
                        + "error, contact your administrator.\n\n— NAVIX Finance");
    }

    /** The template for a type+channel, or {@code null} if none is defined (channel skipped). */
    public TemplateDef get(NotificationType type, NotificationChannel channel) {
        Map<NotificationChannel, TemplateDef> byChannel = registry.get(type);
        return byChannel == null ? null : byChannel.get(channel);
    }

    private void put(NotificationType t, NotificationChannel c, String subject, String body) {
        registry.computeIfAbsent(t, k -> new EnumMap<>(NotificationChannel.class))
                .put(c, new TemplateDef(subject, body));
    }

    private void inApp(NotificationType t, String title, String body) {
        put(t, IN_APP, title, body);
    }

    private void sms(NotificationType t, String body) {
        put(t, SMS, null, body);
    }

    private void email(NotificationType t, String subject, String body) {
        put(t, EMAIL, subject, body);
    }
}
