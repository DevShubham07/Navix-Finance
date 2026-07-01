/**
 * Customer-facing borrower domain types.
 *
 * These describe the designed borrower journey (status timeline, KYC checks, the
 * onboarding customer shape). They were previously colocated with the mock
 * Zustand store; they now live here as plain domain types, independent of any
 * data layer, so the live pages/components can use them without importing mock.
 */

/** Customer-facing status journey (see product flow §6). */
export type BorrowerStatus =
  | "NEW"
  | "APPLIED"
  | "UNDER_REVIEW"
  | "APPROVED"
  | "DOCS_SIGNED"
  | "BANK_VERIFIED"
  | "DISBURSING"
  | "ACTIVE"
  | "REPAID"
  | "DECLINED"
  | "OVERDUE";

/** Per-check KYC state surfaced to the borrower. */
export type KycCheck = "PENDING" | "IN_PROGRESS" | "VERIFIED" | "FAILED";

export interface KycState {
  pan: KycCheck;
  aadhaar: KycCheck;
  selfie: KycCheck;
  address: KycCheck;
  bank: KycCheck;
}

export interface CoApplicant {
  fullName: string;
  pan: string;
  mobile: string;
  relationship: string;
}

/** Everything captured during onboarding for one customer. */
export interface CustomerProfile {
  fullName: string;
  pan: string;
  aadhaar: string;
  mobile: string;
  mobileVerified: boolean;
  email: string;
  employer: string;
  designation: string;
  uan: string;
  monthlySalary: number;
  /** Day-of-month the salary lands. */
  salaryDay: number;
  bankName: string;
  accountLast4: string;
  ifsc: string;
  financialsLinked: boolean;
  addressLine: string;
  city: string;
  pin: string;
  coApplicant?: CoApplicant;
}
