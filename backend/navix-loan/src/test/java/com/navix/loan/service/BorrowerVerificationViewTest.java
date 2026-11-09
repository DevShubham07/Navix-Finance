package com.navix.loan.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.navix.common.exception.BusinessException;
import com.navix.common.security.ActorContext;
import com.navix.common.security.CurrentActor;
import com.navix.loan.dto.OfferDtos.EsignInitRequest;
import com.navix.loan.dto.VerificationDtos.DigilockerInitRequest;
import com.navix.loan.service.ApplicationVerificationService.StepResult;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * What a borrower reads back from a verification call: the provider's identity record, the bureau
 * score and the bank's holder name stay staff-only. Plus the reserved storage folders and the bounds
 * on the borrower-supplied redirect URLs.
 */
class BorrowerVerificationViewTest {

    @AfterEach
    void clear() {
        ActorContext.clear();
    }

    private static StepResult step(String type, Long score, Double nameMatch, Map<String, Object> derived) {
        return new StepResult(type, "PASS", "ok", derived, "FINTRIX", "t1", "c1", nameMatch, score, null);
    }

    @Test
    void panResultKeepsOnlyTheRejectFlagForABorrower() {
        ActorContext.set(new CurrentActor("21", "Asha", "BORROWER"));
        StepResult pan = step("PAN", null, null, Map.of("fullName", "Someone Else", "dob", "1990-01-01",
                "maskedAadhaar", "XXXXXXXX1234", "addressZip", "560001", "applicationRejected", true));

        StepResult out = ApplicationVerificationService.withoutStaffOnlyFields(pan);

        assertThat(out.derived()).containsOnlyKeys("applicationRejected");
    }

    @Test
    void bureauScoreAndAggregatesAreHiddenButTheChallengeSurvives() {
        ActorContext.set(new CurrentActor("21", "Asha", "BORROWER"));
        StepResult bureau = step("BUREAU", 742L, null, Map.of("individualScore", 742, "identityMismatch", "x",
                "bureauChallenge", true, "bureauChallengeQuestion", "Which bank?"));

        StepResult out = ApplicationVerificationService.withoutStaffOnlyFields(bureau);

        assertThat(out.score()).isNull();
        assertThat(out.derived()).containsOnlyKeys("bureauChallenge", "bureauChallengeQuestion");
    }

    @Test
    void pennyDropHidesTheHolderNameAndTheSimilarityScore() {
        ActorContext.set(new CurrentActor("21", "Asha", "BORROWER"));
        StepResult drop = step("PENNY_DROP", null, 0.93, Map.of("accountExists", true,
                "beneficiaryName", "R K SHARMA", "fullName", "R K SHARMA", "bankRrn", "123", "nameMatch", 0.93));

        StepResult out = ApplicationVerificationService.withoutStaffOnlyFields(drop);

        assertThat(out.nameMatch()).isNull();
        assertThat(out.derived()).containsOnlyKeys("accountExists");
    }

    @Test
    void staffReadsTheFullResult() {
        ActorContext.set(new CurrentActor("1", "Admin", "ADMIN"));
        StepResult bureau = step("BUREAU", 742L, null, Map.of("individualScore", 742));

        assertThat(ApplicationVerificationService.withoutStaffOnlyFields(bureau)).isSameAs(bureau);
    }

    @Test
    void keysInTheSystemFoldersCannotBeRegisteredAsABorrowerUpload() {
        for (String folder : List.of("credit_brief", "bureau_report", "sanction_letter", "signed_agreement")) {
            assertThatThrownBy(() -> ApplicationVerificationService.requireOwnApplicationKeys(
                    42L, List.of("applications/42/" + folder + "/x.pdf")))
                    .isInstanceOf(BusinessException.class);
        }
        // An ordinary upload still passes.
        ApplicationVerificationService.requireOwnApplicationKeys(42L, List.of("applications/42/bank_statement/1.pdf"));
    }

    @Test
    void overLongRedirectUrlsAreRefused() {
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
        String huge = "a.".repeat(5000);

        assertThat(validator.validate(new DigilockerInitRequest(huge))).isNotEmpty();
        assertThat(validator.validate(new DigilockerInitRequest("https://dhanboost.com/kyc/digilocker/callback"))).isEmpty();
        assertThat(validator.validate(new EsignInitRequest(huge, "https://dhanboost.com/ok"))).isNotEmpty();
        assertThat(validator.validate(new com.navix.loan.service.OnboardingWaitlistService.SubmitRequest(
                "A", "a@" + ".".repeat(300), null, null, null))).isNotEmpty();
    }
}
