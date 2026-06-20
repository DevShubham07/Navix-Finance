package com.navix.income.service;

import com.navix.income.domain.RiskCategory;
import org.springframework.stereotype.Service;

/**
 * Computes a borrower's {@link RiskCategory} from multiple signals.
 *
 * <p>Inputs considered:
 * <ul>
 *   <li>Bureau score and credit history</li>
 *   <li>Income stability (salary regularity, UAN tenure)</li>
 *   <li>Banking behaviour (balances, bounces, inflow patterns)</li>
 *   <li>Prior NAVIX repayment history</li>
 * </ul>
 *
 * <p>Output is one of A/B/C/D. Risk category affects limit and check depth,
 * never pricing.
 *
 * TODO: scoring bands / weights are TBD. Implement the model that maps the
 * combined signals to a numeric score and then to a category band.
 */
@Service
public class RiskScoringService {

    /**
     * Assess risk for an applicant.
     *
     * TODO: implement. Currently returns no result.
     *
     * @param applicantId the applicant to assess
     * @return assigned risk category
     */
    public RiskCategory assess(Long applicantId) {
        // TODO: gather bureau / income / banking / NAVIX-history signals,
        // compute score, map score -> A/B/C/D using (TBD) bands.
        throw new UnsupportedOperationException("RiskScoringService.assess not implemented yet");
    }
}
