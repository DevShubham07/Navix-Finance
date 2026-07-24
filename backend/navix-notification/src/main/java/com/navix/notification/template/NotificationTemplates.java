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

        inApp(NotificationType.KYC_APPROVED, "KYC approved — instant loan ready",
                "Your KYC is verified. You're eligible for an instant loan up to ₹10,00,000 — log in to "
                        + "choose your amount.");
        sms(NotificationType.KYC_APPROVED,
                "Your KYC is verified with DhanBoost. Log in at https://www.dhanboost.com/login "
                        + "to choose your loan amount. - DhanBoost");
        email(NotificationType.KYC_APPROVED, "Your DhanBoost KYC is approved — instant loan up to ₹10,00,000",
                "Hi {name},\n\nGood news — your KYC is verified. You're now eligible for an instant loan "
                        + "of up to ₹10,00,000. Log in to choose your amount and get funded.\n\n— DhanBoost");

        inApp(NotificationType.KYC_REJECTED, "KYC could not be verified",
                "We couldn't verify your KYC. Please review your details and resubmit.");
        sms(NotificationType.KYC_REJECTED,
                "We could not verify your KYC with DhanBoost. Log in at "
                        + "https://www.dhanboost.com/login to review and resubmit. - DhanBoost");
        email(NotificationType.KYC_REJECTED, "About your DhanBoost KYC",
                "Hi {name},\n\nWe weren't able to verify your KYC this time. Please log in to review "
                        + "your details and resubmit.\n\n— DhanBoost");

        inApp(NotificationType.KYC_REMINDER, "Finish your verification",
                "You still have pending verification steps: {pendingSteps}. Log in to complete them.");
        sms(NotificationType.KYC_REMINDER,
                "Your verification with DhanBoost is incomplete. Log in at "
                        + "https://www.dhanboost.com/login to complete your pending steps. - DhanBoost");
        email(NotificationType.KYC_REMINDER, "Complete your DhanBoost verification",
                "Hi {name},\n\nA few verification steps are still pending on your application: "
                        + "{pendingSteps}.\n\nPlease log in to complete them so we can proceed.\n\n— DhanBoost");

        inApp(NotificationType.REBORROW_PREAPPROVED, "You're pre-approved",
                "Welcome back! You're pre-approved — choose your amount to continue.");
        sms(NotificationType.REBORROW_PREAPPROVED,
                "Welcome back to DhanBoost. You can apply for another loan now. Log in at "
                        + "https://www.dhanboost.com/login to choose your amount. - DhanBoost");

        inApp(NotificationType.REBORROW_REVIEW_PENDING, "Reborrow under review",
                "Reborrow application #{applicationId} needs review.");

        inApp(NotificationType.REBORROW_REVIEW_APPROVED, "Reborrow approved",
                "Your repeat application is approved — choose your amount.");
        sms(NotificationType.REBORROW_REVIEW_APPROVED,
                "Your loan application with DhanBoost is approved. Log in at "
                        + "https://www.dhanboost.com/login to choose your amount. - DhanBoost");

        inApp(NotificationType.REBORROW_REVIEW_REJECTED, "Reborrow declined",
                "We're unable to approve your repeat application at this time.");
        sms(NotificationType.REBORROW_REVIEW_REJECTED,
                "DhanBoost is unable to approve your loan application at this time. Visit "
                        + "https://www.dhanboost.com/login for details. - DhanBoost");

        // ---------------- CREDIT ----------------
        inApp(NotificationType.LOAN_APPLIED, "New application to review",
                "A borrower applied on application #{applicationId} — it's in your credit queue.");

        inApp(NotificationType.CREDIT_ASSIGNED, "Application assigned to you",
                "Application #{applicationId} has been assigned to you to recommend.");

        inApp(NotificationType.CREDIT_RECOMMENDED, "Awaiting your approval",
                "Application #{applicationId} has been recommended — it needs your final approval.");

        inApp(NotificationType.CREDIT_APPROVED, "Credit approved",
                "Application #{applicationId} is approved by credit and moving to disbursement.");
        email(NotificationType.CREDIT_APPROVED, "Your DhanBoost loan is approved",
                "Hi {name},\n\nLoan application #{applicationId} has been approved by the credit team "
                        + "and is ready for the next step (disbursement).\n\n— DhanBoost");

        inApp(NotificationType.CREDIT_REJECTED, "Application declined",
                "Application #{applicationId} was declined at credit review.");
        sms(NotificationType.CREDIT_REJECTED,
                "DhanBoost is unable to approve your loan application at this time. Visit "
                        + "https://www.dhanboost.com/login for details. - DhanBoost");
        email(NotificationType.CREDIT_REJECTED, "About your DhanBoost loan application",
                "Hi {name},\n\nAfter review, we're unable to approve your loan application "
                        + "#{applicationId} at this time.\n\n— DhanBoost");

        // ---------------- DISBURSEMENT ----------------
        inApp(NotificationType.LOAN_APPLIED_FAST_TRACK, "Fast-track disbursal",
                "Pre-approved application #{applicationId} is ready for disbursement (fast-track).");

        inApp(NotificationType.DISBURSEMENT_PENDING_ACCOUNTANT, "Transfer to validate",
                "Application #{applicationId} is awaiting your transfer validation.");

        inApp(NotificationType.DISBURSEMENT_FAILED, "Disbursal failed",
                "The transfer for application #{applicationId} failed. Please retry.");

        inApp(NotificationType.DISBURSEMENT_REJECTED, "Disbursal declined",
                "Application #{applicationId} was declined at disbursement.");
        email(NotificationType.DISBURSEMENT_REJECTED, "About your DhanBoost loan",
                "Hi {name},\n\nUnfortunately your loan application #{applicationId} was declined at the "
                        + "disbursement stage.\n\n— DhanBoost");

        inApp(NotificationType.LOAN_DISBURSED, "Money on the way",
                "Your loan is disbursed: {netDisbursed} credited. Repay {totalRepayable} by {dueDate}.");
        sms(NotificationType.LOAN_DISBURSED,
                "DhanBoost has disbursed {netDisbursed} to your bank account. Repay {totalRepayable} "
                        + "by {dueDate} at https://www.dhanboost.com/login. - DhanBoost");
        email(NotificationType.LOAN_DISBURSED, "Your DhanBoost loan has been disbursed",
                "Hi {name},\n\n{netDisbursed} has been disbursed to your bank account. Your total "
                        + "repayable is {totalRepayable}, due on {dueDate}. You can pay early to save on "
                        + "interest.\n\n— DhanBoost");

        // ---------------- REPAYMENT ----------------
        inApp(NotificationType.REPAYMENT_RECORDED, "Payment recorded",
                "Repayment of {amount} for loan #{loanId} is pending verification.");

        inApp(NotificationType.REPAYMENT_VERIFIED, "Payment confirmed",
                "Your payment of {amount} is confirmed. Outstanding: {outstanding}.");
        sms(NotificationType.REPAYMENT_VERIFIED,
                "Your payment of {amount} to DhanBoost is confirmed. Outstanding balance is "
                        + "{outstanding}. View details at https://www.dhanboost.com/login. - DhanBoost");

        inApp(NotificationType.REPAYMENT_REJECTED, "Payment not verified",
                "Your payment of {amount} for loan #{loanId} couldn't be verified. Please check the "
                        + "reference and record it again.");
        sms(NotificationType.REPAYMENT_REJECTED,
                "Your payment of {amount} could not be verified by DhanBoost. Log in at "
                        + "https://www.dhanboost.com/login to check the reference and record it again. - DhanBoost");

        inApp(NotificationType.PAYMENT_DUE_SOON, "Payment due soon",
                "Your payment of {amount} is due in {daysToDue} day(s) (by {dueDate}). Pay on your salary "
                        + "day or the day after — no penalty. Prepay anytime to save on interest.");
        sms(NotificationType.PAYMENT_DUE_SOON,
                "Your DhanBoost payment of {amount} is due in {daysToDue} day(s) by {dueDate}. Pay at "
                        + "https://www.dhanboost.com/login on or after your salary day with no penalty. - DhanBoost");
        email(NotificationType.PAYMENT_DUE_SOON, "Your DhanBoost payment is due soon",
                "Hi {name},\n\nYour payment of {amount} is due in {daysToDue} day(s), by {dueDate}. You can "
                        + "pay on your salary day or the day after with no penalty — or prepay anytime to save "
                        + "on interest (you only pay interest up to the day you repay).\n\n— DhanBoost");

        inApp(NotificationType.PAYMENT_OVERDUE, "Payment overdue",
                "Your payment of {amount} is overdue by {daysOverdue} day(s). Please pay now — a late "
                        + "penalty of 2%/day is accruing and your credit score may be impacted.");
        sms(NotificationType.PAYMENT_OVERDUE,
                "Your DhanBoost payment of {amount} is overdue by {daysOverdue} day(s). Pay now at "
                        + "https://www.dhanboost.com/login to stop the daily penalty and protect your credit score. - DhanBoost");
        email(NotificationType.PAYMENT_OVERDUE, "Your DhanBoost payment is overdue",
                "Hi {name},\n\nYour payment of {amount} is overdue by {daysOverdue} day(s). Please pay as "
                        + "soon as possible — a late penalty of 2% per day is accruing and continued "
                        + "non-payment will impact your credit score.\n\n— DhanBoost");

        inApp(NotificationType.LOAN_CLOSED, "Loan closed",
                "Your loan is fully repaid and closed. Thank you!");
        sms(NotificationType.LOAN_CLOSED,
                "Your loan with DhanBoost is fully repaid and closed. Thank you. Visit "
                        + "https://www.dhanboost.com/login to borrow again. - DhanBoost");
        email(NotificationType.LOAN_CLOSED, "Your DhanBoost loan is closed",
                "Hi {name},\n\nYour loan is fully repaid and now closed. Thank you for choosing DhanBoost.\n\n"
                        + "— DhanBoost");

        // ---------------- COLLECTIONS ----------------
        inApp(NotificationType.COLLECTION_CASE_OPENED, "New collections case",
                "A collections case was opened on loan #{loanId}.");

        inApp(NotificationType.SETTLEMENT_PROPOSED, "Settlement to approve",
                "A settlement of {settlementAmount} was proposed on loan #{loanId} — your approval is needed.");

        inApp(NotificationType.SETTLEMENT_APPROVED, "Settlement approved",
                "A full & final settlement of {settlementAmount} is approved on your loan. Pay it to close.");
        sms(NotificationType.SETTLEMENT_APPROVED,
                "A full and final settlement of {settlementAmount} is approved on your DhanBoost loan. "
                        + "Pay at https://www.dhanboost.com/login to close the loan. - DhanBoost");

        inApp(NotificationType.SETTLEMENT_REJECTED, "Settlement rejected",
                "Your proposed settlement of {settlementAmount} on loan #{loanId} was not approved.");

        // ---------------- SYSTEM ----------------
        inApp(NotificationType.APPLICATION_CANCELLED, "Application cancelled",
                "Your loan application #{applicationId} has been cancelled.");

        // ---------------- REFERRAL ----------------
        inApp(NotificationType.REFERRAL_PAYOUT_PENDING, "Referral reward to pay",
                "A referral qualified — two rewards of {amount} each are pending payout. Settle them in Referral payouts.");

        inApp(NotificationType.REFERRAL_REWARD_CREDITED, "Referral reward credited",
                "Your referral reward of {amount} has been credited (ref {txnRef}). Thanks for spreading the word!");
        sms(NotificationType.REFERRAL_REWARD_CREDITED,
                "Your DhanBoost referral reward of {amount} is credited with reference {txnRef}. Log in "
                        + "at https://www.dhanboost.com/login to view it. - DhanBoost");

        // ---------------- STAFF / IAM ----------------
        email(NotificationType.STAFF_INVITED, "You're invited to DhanBoost",
                "Hi {name},\n\nYou've been invited to DhanBoost as {role}. Accept your invite and set your "
                        + "password here:\n{inviteLink}\n\n— DhanBoost");
        email(NotificationType.STAFF_CREATED, "Your DhanBoost account is ready",
                "Hi {name},\n\nYour DhanBoost staff account has been created with the role {role}. You can "
                        + "now sign in.\n\n— DhanBoost");
        inApp(NotificationType.STAFF_ROLE_CHANGED, "Your role changed",
                "Your role is now {role}.");
        email(NotificationType.STAFF_ROLE_CHANGED, "Your DhanBoost role has changed",
                "Hi {name},\n\nYour DhanBoost role has been updated to {role}.\n\n— DhanBoost");
        email(NotificationType.STAFF_DISABLED, "Your DhanBoost access has been disabled",
                "Hi {name},\n\nYour DhanBoost staff access has been disabled. If you believe this is in "
                        + "error, contact your administrator.\n\n— DhanBoost");
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
