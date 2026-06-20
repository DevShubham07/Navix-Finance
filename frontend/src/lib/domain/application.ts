/**
 * Borrower signup / onboarding application types.
 */

export type SignupStep =
  | "MOBILE_OTP"
  | "PAN"
  | "DIGILOCKER_KYC"
  | "EMAIL_EMPLOYMENT"
  | "ADDRESS"
  | "BANK_PENNYDROP"
  | "SALARY_DETAILS"
  | "CREDIT_CHECK"
  | "LOAN_OFFER"
  | "COMPLETED";

export interface SignupApplication {
  id: string;
  mobile: string;
  email?: string;
  pan?: string;
  /** The step the applicant currently needs to complete. */
  currentStep: SignupStep;
  /** Steps the applicant has finished, in order. */
  completedSteps: SignupStep[];
  monthlySalary?: number;
  createdAt: string;
  updatedAt: string;
}
