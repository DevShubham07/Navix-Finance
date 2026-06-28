package com.navix.loan.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navix.common.storage.DocumentStoragePort;
import com.navix.common.util.Masking;
import com.navix.common.verification.BureauReportFacts;
import com.navix.loan.dto.CreditBriefDtos.CreditBriefView;
import com.navix.loan.entity.ApplicantProfile;
import com.navix.loan.entity.ApplicationDocument;
import com.navix.loan.entity.LoanApplication;
import com.navix.loan.pdf.CreditBriefPdfRenderer;
import com.navix.loan.repository.ApplicantProfileRepository;
import com.navix.loan.repository.ApplicationDocumentRepository;
import com.navix.loan.repository.LoanApplicationRepository;
import com.navix.loan.service.CreditRatingCalculator.Rating;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Builds and persists the borrower credit brief from a bureau {@link BureauReportFacts} snapshot:
 * computes the 1–5★ rating ({@link CreditRatingCalculator}), renders the one-page PDF
 * ({@link CreditBriefPdfRenderer}), stores it to S3 at a deterministic key, upserts a
 * {@code CREDIT_BRIEF} {@link ApplicationDocument} so it sits with the customer's uploaded documents,
 * and writes the rating + summary + facts back onto the {@link ApplicantProfile}.
 *
 * <p>Generation is <b>best-effort</b>: any failure (PDF, S3, serialization) is swallowed with a log so
 * it can never break the bureau pull / onboarding it hangs off.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class CreditBriefService {

    public static final String DOC_TYPE = "CREDIT_BRIEF";
    private static final String FILE_NAME = "credit-brief.pdf";
    private static final String CONTENT_TYPE = "application/pdf";

    private final CreditRatingCalculator calculator;
    private final CreditBriefPdfRenderer renderer;
    private final DocumentStoragePort storage;
    private final ApplicationDocumentRepository documentRepo;
    private final ApplicantProfileRepository profileRepo;
    private final LoanApplicationRepository applicationRepo;
    private final ObjectMapper objectMapper;

    /**
     * Generate (or regenerate) the brief for an application from freshly-parsed bureau facts. Mutates
     * and saves {@code profile}. No-op when {@code facts} is null (thin-file / CRIF).
     */
    @Transactional
    public void generate(Long appId, ApplicantProfile profile, BureauReportFacts facts) {
        if (profile == null || facts == null) {
            return;
        }
        Rating rating = calculator.rate(facts);

        // 1) Persist the rating headline + facts FIRST — this drives every staff surface and must not
        //    depend on the PDF/S3 step succeeding (e.g. S3 unreachable in local/dev).
        try {
            profile.setCreditStarRating(BigDecimal.valueOf(rating.stars()));
            profile.setCreditRecommendation(rating.recommendation());
            profile.setCreditBriefSummary(rating.summary());
            profile.setCreditBriefGeneratedAt(Instant.now());
            profile.setCreditBriefFacts(objectMapper.writeValueAsString(facts));
            profileRepo.save(profile);
        } catch (Exception e) {
            log.warn("Credit brief rating persist failed for application {}: {}", appId, e.toString());
            return;
        }

        // 2) Best-effort: render the one-page PDF, store it to S3, and register the CREDIT_BRIEF
        //    document so it rides the customer's documents list. A failure here leaves the rating intact.
        try {
            Long applicantId = applicationRepo.findById(appId)
                    .map(LoanApplication::getApplicantId).orElse(null);
            byte[] pdf = renderer.render(appId, applicantId, profile.getBureauSource(), facts, rating,
                    LocalDate.now());
            String key = "applications/" + appId + "/credit_brief/" + FILE_NAME;
            storage.store(key, pdf, CONTENT_TYPE);
            upsertDocument(appId, key, pdf.length);
        } catch (Exception e) {
            log.warn("Credit brief PDF/S3 step failed for application {} (rating saved): {}",
                    appId, e.toString());
        }
    }

    /**
     * Lazy backstop for read paths: if the profile carries parsed facts but the PDF document is missing
     * (e.g. an earlier S3 hiccup, or the row was removed), regenerate it from the stored facts.
     */
    @Transactional
    public void ensureBrief(Long appId, ApplicantProfile profile) {
        if (profile == null || profile.getCreditBriefFacts() == null) {
            return;
        }
        if (briefDocument(appId).isPresent()) {
            return;
        }
        try {
            BureauReportFacts facts = objectMapper.readValue(profile.getCreditBriefFacts(),
                    BureauReportFacts.class);
            generate(appId, profile, facts);
        } catch (Exception e) {
            log.warn("Credit brief lazy regeneration failed for application {}: {}", appId, e.toString());
        }
    }

    /**
     * Assemble the staff credit-brief view for an application: the rating headline, the categorized
     * (masked) facts, and the PDF document id. Returns an {@code available=false} shell when no brief
     * exists (thin-file / not yet pulled). Lazily regenerates the PDF if facts exist but the doc is gone.
     */
    @Transactional
    public CreditBriefView view(Long appId) {
        ApplicantProfile profile = profileRepo.findByApplicationId(appId).orElse(null);
        if (profile == null || profile.getCreditStarRating() == null) {
            return new CreditBriefView(appId, false, null, null, null, null, null, null, null);
        }
        ensureBrief(appId, profile);
        BureauReportFacts f = factsOf(profile);
        Long docId = briefDocument(appId).map(ApplicationDocument::getId).orElse(null);
        CreditBriefView.Facts facts = f == null ? null : new CreditBriefView.Facts(
                f.name(), Masking.maskPan(f.pan()), Masking.maskPhone(f.mobile()), f.dob(), f.city(),
                f.pin(), f.creditScore(), f.totalAccounts(), f.activeAccounts(), f.closedAccounts(),
                f.defaults(), f.totalBalanceRupees(), f.securedBalanceRupees(), f.unsecuredBalanceRupees(),
                f.recentInquiries30d());
        return new CreditBriefView(appId, true,
                profile.getBureauScore() != null ? profile.getBureauScore().intValue() : null,
                profile.getCreditStarRating().doubleValue(),
                profile.getCreditRecommendation(),
                profile.getCreditBriefSummary(),
                profile.getCreditBriefGeneratedAt(),
                docId, facts);
    }

    /** The stored CREDIT_BRIEF document for an application, if any (for the download presign). */
    public Optional<ApplicationDocument> briefDocument(Long appId) {
        return documentRepo.findFirstByApplicationIdAndDocTypeOrderByIdDesc(appId, DOC_TYPE);
    }

    /** Deserialize the stored facts JSON back to the typed record (null-safe). */
    public BureauReportFacts factsOf(ApplicantProfile profile) {
        if (profile == null || profile.getCreditBriefFacts() == null) {
            return null;
        }
        try {
            return objectMapper.readValue(profile.getCreditBriefFacts(), BureauReportFacts.class);
        } catch (Exception e) {
            log.warn("Could not parse stored credit-brief facts for application {}: {}",
                    profile.getApplicationId(), e.toString());
            return null;
        }
    }

    private void upsertDocument(Long appId, String key, long sizeBytes) {
        ApplicationDocument doc = documentRepo
                .findFirstByApplicationIdAndDocTypeOrderByIdDesc(appId, DOC_TYPE)
                .orElseGet(ApplicationDocument::new);
        doc.setApplicationId(appId);
        doc.setDocType(DOC_TYPE);
        doc.setFileName(FILE_NAME);
        doc.setContentType(CONTENT_TYPE);
        doc.setSizeBytes(sizeBytes);
        doc.setS3ObjectKey(key);
        doc.setData(null); // S3-backed: keep the "exactly one of data / s3ObjectKey" invariant
        documentRepo.save(doc);
    }
}
