package com.navix.loan.service;

import com.navix.common.exception.BusinessException;
import com.navix.common.exception.ResourceNotFoundException;
import com.navix.common.storage.DocumentStoragePort;
import com.navix.loan.domain.ApplicationStatus;
import com.navix.loan.dto.OfferDtos.DisbursalAccountRequest;
import com.navix.loan.dto.OfferDtos.DisbursalAccountView;
import com.navix.loan.dto.OfferDtos.OfferSummaryView;
import com.navix.loan.dto.OfferDtos.ReferenceInput;
import com.navix.loan.dto.OfferDtos.ReferenceView;
import com.navix.loan.dto.OfferDtos.SanctionLetterView;
import com.navix.loan.entity.ApplicationDocument;
import com.navix.loan.entity.ApplicationReference;
import com.navix.loan.entity.CustomerProfile;
import com.navix.loan.entity.LoanApplication;
import com.navix.loan.pdf.SanctionLetterPdfRenderer;
import com.navix.loan.repository.ApplicationDocumentRepository;
import com.navix.loan.repository.ApplicationReferenceRepository;
import com.navix.loan.repository.CustomerProfileRepository;
import com.navix.loan.repository.LoanApplicationRepository;
import com.navix.loan.service.ApplicationVerificationService.StepResult;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The borrower's post-sanction journey (revamp.md Phase 3): draw down an amount within the credit
 * team's ceiling, name two references, read and eSign the Key Fact Statement, and confirm where the
 * money lands. Everything here runs while the application is {@code SANCTIONED}; the last step hands
 * off to {@link ApplicationFlowService#acceptOffer} and the file leaves the borrower's hands.
 *
 * <p>The identity checks that sit inside this journey (DigiLocker, selfie, geo-address) are not here
 * — they are the existing {@code ApplicationVerificationService} steps, unchanged, just reached from
 * {@code /loan/*} instead of {@code /signup/*}. This class owns only what Phase 3 newly captures.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class OfferService {

    /** IST cut-off for same-day release (revamp.md decision 37) — calendar days, no weekend roll. */
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalTime SAME_DAY_CUTOFF = LocalTime.of(18, 0);

    private final LoanApplicationRepository applicationRepo;
    private final CustomerProfileRepository profileRepo;
    private final ApplicationReferenceRepository referenceRepo;
    private final PennyDropGuard pennyDropGuard;
    private final ApplicationDocumentRepository documentRepo;
    private final ApplicationVerificationService verification;
    private final ApplicationFlowService flow;
    private final JourneyService journey;
    private final SanctionLetterPdfRenderer letterRenderer;
    private final DocumentStoragePort storage;
    private final LoanMath loanMath;

    @Value("${navix.grievance.officer-name:Lalit Kumar}")
    private String grievanceOfficerName;

    @Value("${navix.grievance.officer-phone:9716760246}")
    private String grievanceOfficerPhone;

    @Value("${navix.grievance.officer-email:grievance@dhanboost.com}")
    private String grievanceOfficerEmail;

    // ---------------------------------------------------------------- 1. amount

    /**
     * Record how much of the sanctioned ceiling the borrower actually wants (screen 1). The
     * application stays {@code SANCTIONED} — nothing moves until the disbursal account is confirmed
     * at the end of the journey.
     */
    @Transactional
    public LoanApplication chooseAmount(Long appId, long amountPaise) {
        LoanApplication app = requireSanctioned(appId);
        long ceiling = ceilingOf(app);
        if (amountPaise < LoanMath.MIN_LOAN_PAISE) {
            throw new BusinessException("AMOUNT_TOO_LOW", "Requested amount is below the minimum of ₹1,000");
        }
        if (amountPaise > ceiling) {
            throw new BusinessException("LIMIT_EXCEEDED", "Requested amount exceeds the sanctioned amount");
        }
        app.setAmountRequested(amountPaise);
        applicationRepo.save(app);
        journey.advance(appId, JourneyService.OfferStep.OFFER_REPAYMENT_DATE);
        return app;
    }

    // ---------------------------------------------------------------- 4. references

    @Transactional(readOnly = true)
    public List<ReferenceView> references(Long appId) {
        return referenceRepo.findByApplicationIdOrderBySlotAsc(appId).stream()
                .map(r -> new ReferenceView(r.getSlot().intValue(), r.getFullName(), r.getMobile(),
                        r.getRelation()))
                .toList();
    }

    /**
     * Save both references (screen 4). Exactly two, and the three mobiles involved — the two contacts
     * and the borrower's own — must all differ (revamp.md decision 39): a reference who is the
     * borrower, or the same person listed twice, is not a reference.
     */
    @Transactional
    public List<ReferenceView> saveReferences(Long appId, List<ReferenceInput> inputs) {
        LoanApplication app = requireSanctioned(appId);
        if (inputs == null || inputs.size() != 2) {
            throw new BusinessException("REFERENCES_REQUIRED", "Two references are required");
        }
        String own = profileRepo.findByApplicationId(appId).map(CustomerProfile::getMobile).orElse(null);
        List<String> mobiles = new ArrayList<>();
        for (ReferenceInput in : inputs) {
            String mobile = digits(in.mobile());
            if (blank(in.fullName()) || mobile.length() != 10) {
                throw new BusinessException("REFERENCE_INVALID",
                        "Each reference needs a name and a 10-digit mobile number");
            }
            if (!ApplicationReference.RELATIONS.contains(in.relation())) {
                throw new BusinessException("REFERENCE_INVALID", "Choose a relation from the list");
            }
            if (own != null && mobile.equals(digits(own))) {
                throw new BusinessException("REFERENCE_IS_SELF",
                        "A reference cannot be your own mobile number");
            }
            if (mobiles.contains(mobile)) {
                throw new BusinessException("REFERENCE_DUPLICATE",
                        "Your two references must have different mobile numbers");
            }
            mobiles.add(mobile);
        }

        for (int i = 0; i < inputs.size(); i++) {
            ReferenceInput in = inputs.get(i);
            short slot = (short) (i + 1);
            ApplicationReference row = referenceRepo.findByApplicationIdAndSlot(appId, slot)
                    .orElseGet(ApplicationReference::new);
            row.setApplicationId(appId);
            row.setCustomerId(app.getCustomerId());
            row.setSlot(slot);
            row.setFullName(in.fullName().trim());
            row.setMobile(mobiles.get(i));
            row.setRelation(in.relation());
            referenceRepo.save(row);
        }
        journey.advance(appId, JourneyService.OfferStep.OFFER_SUMMARY);
        return references(appId);
    }

    // ---------------------------------------------------------------- 5. summary

    /**
     * The loan summary (screen 5) — every number the borrower is shown before they commit, computed
     * with the same {@link LoanMath} the disbursed loan will use, so the summary and the ledger can't
     * disagree.
     */
    @Transactional(readOnly = true)
    public OfferSummaryView summary(Long appId) {
        LoanApplication app = requireSanctioned(appId);
        long principal = drawdownOf(app);
        LocalDate repaymentDate = app.getApprovedRepaymentDate();
        int tenure = tenureDays(app);
        long fee = loanMath.processingFeePaise(principal);
        long gst = loanMath.gstPaise(principal);
        long net = loanMath.netDisbursedPaise(principal);
        long interest = loanMath.interestPaise(principal, tenure);
        return new OfferSummaryView(appId, ceilingOf(app), principal, fee, gst, net, interest,
                loanMath.totalRepayablePaise(principal, tenure), tenure, repaymentDate,
                expectedDisbursalDate(ZonedDateTime.now(IST)));
    }

    /**
     * When the borrower should expect the money: today if it is before 18:00 IST, otherwise tomorrow
     * (revamp.md decision 37). Calendar days as stated — deliberately no weekend or holiday roll,
     * even though a real NEFT/IMPS release would see one.
     */
    static LocalDate expectedDisbursalDate(ZonedDateTime nowIst) {
        return nowIst.toLocalTime().isBefore(SAME_DAY_CUTOFF)
                ? nowIst.toLocalDate()
                : nowIst.toLocalDate().plusDays(1);
    }

    // ---------------------------------------------------------------- 8. sanction letter

    /**
     * Render (or re-render) the Key Fact Statement and store it to S3 as a {@code SANCTION_LETTER}
     * document, returning a presigned URL for the borrower to read (screen 8).
     *
     * <p>Regenerated on every visit rather than cached: the borrower may have changed their drawdown
     * amount since they last looked, and a stale letter is one they'd then eSign.
     */
    @Transactional
    public SanctionLetterView sanctionLetter(Long appId) {
        LoanApplication app = requireSanctioned(appId);
        CustomerProfile p = profileRepo.findByApplicationId(appId).orElse(null);
        long principal = drawdownOf(app);
        int tenure = tenureDays(app);

        byte[] pdf = letterRenderer.render(new SanctionLetterPdfRenderer.Facts(
                appId,
                p != null ? p.getFullName() : null,
                p != null ? p.getPan() : null,
                p != null ? p.getMobile() : null,
                principal,
                loanMath.processingFeePaise(principal),
                loanMath.gstPaise(principal),
                loanMath.netDisbursedPaise(principal),
                loanMath.interestPaise(principal, tenure),
                loanMath.totalRepayablePaise(principal, tenure),
                tenure,
                app.getSanctionedAt() != null
                        ? LocalDate.ofInstant(app.getSanctionedAt(), IST) : LocalDate.now(IST),
                app.getApprovedRepaymentDate(),
                maskAccount(firstNonBlank(app.getDisbursalAccountNumber(),
                        p != null ? p.getSalaryAccountNumber() : null)),
                grievanceOfficerName, grievanceOfficerPhone, grievanceOfficerEmail));

        String key = "applications/" + appId + "/sanction_letter/sanction-letter.pdf";
        try {
            storage.store(key, pdf, "application/pdf");
        } catch (RuntimeException storageFailure) {
            // Unlike the credit brief, this one must NOT degrade quietly: the borrower has to read
            // and sign this document, so a letter they can't open has to say so rather than let
            // them walk into an eSign screen with nothing behind it.
            log.error("Sanction letter storage failed for application {}: {}", appId, storageFailure.toString());
            throw new BusinessException("SANCTION_LETTER_UNAVAILABLE",
                    "We couldn't prepare your sanction letter just now. Please try again in a moment.");
        }
        ApplicationDocument doc = documentRepo
                .findFirstByApplicationIdAndDocTypeOrderByIdDesc(appId,
                        ApplicationVerificationService.SANCTION_LETTER)
                .orElseGet(ApplicationDocument::new);
        doc.setApplicationId(appId);
        doc.setDocType(ApplicationVerificationService.SANCTION_LETTER);
        doc.setFileName("sanction-letter.pdf");
        doc.setContentType("application/pdf");
        doc.setSizeBytes((long) pdf.length);
        doc.setS3ObjectKey(key);
        doc.setData(null); // S3-backed: keep the "exactly one of data / s3ObjectKey" invariant
        documentRepo.save(doc);

        journey.advance(appId, JourneyService.OfferStep.OFFER_ESIGN);
        return new SanctionLetterView(doc.getId(), storage.presignDownload(key));
    }

    // ---------------------------------------------------------------- 9. eSign

    /** Sign the letter (screen 9). The verification service owns the port call and the audit rows. */
    @Transactional
    public StepResult esign(Long appId) {
        requireSanctioned(appId);
        StepResult result = verification.recordEsign(appId);
        journey.advance(appId, JourneyService.OfferStep.OFFER_SANCTIONED);
        return result;
    }

    // ---------------------------------------------------------------- 11. disbursal account

    /**
     * What the last screen prefills: the salary account credit already saw. The borrower confirms it
     * as-is (no penny drop, decision 9) or types another (penny drop, and the 3-strikes rule applies).
     */
    @Transactional(readOnly = true)
    public DisbursalAccountView disbursalAccount(Long appId) {
        LoanApplication app = requireSanctioned(appId);
        CustomerProfile p = profileRepo.findByApplicationId(appId).orElse(null);
        Instant lockedUntil = pennyDropGuard.lockedUntil(app.getCustomerId()).orElse(null);
        String account = firstNonBlank(app.getDisbursalAccountNumber(), p != null ? p.getSalaryAccountNumber() : null);
        String ifsc = firstNonBlank(app.getDisbursalIfsc(), p != null ? p.getSalaryIfsc() : null);
        boolean reusable = Boolean.TRUE.equals(app.getDisbursalAccountVerified())
                && matches(account, ifsc, app.getDisbursalAccountNumber(), app.getDisbursalIfsc());
        return new DisbursalAccountView(
                account,
                ifsc,
                firstNonBlank(app.getDisbursalHolderName(), p != null ? p.getFullName() : null),
                firstNonBlank(app.getDisbursalBank(), p != null ? p.getSalaryBank() : null),
                reusable,
                !reusable,
                lockedUntil,
                pennyDropGuard.remainingAttempts(app.getCustomerId()));
    }

    /**
     * Confirm where this advance is paid (screen 11) and hand the application to disbursement.
     *
     * <p>The penny drop fires <b>only</b> when the account differs from the salary account on file
     * (revamp.md decision 9) — confirming the salary account unchanged never costs a provider call and
     * never can fail. That is the accepted trade-off: the first disbursal can go to an account number
     * that was only ever typed in, with the Credit Executive's read of the file as the sole check.
     *
     * <p>A changed account that fails counts a strike; three inside a window earns a 12-hour lock, and
     * a success clears the count outright (decision 40).
     */
    @Transactional
    public LoanApplication confirmDisbursalAccount(Long appId, DisbursalAccountRequest req) {
        LoanApplication app = requireSanctioned(appId);
        if (app.getAmountRequested() == null) {
            throw new BusinessException("AMOUNT_NOT_CHOSEN", "Choose your loan amount first");
        }
        String account = digits(req.accountNumber());
        String ifsc = req.ifsc() == null ? "" : req.ifsc().trim().toUpperCase();
        if (account.length() < 6 || ifsc.length() != 11) {
            throw new BusinessException("ACCOUNT_INVALID",
                    "Invalid account details, please enter the bank details again.");
        }

        CustomerProfile p = profileRepo.findByApplicationId(appId).orElse(null);
        // "Changed" means changed from an account we already have reason to trust — either the
        // salary account credit verified the file against, or (on a re-apply) the account the last
        // advance was actually paid into, carried over by ApplicationFlowService. Matching either is
        // not a change, so no penny drop fires (revamp.md decisions 9, 45).
        boolean changed = !matches(account, ifsc, p == null ? null : p.getSalaryAccountNumber(),
                p == null ? null : p.getSalaryIfsc())
                && !matches(account, ifsc, app.getDisbursalAccountNumber(), app.getDisbursalIfsc());

        boolean verified = Boolean.TRUE.equals(app.getDisbursalAccountVerified())
                && matches(account, ifsc, app.getDisbursalAccountNumber(), app.getDisbursalIfsc());
        if (!verified) {
            if (pennyDropGuard.lockedUntil(app.getCustomerId()).isPresent()) {
                throw new BusinessException("PENNY_DROP_LOCKED",
                        "Too many failed attempts. Please try again after "
                                + PennyDropGuard.LOCK.toHours() + " hours.");
            }
            StepResult drop = verification.verifyPennyDrop(appId, account, ifsc, true);
            verified = ApplicationVerificationService.PASS.equals(drop.status());
            // Committed in its own transaction — this method throws on failure, and a strike that
            // rolled back with the rejection would never move the counter.
            pennyDropGuard.record(app.getCustomerId(), appId, account, ifsc, verified,
                    verified ? null : drop.message());
            if (!verified) {
                throw new BusinessException("ACCOUNT_INVALID",
                        "Invalid account details, please enter the bank details again.");
            }
        }

        app.setDisbursalAccountNumber(account);
        app.setDisbursalIfsc(ifsc);
        app.setDisbursalHolderName(firstNonBlank(req.holderName(), p != null ? p.getFullName() : null));
        app.setDisbursalBank(firstNonBlank(req.bank(), p != null ? p.getSalaryBank() : null));
        app.setDisbursalAccountChanged(changed);
        app.setDisbursalAccountVerified(verified);
        app.setDisbursalConfirmedAt(Instant.now());
        applicationRepo.save(app);

        journey.advance(appId, JourneyService.OfferStep.OFFER_DONE);
        // Hands off: SANCTIONED → DISBURSEMENT_PENDING, gated on the eSign row existing.
        return flow.acceptOffer(appId, app.getAmountRequested());
    }

    // ---------------------------------------------------------------- helpers

    /**
     * Every step here is the borrower's own, on an application a Credit Executive has sanctioned and
     * that has not yet left their hands. Ownership is checked by the controller; this guards the state.
     */
    private LoanApplication requireSanctioned(Long appId) {
        LoanApplication app = applicationRepo.findById(appId)
                .orElseThrow(() -> new ResourceNotFoundException("LoanApplication", String.valueOf(appId)));
        if (app.getStatus() != ApplicationStatus.SANCTIONED) {
            throw new BusinessException("NOT_APPLICABLE", "This offer isn't ready");
        }
        return app;
    }

    private static long ceilingOf(LoanApplication app) {
        return app.getSanctionedAmountPaise() != null ? app.getSanctionedAmountPaise() : 0L;
    }

    /** What the borrower has chosen to draw, defaulting to the full ceiling until they pick. */
    private static long drawdownOf(LoanApplication app) {
        return app.getAmountRequested() != null ? app.getAmountRequested() : ceilingOf(app);
    }

    /**
     * Days from today to the credit team's repayment date. Recomputed rather than read off
     * {@code sanction_tenure_days}, which was measured on the day of sanction — a borrower who
     * returns a week later would otherwise be quoted a week of interest they won't be charged.
     * Never below 1: a sanction never expires (decision 41), so the date can be in the past.
     */
    private int tenureDays(LoanApplication app) {
        LocalDate due = app.getApprovedRepaymentDate();
        if (due == null) {
            return app.getSanctionTenureDays() != null ? app.getSanctionTenureDays() : LoanMath.TERM_DAYS;
        }
        long days = ChronoUnit.DAYS.between(LocalDate.now(IST), due);
        return (int) Math.max(1, days);
    }

    private static String maskAccount(String account) {
        String digits = digits(account);
        return digits.length() < 4 ? null : "•••• " + digits.substring(digits.length() - 4);
    }

    private static String digits(String s) {
        return s == null ? "" : s.replaceAll("\\D", "");
    }

    /**
     * Whether the typed account is the same one as {@code refAccount}/{@code refIfsc}. A blank
     * reference never matches — "we have nothing on file" must read as a change (and so a penny
     * drop), not as agreement.
     */
    private static boolean matches(String account, String ifsc, String refAccount, String refIfsc) {
        String ref = digits(refAccount);
        if (ref.isEmpty() || blank(refIfsc)) {
            return false;
        }
        return account.equals(ref) && ifsc.equals(refIfsc.trim().toUpperCase());
    }

    private static String firstNonBlank(String a, String b) {
        return !blank(a) ? a : b;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
