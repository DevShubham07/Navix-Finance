/**
 * KYC domain types (PAN, DigiLocker/Aadhaar, identity match).
 */

export type KycCheckType =
  | "PAN"
  | "AADHAAR"
  | "EMAIL"
  | "ADDRESS"
  | "BANK"
  | "CREDIT";

export type KycCheckStatus =
  | "PENDING"
  | "PASSED"
  | "FAILED"
  | "MANUAL_REVIEW";

export interface KycCheck {
  type: KycCheckType;
  status: KycCheckStatus;
  /** Raw provider payload reference, for audit. */
  reference?: string;
  performedAt?: string;
}

export interface PanResult {
  pan: string;
  fullName: string;
  dob: string;
  gender: string;
  aadhaarLinked: boolean;
  maskedAadhaar: string;
  address: string;
}

export interface AadhaarResult {
  maskedAadhaar: string;
  name: string;
  dob: string;
  gender: string;
  address: string;
}

/** Result of matching PAN identity against Aadhaar/DigiLocker identity. */
export interface IdentityMatch {
  nameMatch: boolean;
  dobMatch: boolean;
  /** Overall pass gate for identity verification. */
  matched: boolean;
  score?: number;
}

export interface KycCase {
  id: string;
  applicationId: string;
  status: KycCheckStatus;
  checks: KycCheck[];
  pan?: PanResult;
  aadhaar?: AadhaarResult;
  identityMatch?: IdentityMatch;
  createdAt: string;
  updatedAt: string;
}
