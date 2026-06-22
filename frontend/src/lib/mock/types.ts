import type { LoanStatus, LoanCostBreakdown, Repayment } from "@/lib/domain/loan";
import type { KycCheckStatus } from "@/lib/domain/kyc";
import type { ApprovalTrailEntry, StaffRole } from "@/lib/domain/staff";
import type { DpdBucket } from "@/lib/domain/collections";

export type RiskCategory = "A" | "B" | "C" | "D";

/** Where an application sits in the staff maker-checker pipeline. */
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

export const RISK_PROFILE: Record<RiskCategory, { label: string; treatment: string }> = {
  A: { label: "Low risk", treatment: "Up to full cap · smoothest review · no co-applicant" },
  B: { label: "Moderate-low", treatment: "At/near cap · standard review" },
  C: { label: "Moderate-high", treatment: "Reduced limit · closer review · may need co-applicant" },
  D: { label: "High risk", treatment: "Much reduced or decline · co-applicant required" },
};

export interface KycSnapshot {
  pan: KycCheckStatus;
  aadhaar: KycCheckStatus;
  selfie: KycCheckStatus;
  address: KycCheckStatus;
  bank: KycCheckStatus;
  overall: KycCheckStatus;
}

export interface BankAccount {
  holderName: string;
  accountMasked: string;
  ifsc: string;
  bankName: string;
  pennyDropVerified: boolean;
}

export interface LoanRecord {
  id: string;
  principal: number;
  costBreakdown: LoanCostBreakdown;
  /** ISO date string. */
  dueDate: string;
  disbursedAt?: string;
  status: LoanStatus;
  repayments: Repayment[];
  outstanding: number;
}

export interface ApplicationRecord {
  id: string;
  applicantId: string;
  applicantName: string;
  mobile: string;
  email: string;
  panMasked: string;
  employer: string;
  designation: string;
  uan: string;
  monthlySalary: number;
  /** Day-of-month the salary lands. */
  salaryDay: number;
  requestedAmount: number;
  eligibleLimit: number;
  riskCategory: RiskCategory;
  creditScore: number;
  kyc: KycSnapshot;
  stage: AppStage;
  assignedExecutiveId?: string;
  assignedExecutiveName?: string;
  recommendation?: {
    decision: "APPROVE" | "REJECT";
    notes: string;
    by: string;
    at: string;
  };
  coApplicantRequired: boolean;
  bank: BankAccount;
  approvalTrail: ApprovalTrailEntry[];
  loan?: LoanRecord;
  createdAt: string;
  updatedAt: string;
}

export interface CollectionsCaseRecord {
  id: string;
  loanId: string;
  applicantId: string;
  applicantName: string;
  mobile: string;
  outstanding: number;
  daysPastDue: number;
  bucket: DpdBucket;
  assignedOfficerId?: string;
  assignedOfficerName?: string;
  interactions: Array<{
    id: string;
    channel: "CALL" | "SMS" | "EMAIL" | "WHATSAPP" | "FIELD_VISIT";
    outcome: "PROMISE_TO_PAY" | "NO_RESPONSE" | "DISPUTE" | "PARTIAL_PAYMENT" | "PAID" | "ESCALATE";
    notes?: string;
    proofRef?: string;
    officerName: string;
    at: string;
  }>;
  settlement?: {
    amount: number;
    status: "PROPOSED" | "APPROVED" | "REJECTED" | "SETTLED";
    proposedBy: string;
    at: string;
  };
  createdAt: string;
}

export interface StaffMember {
  id: string;
  name: string;
  email: string;
  role: StaffRole;
  active: boolean;
  lastActive?: string;
  createdAt: string;
}

export interface InviteRecord {
  id: string;
  email: string;
  role: StaffRole;
  status: "PENDING" | "ACCEPTED" | "EXPIRED" | "REVOKED";
  invitedByName: string;
  expiresAt: string;
  createdAt: string;
}

export interface BlocklistEntry {
  id: string;
  type: "PAN" | "AADHAAR" | "MOBILE" | "DEVICE" | "BANK";
  value: string;
  reason: string;
  addedByName: string;
  createdAt: string;
}
