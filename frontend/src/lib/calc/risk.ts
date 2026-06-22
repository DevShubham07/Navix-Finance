import type { RiskCategory } from "@/lib/mock/types";

/**
 * Pure risk-policy functions for the salary-linked product. Kept free of React
 * and storage so they can be unit-tested directly (see scripts/scenario-harness).
 * Risk category affects limit and required checks — never price (see CLAUDE.md).
 */

/** Deterministic mock risk model — salary + bureau score drive the band. */
export function assessRisk(monthlySalary: number, creditScore: number): RiskCategory {
  if (creditScore >= 760 && monthlySalary >= 50000) return "A";
  if (creditScore >= 720 && monthlySalary >= 35000) return "B";
  if (creditScore >= 660) return "C";
  return "D";
}

/** Fraction of the 25% cap a category may draw. */
export const RISK_LIMIT_MULTIPLIER: Record<RiskCategory, number> = {
  A: 1.0,
  B: 1.0,
  C: 0.6,
  D: 0.4,
};

/** Categories that must add a co-applicant to proceed. */
export function requiresCoApplicant(risk: RiskCategory): boolean {
  return risk === "C" || risk === "D";
}

/** Sanctioned limit after the risk haircut, floored to ₹100. */
export function sanctionedLimit(baseEligible: number, risk: RiskCategory): number {
  return Math.floor((baseEligible * RISK_LIMIT_MULTIPLIER[risk]) / 100) * 100;
}
