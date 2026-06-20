/**
 * Loan domain types for the salary-linked single-repayment product.
 *
 * Pricing rules (see lib/calc/loan-math.ts):
 *  - eligible limit = 25% of monthly salary
 *  - up-front 10% processing fee + 18% GST on the fee
 *  - 1%/day interest, prepay anytime with no penalty
 *  - single repayment on salary day
 *  - late penalty 2%/day capped at 30 days, then collections
 */

export type LoanStatus =
  | "DRAFT"
  | "SUBMITTED"
  | "UNDER_REVIEW"
  | "APPROVED"
  | "REJECTED"
  | "DISBURSEMENT_PENDING"
  | "DISBURSED"
  | "ACTIVE"
  | "REPAID"
  | "OVERDUE"
  | "IN_COLLECTIONS"
  | "CLOSED";

export type LoanDocumentType = "AGREEMENT" | "SANCTION" | "KFS";

export interface LoanDocument {
  id: string;
  type: LoanDocumentType;
  /** Storage URL or key for the generated document. */
  url: string;
  generatedAt: string;
  signedAt?: string;
}

/**
 * Itemized cost breakdown for a given principal and tenure.
 * Amounts are in INR.
 */
export interface LoanCostBreakdown {
  principal: number;
  processingFee: number;
  gstOnFee: number;
  /** Amount credited to the borrower (principal - fee - gst). */
  netDisbursed: number;
  /** Interest accrued over the tenure at 1%/day. */
  interest: number;
  tenureDays: number;
  /** Total amount due on the repayment date. */
  totalRepayable: number;
}

export interface LoanApplication {
  id: string;
  applicantId: string;
  /** Requested principal in INR. */
  requestedAmount: number;
  monthlySalary: number;
  /** Computed 25%-of-salary cap at time of application. */
  eligibleLimit: number;
  status: LoanStatus;
  createdAt: string;
  updatedAt: string;
}

export interface Loan {
  id: string;
  applicationId: string;
  applicantId: string;
  status: LoanStatus;
  principal: number;
  costBreakdown: LoanCostBreakdown;
  /** Expected single-repayment date (borrower's salary day). */
  dueDate: string;
  disbursedAt?: string;
  repaidAt?: string;
  documents: LoanDocument[];
  createdAt: string;
  updatedAt: string;
}

/** How a repayment was made. NACH auto-debit is a future capability. */
export type RepaymentMethod = "UPI" | "BANK_TRANSFER" | "NACH";

export type RepaymentStatus = "PENDING_VERIFICATION" | "VERIFIED" | "REJECTED";

/**
 * A repayment made against a loan. Manual repayments (UPI / bank transfer)
 * require proof (txnRef and/or proofUrl). Partial payments are allowed; the
 * loan closes when the outstanding balance reaches zero.
 */
export interface Repayment {
  id: string;
  loanId: string;
  /** Amount paid in this transaction, in INR. */
  amount: number;
  method: RepaymentMethod;
  status: RepaymentStatus;
  /** UPI / bank transaction reference. */
  txnRef?: string;
  /** Storage URL/key for an uploaded payment screenshot. */
  proofUrl?: string;
  paidOn: string;
  /** True if a partial payment (balance remains outstanding). */
  partial: boolean;
}
