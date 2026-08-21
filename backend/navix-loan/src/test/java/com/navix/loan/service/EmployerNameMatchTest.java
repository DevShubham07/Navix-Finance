package com.navix.loan.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * {@link ApplicationVerificationService#employerNamesAgree} against the real declared-vs-EPFO pairs
 * from the first production backfill.
 *
 * <p>Digitap's own {@code employer_name_match} scores whole-string similarity, so it rejected four of
 * these. Only one of the four was a genuinely different employer. The other three were the borrower
 * typing a short form of their employer's legal name into a free-text box, which the EPFO register
 * spells out in full — and because {@code is_employed} folds the name score in, each of those files
 * came back needing manual review for no reason.
 *
 * <p>The rule is asymmetric on purpose. A false review costs a reviewer a minute; a false
 * confirmation tells them we verified employment at a company the borrower does not work for. So the
 * accept cases below must all be positive identifications, and {@code accenture / eClerx} must stay
 * rejected no matter how the rule is tuned.
 */
class EmployerNameMatchTest {

    @ParameterizedTest(name = "[{index}] {0} == {1}")
    @CsvSource({
            // The three the provider wrongly rejected: an exact distinctive word, plus extra words
            // that are part of the legal name the borrower did not type.
            "sprinklr,                              SPRINKLR INDIA PVT LTD",
            "Accenture,                             ACCENTURE SOLUTIONS PVT. LTD.",
            // ...and one where the borrower also closed up a space the register keeps ("HY GRO").
            "Hygro Chemicals,                       HY GRO CHEMICALS PHARMTEK PRIVATE LIMITED",

            // The ones the provider already got right; they must not regress.
            "Schaeffler India Limited,              SCHAEFFLER INDIA LIMITED",
            "Ltimindtree,                           LTIMINDTREE LIMITED",
            "tata consultancy services Pvt Ltd,     TATA CONSULTANCY SERVICES LIMITED",
            "ENDURANCE TECHNOLOGIES LTD,            ENDURANCE TECHNOLOGIES LIMITED",
            "Synchrony international Pvt Ltd,       SYNCHRONY INTERNATIONAL SERVICES PRIVATE LIMITED",
            "cutmac marketing pvt ltd,              CUTMAC MARKETING PVT LTD",
            "ICFAI FOundation for Higher Education, THE ICFAI FOUNDATION FOR HIGHER EDUCATION TRUST",
            // Singular declared against a plural on the register.
            "Vodafone India service private limited, VODAFONE INDIA SERVICES PRIVATE LIMITED",
    })
    void identifiesTheSameEmployer(String declared, String onRecord) {
        assertThat(ApplicationVerificationService.employerNamesAgree(declared, onRecord)).isTrue();
    }

    @ParameterizedTest(name = "[{index}] {0} != {1}")
    @CsvSource({
            // THE case this check exists for: the borrower named Accenture, the EPFO says eClerx.
            // A real discrepancy on a real file, and it must survive every future loosening.
            "Accenture,          M/S ECLERX SERVICES LIMITED",
            "Infosys,            WIPRO LIMITED",
            "Tata Motors,        MAHINDRA AND MAHINDRA LIMITED",
            // Sharing only boilerplate is not evidence of anything.
            "Private Limited,    SOME EMPLOYER PRIVATE LIMITED",
            "The Company,        ANOTHER COMPANY LTD",
            // A two-letter fragment in common is a collision, not an identification.
            "HR Services,        HP ENTERPRISES LIMITED",
    })
    void rejectsADifferentEmployer(String declared, String onRecord) {
        assertThat(ApplicationVerificationService.employerNamesAgree(declared, onRecord)).isFalse();
    }

    @Test
    void missingNamesAreNotAMatch() {
        // No opinion, expressed as "not established" — never as a confirmation.
        assertThat(ApplicationVerificationService.employerNamesAgree(null, "SPRINKLR INDIA PVT LTD")).isFalse();
        assertThat(ApplicationVerificationService.employerNamesAgree("sprinklr", null)).isFalse();
        assertThat(ApplicationVerificationService.employerNamesAgree("  ", "SPRINKLR INDIA PVT LTD")).isFalse();
        assertThat(ApplicationVerificationService.employerNamesAgree("Pvt Ltd", "PRIVATE LIMITED")).isFalse();
    }
}
