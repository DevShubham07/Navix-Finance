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

/** Where an application sits in the staff maker-checker pipeline (badge rendering). */
export type AppStage =
  | "KYC_REVIEW" // KYC Approver must clear identity
  | "CREDIT_QUEUE" // Credit Head assigns to an executive
  | "CREDIT_REVIEW" // Credit Executive reviews + recommends
  | "CREDIT_DECISION" // Credit Head gives final approval
  | "DISBURSEMENT" // Disbursement Head releases funds
  | "ACCOUNTING" // Accountant confirms the bank transfer
  | "ACTIVE" // loan live, repayment clock running
  | "REPAID"
  | "REJECTED";

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
