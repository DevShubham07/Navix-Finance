package com.navix.loan.bre;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The single list of business rules the BRE tab shows. Toggleable rules carry the {@code feature_flag}
 * key that switches them and the default used when no row exists (every default reproduces the
 * behaviour from before the tab existed). Thresholds carry their code default and allowed range.
 */
public final class BreRuleCatalog {

    private BreRuleCatalog() {
    }

    public enum Group { AUTO, ADVISORY, REVIEW, ALWAYS, GATE }

    public record Threshold(String key, String label, String unit, long defaultValue, long min, long max) {
    }

    public record Rule(String key, Group group, String title, String checks, String outcome, String source,
                       String flagKey, boolean defaultOn, boolean toggleable, List<Threshold> thresholds) {
    }

    // ---- rule keys used by decision points ----
    public static final String SELF_EMPLOYED_REJECT = "self-employed-reject";
    public static final String LOW_BUREAU_SCORE = "low-bureau-score-reject";
    public static final String AADHAAR_MISMATCH = "aadhaar-mismatch-reject";
    public static final String DOB_MISMATCH = "dob-mismatch-reject";
    public static final String AADHAAR_DUPLICATE_REJECT = "aadhaar-duplicate-reject";
    public static final String AADHAAR_DUPLICATE_SANCTION_BLOCK = "aadhaar-duplicate-sanction-block";
    public static final String PAST_DELINQUENCY_REJECT = "past-delinquency-reject";
    public static final String REBORROW_AUTO_SANCTION = "reborrow-auto-sanction";

    // ---- threshold keys ----
    public static final String T_SELF_EMPLOYED_BLOCK_DAYS = "self-employed-block-days";
    public static final String T_BUREAU_FLOOR = "bureau-floor";
    public static final String T_BUREAU_BLOCK_DAYS = "bureau-block-days";
    public static final String T_FRAUD_BLOCK_DAYS = "fraud-block-days";
    public static final String T_LATE_TOLERANCE_DAYS = "late-tolerance-days";
    public static final String T_MANUAL_BLOCK_DAYS = "manual-block-days";
    public static final String T_ADVISORY_MIN_SCORE = "advisory-min-credit-score";
    public static final String T_ADVISORY_MAX_NBFC_DPD = "advisory-max-nbfc-dpd";
    public static final String T_ADVISORY_SALARY_TOL = "advisory-salary-tolerance-pct";

    private static final String ONBOARDING_PAUSED = "onboarding-paused";
    private static final String APPLICATIONS_CLOSED = "applications-closed";

    private static Threshold days(String key, String label, long def) {
        return new Threshold(key, label, "days", def, 1, 3650);
    }

    private static Rule auto(String key, String flag, boolean defaultOn, String title, String checks,
                             String outcome, String source, Threshold... thresholds) {
        return new Rule(key, Group.AUTO, title, checks, outcome, source, flag, defaultOn, true, List.of(thresholds));
    }

    private static Rule advisory(String key, String title, String checks, Threshold... thresholds) {
        return new Rule("advisory-" + key, Group.ADVISORY, title, checks,
                "Shown as a pass or flag on the Customer 360 BRE tab; never rejects anyone.",
                "frontend lib/customers/bre/rules.ts (evaluated in the browser)",
                "bre-advisory-" + key, true, true, List.of(thresholds));
    }

    private static Rule review(String key, String title, String checks, String source) {
        return new Rule(key, Group.REVIEW, title, checks,
                "A failed or unclear result is marked REVIEW for a credit reviewer; the borrower is never declined by it.",
                source, null, false, false, List.of());
    }

    private static Rule always(String key, String title, String checks, String outcome, String source,
                               Threshold... thresholds) {
        return new Rule(key, Group.ALWAYS, title, checks, outcome, source, null, false, false, List.of(thresholds));
    }

    private static Rule gate(String key, String flag, String title, String checks, String outcome) {
        return new Rule(key, Group.GATE, title, checks, outcome,
                "Staff > Settings (change it there)", flag, false, false, List.of());
    }

    public static final List<Rule> RULES;

    static {
        List<Rule> r = new ArrayList<>();
        // ---- A. auto-decision rules ----
        r.add(auto(SELF_EMPLOYED_REJECT, "bre-self-employed-reject", true,
                "Self-employed applicants are turned away",
                "The borrower picks Self-employed on the employment step.",
                "File rejected (SELF_EMPLOYED) and the mobile is blocked for the cooling-off window. The borrower sees a neutral not-eligible screen. When off, self-employed applicants carry on to the employer step.",
                "ApplicationController.selfEmployed; borrower signup/employment page",
                days(T_SELF_EMPLOYED_BLOCK_DAYS, "Block days", 90)));
        r.add(auto(LOW_BUREAU_SCORE, "bureau-auto-reject", false,
                "Low credit score is rejected automatically",
                "A real numeric bureau score below the floor (a missing or unparsable score is never rejected).",
                "File rejected (LOW_BUREAU_SCORE) and blocked for the cooling-off window. When off, every bureau result goes to a human.",
                "ApplicationVerificationService.pullBureau; BureauBackfillService",
                new Threshold(T_BUREAU_FLOOR, "Score floor", "score", 550, 300, 900),
                days(T_BUREAU_BLOCK_DAYS, "Block days", 90)));
        r.add(auto(AADHAAR_MISMATCH, "bre-aadhaar-mismatch-reject", true,
                "Typed Aadhaar number does not match the PAN record or DigiLocker",
                "The 12-digit Aadhaar the borrower typed against the masked Aadhaar on the PAN record and the one DigiLocker returns (last four digits).",
                "File rejected as FRAUD_REJECTED and the mobile is blocked. When off, the mismatch is still recorded as evidence for staff but the check does not fail and nobody is rejected.",
                "ApplicationVerificationService.verifyPan; completeDigilocker",
                days(T_FRAUD_BLOCK_DAYS, "Fraud block days", 365)));
        r.add(auto(DOB_MISMATCH, "bre-dob-mismatch-reject", true,
                "Date of birth entered does not match DigiLocker",
                "The DOB typed at intake against the DOB on the DigiLocker Aadhaar (1 Jan is compared by year only). Uses the same fraud block days as the Aadhaar mismatch rule.",
                "File rejected as FRAUD_REJECTED. When off, the mismatch is kept as evidence and the Aadhaar DOB replaces the typed one on the profile.",
                "ApplicationVerificationService.completeDigilocker"));
        r.add(auto(AADHAAR_DUPLICATE_REJECT, "bre-aadhaar-duplicate-reject", true,
                "Aadhaar already on another customer's submitted file",
                "At submit-kyc, the typed Aadhaar number against every other customer's submitted application.",
                "File rejected permanently (AADHAAR_DUPLICATE) with a reference to the earlier case. When off, the file proceeds (the duplicate is still flagged for review).",
                "ApplicationVerificationService.rejectIfAadhaarDuplicate"));
        r.add(auto(AADHAAR_DUPLICATE_SANCTION_BLOCK, "bre-aadhaar-duplicate-sanction-block", true,
                "Unresolved duplicate Aadhaar blocks sanction",
                "An Aadhaar-duplicate review row that is not cleared to PASS when a credit officer tries to sanction.",
                "Sanction is refused (AADHAAR_DUPLICATE_UNRESOLVED) until the check is cleared. When off, the sanction goes through.",
                "ApplicationFlowService.sanction"));
        r.add(auto(PAST_DELINQUENCY_REJECT, "bre-past-delinquency-reject", true,
                "Returning borrower with a late or unpaid history is rejected",
                "On reborrow: a prior loan written off or defaulted, still overdue, or repaid later than the tolerance after its due date.",
                "Reborrow auto-rejected (PAST_DELINQUENCY) unless an admin reapply allowance exists. When off, history is ignored and a customer who never repaid still gets NO_PRIOR_LOAN.",
                "ApplicationFlowService.reborrow; isDisqualifiedByHistory",
                new Threshold(T_LATE_TOLERANCE_DAYS, "Late tolerance", "days", 5, 0, 60)));
        r.add(auto(REBORROW_AUTO_SANCTION, "bre-reborrow-auto-sanction", true,
                "Clean returning borrower is sanctioned automatically",
                "On reborrow: a customer with a repaid loan and a clean standing.",
                "Skips KYC and credit and lands directly on SANCTIONED. When off, the new file goes to the credit queue (KYC_PENDING) for an executive to decide.",
                "ApplicationFlowService.reborrow"));

        // ---- B. advisory rules (Customer 360) ----
        r.add(advisory("credit-score", "Credit score above the minimum",
                "The bureau score is above the minimum score.",
                new Threshold(T_ADVISORY_MIN_SCORE, "Minimum credit score", "score", 600, 300, 900)));
        r.add(advisory("personal-loan", "Personal loan in the last 9 months",
                "The bureau report shows a personal loan opened in the lookback window."));
        r.add(advisory("pan-aadhaar-link", "PAN linked to Aadhaar",
                "The PAN record and the link-status check both say the PAN is linked to Aadhaar."));
        r.add(advisory("ownership", "Individual ownership, not guarantor",
                "Loans on the report are held individually, not as guarantor or joint."));
        r.add(advisory("nbfc", "NBFC loan in the last 9 months, no DPD over the limit",
                "The report has an NBFC loan in the window and none ran past the maximum days past due.",
                new Threshold(T_ADVISORY_MAX_NBFC_DPD, "Maximum NBFC DPD", "days", 10, 0, 180)));
        r.add(advisory("occupation", "Not self-employed, army, lawyer or MHA",
                "Declared and bureau occupation are salaried, not in an excluded profession."));
        r.add(advisory("aadhaar-pan", "Aadhaar-PAN linkage and name",
                "Aadhaar and PAN agree on linkage and on the holder's name."));
        r.add(advisory("uan-employer", "UAN and company name",
                "The EPFO UAN record matches the employer the borrower declared."));
        r.add(advisory("aadhaar-match", "Aadhaar: typed vs DigiLocker vs photo",
                "The typed Aadhaar number, DigiLocker and the card photo agree."));
        r.add(advisory("salary-match", "Salary: bank statement vs slips vs declared",
                "The three salary sources agree within the tolerance.",
                new Threshold(T_ADVISORY_SALARY_TOL, "Salary tolerance", "percent", 10, 1, 50)));

        // ---- C. review-only checks ----
        r.add(review("review-name-match", "Name match across PAN, Aadhaar and bank",
                "Names from PAN, DigiLocker and the bank account compared for similarity.",
                "ApplicationVerificationService.recomputeNameMatch"));
        r.add(review("review-bureau-identity", "Bureau report belongs to the borrower",
                "The report's PAN and DOB against the borrower's own verified details.",
                "ApplicationVerificationService.pullBureau"));
        r.add(review("review-crif-kba", "CRIF knowledge-based question",
                "The bureau may ask the borrower a security question before releasing the report.",
                "ApplicationVerificationService.answerBureauChallenge"));
        r.add(review("review-epfo", "EPFO employment",
                "UAN employment history against the declared employer (hourly auto-retry).",
                "ApplicationVerificationService.verifyEmployment"));
        r.add(review("review-email", "Personal and official email",
                "Both emails confirmed by OTP; official domain checked against the employer.",
                "ApplicationVerificationService.verifyEmail"));
        r.add(review("review-pan-valid", "PAN is valid",
                "The PAN returns an active record from the provider chain.",
                "ApplicationVerificationService.verifyPan"));
        r.add(review("review-pan-aadhaar-link", "PAN-Aadhaar link status",
                "Provider link-status check on the typed Aadhaar.",
                "ApplicationVerificationService.verifyPanAadhaarLink"));
        r.add(review("review-address", "Address check",
                "Geolocation reverse-geocode against the typed address.",
                "ApplicationVerificationService.verifyAddress"));
        r.add(review("review-selfie", "Selfie liveness and face match",
                "Liveness and a 1:1 face match against the Aadhaar photo. Never blocks the borrower.",
                "ApplicationVerificationService.verifySelfie"));
        r.add(review("review-digilocker", "DigiLocker consent and signature",
                "A DigiLocker Aadhaar whose signature cannot be validated is parked.",
                "ApplicationVerificationService.completeDigilocker"));
        r.add(review("review-provider-outage", "Provider outage",
                "A verification provider that errors or times out.",
                "RoutingVerificationPort"));

        // ---- D. always enforced ----
        r.add(always("always-blocklist", "Blocklist and staff block",
                "Any identifier (mobile, PAN, Aadhaar, account) on the blocklist, or a staff block on the customer.",
                "New application or reborrow refused with a neutral NOT_ELIGIBLE message.",
                "ApplicationFlowService.assertNotBlocked"));
        r.add(always("always-cooling-off", "Cooling-off window after a rejection",
                "A rejection that still carries a blocked-until date.",
                "A new application is refused until the window ends.",
                "ApplicationFlowService.assertCanStartNewApplication"));
        r.add(always("always-one-advance", "One advance at a time",
                "A live application or an active or overdue loan.",
                "A new application or reborrow is refused.",
                "ApplicationFlowService.assertCanStartNewApplication"));
        r.add(always("always-kyc-incomplete", "Intake must be complete",
                "PAN, email, bureau and salary attempted, plus the Aadhaar number and four card images.",
                "submit-kyc refused with KYC_INCOMPLETE.",
                "ApplicationFlowService.submitKyc"));
        r.add(always("always-amount-limits", "Minimum amount and limit cap",
                "Requested amount below Rs 1,000 or above the sanctioned ceiling.",
                "The amount is refused.",
                "OfferService; ApplicationFlowService.sanction"));
        r.add(always("always-salary-limit", "25% of salary limit",
                "Eligible limit is 25% of monthly salary, floored to Rs 100 (an admin override wins).",
                "The offer ceiling is capped at the limit.",
                "LoanMath.eligibleLimitPaise"));
        r.add(always("always-penny-drop-lock", "Penny-drop lock",
                "Three failed Rs 1 bank verifications.",
                "The disbursal account step locks for 12 hours.",
                "PennyDropGuard"));
        r.add(always("always-offer-gates", "eSign, reference OTP and mandate gates",
                "The sanction letter must be signed; references OTP-confirmed and the mandate registered when those features are on.",
                "The journey does not advance until the step is done.",
                "JourneyService; OfferService"));
        r.add(always("always-manual-reject-cooling-off", "Cooling-off after a manual reject",
                "A credit officer or disbursement head rejects a file.",
                "The customer is blocked from reapplying for the window.",
                "ApplicationFlowService.rejectWithBlock",
                days(T_MANUAL_BLOCK_DAYS, "Block days", 30)));

        // ---- E. intake gates (changed in Settings) ----
        r.add(gate(ONBOARDING_PAUSED, ONBOARDING_PAUSED, "Onboarding paused (waitlist)",
                "New borrowers without a loan.",
                "They see the waitlist form instead of the application."));
        r.add(gate(APPLICATIONS_CLOSED, APPLICATIONS_CLOSED, "Applications closed",
                "Any new application or reborrow.",
                "Refused with APPLICATIONS_CLOSED; files in flight carry on."));
        RULES = List.copyOf(r);
    }

    public static final Map<String, Rule> BY_KEY =
            RULES.stream().collect(Collectors.toUnmodifiableMap(Rule::key, Function.identity()));

    public static final Map<String, Threshold> THRESHOLDS = RULES.stream()
            .flatMap(rule -> rule.thresholds().stream())
            .collect(Collectors.toUnmodifiableMap(Threshold::key, Function.identity()));
}
