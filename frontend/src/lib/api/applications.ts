/**
 * Typed client for the DhanBoost backend application state-machine API.
 *
 * IMPORTANT: these functions call the Next.js BFF proxies, NOT the Spring
 * backend directly. Staff actions/queues go through `/api/staff/applications/*`
 * (identity injected from the httpOnly `navix_staff` cookie); borrower actions
 * go through `/api/borrower/applications/*` (identity from `navix_borrower`).
 * The two namespaces never share a session/cookie.
 *
 * Every backend response is an `ApiResponse<T>` envelope; the helpers below
 * unwrap `data` and throw {@link ApplicationApiError} on `success:false`,
 * surfacing `error.code` so the UI can show a meaningful message.
 */

import { daysBetween } from "@/lib/calc/loan-math";
import type { JsonValue } from "@/lib/credit/provider-report";

// ---------------------------------------------------------------------------
// Domain types (mirror the backend exactly)
// ---------------------------------------------------------------------------

export type ApplicationStatus =
  | "DRAFT"
  | "KYC_PENDING"
  | "KYC_APPROVED"
  | "KYC_REJECTED"
  | "PRE_APPROVED"
  | "REVIEW_PENDING"
  | "CREDIT_EXEC_PENDING"
  | "CREDIT_EXEC_APPROVED"
  | "CREDIT_HEAD_PENDING"
  | "CREDIT_HEAD_APPROVED"
  // Credit has decided; the borrower is walking the post-approval journey (V45).
  | "SANCTIONED"
  | "DISBURSEMENT_PENDING"
  | "ACCOUNTANT_PENDING"
  | "DISBURSEMENT_FAILED"
  | "DISBURSED"
  | "ACTIVE"
  | "OVERDUE"
  | "DEFAULTED"
  | "CLOSED"
  | "WRITTEN_OFF"
  | "REJECTED"
  | "CANCELLED";

export interface ApplicationView {
  id: number;
  customerId: number;
  status: ApplicationStatus;
  amountRequestedPaise: number | null;
  eligibleLimitPaise: number | null;
  purpose: string | null;
  assignedExecutiveId: number | null;
  loanId: number | null;
  /** Salary-credit day-of-month (1–31) — fixed on the first loan and reused on reborrow (no re-pick). */
  salaryCreditDay?: number | null;
  /** A pre-approved reborrow that reached disbursement without credit review (fast-track section). */
  fastTrack?: boolean;
  /** Staff-only credit headline (populated on staff queue rows; never on borrower paths). */
  creditScore?: number | null;
  starRating?: number | null;
  recommendation?: string | null;
  /** Staff-only customer identity (populated on staff queue rows; never on borrower paths). */
  customerName?: string | null;
  customerMobile?: string | null;
  /**
   * The minted loan's EFFECTIVE status and due date (staff queue rows only; null before disbursal).
   *
   * Read `loanDueDate` — not `status` — to decide whether a live loan is overdue: `LoanStatus.OVERDUE`
   * is compute-on-read and the application aggregate stays `ACTIVE` for the whole repayment window,
   * so `status === "OVERDUE"` is effectively never true. `loanStatus` also stays `IN_COLLECTIONS`
   * once a case is opened, past due or not.
   */
  loanStatus?: string | null;
  loanDueDate?: string | null;
  /**
   * The credit sanction (V45) — null until a Credit Executive accepts the lead. The sanctioned
   * amount is the CEILING the borrower may draw from, not what they end up requesting.
   */
  sanctionedAmountPaise?: number | null;
  approvedRepaymentDate?: string | null;
  sanctionTenureDays?: number | null;
  sanctionRemarks?: string | null;
  sanctionedAt?: string | null;
  /** "Mark lead pending" — a staff-only tag; the borrower is never told (revamp.md decision 30). */
  markedPendingAt?: string | null;
  pendingReason?: string | null;
  /**
   * Where this advance is paid (V46), confirmed by the borrower on the last offer screen. Held in
   * full because the Disbursement Head cannot make the transfer otherwise (revamp.md decision 16) —
   * so never log these and never put them in an export.
   */
  disbursalAccountNumber?: string | null;
  disbursalIfsc?: string | null;
  disbursalHolderName?: string | null;
  disbursalBank?: string | null;
  /** The borrower named a different account, so a penny drop ran against it. */
  disbursalAccountChanged?: boolean | null;
  /** True only when this exact account-number/IFSC pair has a reusable successful verification. */
  disbursalAccountVerified?: boolean | null;
  /** Staff-enriched identity/bank fields (populated on staff reads; always null on the borrower `/mine` route). */
  pan?: string | null;
  salaryAccountNumber?: string | null;
  salaryIfsc?: string | null;
  /**
   * The REAL, server-resolved assignee name for `assignedExecutiveId` (staff reads only — always null
   * on the borrower `/mine` route). Distinct from any implied/displayed Credit Head label shown in the
   * journey view when this is still null — that is a frontend-only convention, never persisted here.
   */
  assignedExecutiveName?: string | null;
  /** When this application entered its CURRENT `status` (latest `application_event.at`). Staff-only. */
  currentStageEnteredAt?: string | null;
}

/** One staffer's action off the application-event trail (backs /staff/my-decisions). */
export interface DecisionView {
  applicationId: number;
  customerName: string | null;
  action: string;
  fromStatus: string | null;
  toStatus: string;
  notes: string | null;
  at: string;
}

/**
 * True when a disbursed loan is past its due date — the DPD definition used across this product
 * (`daysBetween` compares calendar dates, so this is timezone-safe). Falls back to the application
 * status only when no loan due date is known.
 */
export function isLoanOverdue(app: ApplicationView, asOf: Date = new Date()): boolean {
  if (!app.loanDueDate) return app.status === "OVERDUE";
  return daysBetween(new Date(`${app.loanDueDate}T00:00:00`), asOf) > 0;
}

/**
 * ADMIN-only flat view of an application with full KYC detail + onboarding completeness — covers
 * complete AND incomplete (DRAFT / partially filled) applications. Mirrors backend AdminApplicationView.
 */
export interface AdminApplicationView {
  id: number;
  customerId: number;
  status: ApplicationStatus;
  amountRequestedPaise: number | null;
  eligibleLimitPaise: number | null;
  purpose: string | null;
  salaryCreditDay: number | null;
  assignedExecutiveId: number | null;
  loanId: number | null;
  hasProfile: boolean;
  fullName: string | null;
  pan: string | null;
  mobile: string | null;
  email: string | null;
  dob: string | null;
  address: string | null;
  employer: string | null;
  employmentStatus: string | null;
  monthlySalaryPaise: number | null;
  salaryBank: string | null;
  /** Staff/admin-only bank identifiers, full value (no masking — product decision). */
  salaryAccountNumber?: string | null;
  salaryIfsc?: string | null;
  creditScore: number | null;
  starRating: number | null;
  recommendation: string | null;
  riskCategory: string | null;
  /** Required verification checks currently PASS/REVIEW, out of stepsRequired. */
  stepsCompleted: number;
  stepsRequired: number;
  agreementAccepted: boolean;
  /** True once every required step is cleared and the agreement accepted. */
  complete: boolean;
  kycCapturedAt: string | null;
  bureauState: BureauState;
  /** Real, server-resolved assignee name for `assignedExecutiveId` (ADMIN-only register). */
  assignedExecutiveName?: string | null;
  /** When this application entered its CURRENT `status` (latest `application_event.at`). */
  currentStageEnteredAt?: string | null;
}

/**
 * One row of the telecaller queue (pre-`SANCTIONED` applications, mirrors backend
 * `TelecallingView`). `staleDays` is computed from the latest `application_event.at`
 * (falling back to `created_at`) — default sort is stale-first.
 */
export interface TelecallingView {
  id: number;
  customerId: number;
  status: ApplicationStatus;
  customerName: string | null;
  mobile: string | null;
  email: string | null;
  pan: string | null;
  stepsCompleted: number;
  stepsRequired: number;
  ownerStaffId: number | null;
  staleDays: number;
}

export interface EventView {
  id: number;
  fromStatus: ApplicationStatus | null;
  toStatus: ApplicationStatus | null;
  actorId: number | null;
  actorRole: string | null;
  /** The resolved actor's display name (borrower profile name or staff name); null if unresolvable. */
  actorName: string | null;
  action: string | null;
  notes: string | null;
  at: string;
}

export interface LoanView {
  id: number;
  customerId: number;
  principalPaise: number;
  processingFeePaise: number;
  gstPaise: number;
  netDisbursedPaise: number;
  dailyInterestRate: number;
  disbursedOn: string | null;
  dueDate: string | null;
  totalRepayablePaise: number;
  outstandingPaise: number;
  status: string;
  /** Bank/UPI reference for the outgoing disbursal (captured at release). */
  disbursalTxnRef: string | null;
}

export interface OutstandingView {
  loanId: number;
  asOf: string;
  outstandingPaise: number;
  /** Non-null when collections has an approved settlement: outstandingPaise is then the
   *  settlement-capped full-and-final figure. */
  settledAmountPaise?: number | null;
  /** Itemized make-up of the balance as of `asOf` (optional for back-compat with older backends):
   *  accrued interest, accrued late penalty, and the sum of verified payments so far. When a
   *  settlement caps the figure, these may sum to more than `outstandingPaise`. */
  interestPaise?: number;
  penaltyPaise?: number;
  verifiedPaise?: number;
  /** The day counts those amounts were charged over — interest capped at the tenure, penalty net
   *  of the 1-day grace and capped at 30 — so the UI can show "1%/day × 27 days". */
  interestDays?: number;
  penaltyDays?: number;
}

export type PaymentMethodName = "UPI" | "BANK_TRANSFER" | "NACH";
export type PaymentStatusName = "PENDING_VERIFICATION" | "VERIFIED" | "REJECTED";

/** A recorded repayment against a loan (mirrors backend PaymentView). */
export interface PaymentView {
  id: number;
  loanId: number;
  amountPaise: number;
  method: PaymentMethodName;
  status: PaymentStatusName;
  txnRef: string | null;
  proofUrl: string | null;
  paidOn: string | null;
  partial: boolean;
  /** Staff-only context populated by the accountant's pending-verification queue. */
  customerId?: number | null;
  customerName?: string | null;
  /** Set when `status === "REJECTED"` — a fixed reason code + optional free-text note. */
  rejectionReason?: RejectionReasonCode | null;
  rejectionNote?: string | null;
}

/** Fixed picklist of repayment-rejection reasons (mirrors the backend validation set). */
export type RejectionReasonCode =
  | "WRONG_REFERENCE"
  | "AMOUNT_MISMATCH"
  | "NOT_RECEIVED"
  | "UNREADABLE_PROOF"
  | "OTHER";

/** Human labels for {@link RejectionReasonCode}, shown in the staff reject dialog and borrower banner. */
export const REJECTION_REASON_LABEL: Record<RejectionReasonCode, string> = {
  WRONG_REFERENCE: "Wrong payment reference",
  AMOUNT_MISMATCH: "Amount doesn't match",
  NOT_RECEIVED: "Payment not received",
  UNREADABLE_PROOF: "Proof unreadable",
  OTHER: "Other",
};

export type TransactionType = "DISBURSAL" | "REPAYMENT";
export type TransactionDirection = "OUTGOING" | "INCOMING";

/** One row in the accountant's company-wide transactions ledger (mirrors backend TransactionView). */
export interface TransactionView {
  id: string;
  type: TransactionType;
  direction: TransactionDirection;
  loanId: number | null;
  customerId: number | null;
  borrowerName: string | null;
  pan: string | null;
  amountPaise: number;
  txnRef: string | null;
  status: string | null;
  date: string | null;
}

/**
 * Customer KYC snapshot for an application. Staff see the full, unmasked identity + verification
 * detail; on the borrower's own read the credit/risk/bureau fields come back null.
 */
export interface ProfileView {
  applicationId: number;
  fullName: string | null;
  pan: string | null;
  mobile: string | null;
  dob: string | null;
  address: string | null;
  employer: string | null;
  employmentStatus: string | null;
  monthlySalaryPaise: number | null;
  salaryBank: string | null;
  /** Salary management (Phase 2.1). Annual figure in paise; percentages as numbers. */
  annualSalaryPaise?: number | null;
  salaryPercentage?: number | null;
  incrementPercentage?: number | null;
  email?: string | null;
  /** Emergency contact (Phase 2.2) — editable on the borrower profile. */
  emergencyContactName?: string | null;
  emergencyContactPhone?: string | null;
  emergencyContactRelation?: string | null;
  /** Staff-only credit headline (score + 1–5★ rating + verdict). Null until the bureau is pulled. */
  creditScore?: number | null;
  starRating?: number | null;
  recommendation?: string | null;
  /** Staff-only verification + risk detail (null on borrower-facing reads). */
  bureauSource?: string | null;
  riskCategory?: string | null;
  panVerified?: boolean | null;
  aadhaarLinked?: boolean | null;
  /** Aadhaar verified via DigiLocker (consent done + Aadhaar fetched + document ingested). */
  aadhaarVerified?: boolean | null;
  emailVerified?: boolean | null;
  addressVerified?: boolean | null;
  pennyDropVerified?: boolean | null;
  nameMatchScore?: number | null;
  /** Phase 1 intake — also what the wizard re-hydrates from on another device (revamp.md C1). */
  officialEmail?: string | null;
  salaryAccountNumber?: string | null;
  salaryIfsc?: string | null;
  salaryAccountMobile?: string | null;
  previousSalaryDate?: string | null;
  termsVersion?: string | null;
  termsAcceptedAt?: string | null;
  pepDeclaredAt?: string | null;
  creditBriefSummary?: string | null;
  creditBriefGeneratedAt?: string | null;
}

/** What the borrower submits for their KYC profile (all fields optional). */
export interface ProfileInput {
  fullName?: string;
  pan?: string;
  mobile?: string; // 10 digits; uniqueness enforced server-side
  email?: string; // contact email — gates email notifications
  dob?: string; // ISO yyyy-mm-dd
  address?: string;
  employer?: string;
  employmentStatus?: string;
  monthlySalaryPaise?: number;
  salaryBank?: string;
  // --- Phase 1 intake (revamp.md). All optional: the wizard saves in slices. ---
  /** Official work email — the only address the email-verification API is run against. */
  officialEmail?: string;
  salaryAccountNumber?: string;
  salaryIfsc?: string;
  salaryAccountMobile?: string;
  /** Last salary credit date (ISO yyyy-mm-dd); the recurring salary day is derived from it. */
  previousSalaryDate?: string;
  /** Self-employed declared annual income, in paise. */
  annualSalaryPaise?: number;
  /** T&C version accepted on screen 1; the accepted-at timestamp is stamped server-side. */
  termsVersion?: string;
  /** True = "I am not a Politically Exposed Person". */
  pepDeclared?: boolean;
}

/** Where the borrower is in the onboarding journey, answered server-side (revamp.md C1). */
export interface JourneyView {
  step: string;
  route: string;
  index: number;
  total: number;
  completed: string[];
  /**
   * The steps THIS borrower's journey actually has, in order. Not a constant: a re-apply drops the
   * screens whose evidence carried over from the prior advance (V47), so `index`/`total` count
   * against this list rather than a fixed eleven.
   */
  steps: string[];
}

/** One row of the ADMIN rejection register. */
export interface RejectionView {
  id: number;
  applicationId: number | null;
  customerId: number;
  borrowerName: string | null;
  mobile: string | null;
  reasonCode: string;
  reasonDetail: string | null;
  auto: boolean;
  blockedUntil: string | null;
  createdAt: string | null;
}

/**
 * Borrower self-edit of their own profile (Phase 2.2) — non-identity fields only. Verification-linked
 * edits reset the matching check; a salary change recomputes eligibility.
 */
export interface EditProfileInput {
  address?: string | null;
  employer?: string | null;
  employmentStatus?: string | null;
  monthlySalaryPaise?: number | null;
  salaryBank?: string | null;
  email?: string | null;
  emergencyContactName?: string | null;
  emergencyContactPhone?: string | null;
  emergencyContactRelation?: string | null;
}

/** Borrower notification preferences (Phase 2.2) — server-persisted; honored by the engine. */
export interface BorrowerPreferences {
  emailOptIn: boolean;
  smsOptIn: boolean;
  offersOptIn: boolean;
}

/** Document metadata (no bytes). */
export interface DocumentView {
  id: number;
  docType: string;
  fileName: string;
  contentType: string | null;
  sizeBytes: number | null;
  uploadedAt: string;
  /** True when the bytes live in S3 (fetch a presigned URL); false for legacy inline base64. */
  s3?: boolean;
}

/** A presigned GET URL for an S3-backed document (mirrors backend DocumentUrlView). */
export interface DocumentUrlView {
  id: number;
  fileName: string | null;
  contentType: string | null;
  url: string;
}

/** One application's documents, grouped for the customer-wide documents view (mirrors backend). */
export interface ApplicationDocumentGroup {
  applicationId: number;
  applicationStatus: ApplicationStatus;
  documents: DocumentView[];
}

/** A document with its bytes as base64, for view/download. */
export interface DocumentContent {
  id: number;
  docType: string;
  fileName: string;
  contentType: string | null;
  dataBase64: string;
}

/** One row in the staff Customers list (borrower-centric roll-up; mirrors backend CustomerSummary). */
export interface CustomerSummary {
  customerId: number;
  name: string | null;
  pan: string | null;
  mobile: string | null;
  applicationCount: number;
  loanCount: number;
  latestStatus: string | null;
  totalOutstandingPaise: number;
  /** Latest credit headline for the customer (staff-only). */
  creditScore?: number | null;
  starRating?: number | null;
  /** Newest loan's effectiveStatus(today) — outranks latestStatus for client-side segments. */
  loanStatus?: string | null;
  ownerStaffId?: number | null;
  ownerName?: string | null;
  bureauState?: BureauState | null;
}

/** A customer's full history: latest profile + every application, loan and payment (mirrors backend). */
export interface CustomerDetail {
  customerId: number;
  profile: ProfileView | null;
  applications: ApplicationView[];
  loans: LoanView[];
  payments: PaymentView[];
  ownerStaffId?: number | null;
  ownerName?: string | null;
  /** The latest application's full credit brief (categorized facts + PDF doc id) — null until a bureau
   *  pull has happened. Same shape `staffApi.creditBrief` returns, attached here to avoid a second
   *  round-trip when the Credit Report tab just needs "all the info" already on the customer object. */
  creditBrief?: CreditBriefView | null;
}

/** Parsed bureau facts behind the credit brief (Categories A/B/C). Amounts are rupees (bureau unit). */
export interface CreditBriefFacts {
  name: string | null;
  pan: string | null;
  mobile: string | null;
  dob: string | null;
  city: string | null;
  pin: string | null;
  creditScore: number | null;
  totalAccounts: number | null;
  activeAccounts: number | null;
  closedAccounts: number | null;
  defaults: number | null;
  totalBalance: number | null;
  securedBalance: number | null;
  unsecuredBalance: number | null;
  recentInquiries30d: number | null;
}

/** Whether the bureau pull ran for an application, and what it found (mirrors backend BureauState). */
export type BureauState = "NOT_FETCHED" | "NO_RECORD" | "FOUND";

/** Staff credit brief for an application: 1–5★ rating headline + facts + the CREDIT_BRIEF PDF doc id. */
export interface CreditBriefView {
  applicationId: number;
  /** Legacy: true whenever a provider response was stored, which today includes the NO_RECORD case
   *  too — do not gate empty-state rendering on this; switch on bureauState instead. */
  available: boolean;
  creditScore: number | null;
  starRating: number | null;
  recommendation: string | null;
  summary: string | null;
  generatedAt: string | null;
  /** The stored CREDIT_BRIEF document id — fetch its presigned URL via staffApi.documentUrl. */
  documentId: number | null;
  facts: CreditBriefFacts | null;
  /** Exact staff-only provider response, including every nested bureau field and tradeline. */
  providerResponse: JsonValue | null;
  bureauState: BureauState;
}

/** Admin edit of a customer's KYC / salary data (identity fields excluded — they stay locked). */
export interface UpdateCustomerInput {
  fullName?: string | null;
  address?: string | null;
  employer?: string | null;
  employmentStatus?: string | null;
  monthlySalaryPaise?: number | null;
  annualSalaryPaise?: number | null;
  salaryPercentage?: number | null;
  incrementPercentage?: number | null;
  salaryBank?: string | null;
}

/** One audited profile/salary change (Phase 2.1) — mirrors backend ProfileChangeView. */
export interface ProfileChangeView {
  id: number;
  field: string;
  oldValue: string | null;
  newValue: string | null;
  modifiedBy: string | null;
  /** ISO timestamp. */
  modifiedAt: string | null;
}

/** One entry in the unified customer activity timeline — mirrors backend ActivityEntry. */
export type ActivityType = "LIFECYCLE" | "PROFILE" | "REVERIFY" | "REMARK" | "CALL";
export interface ActivityEntry {
  type: ActivityType;
  applicationId: number | null;
  title: string;
  detail: string | null;
  actor: string | null;
  /** ISO timestamp. */
  at: string | null;
}

/** A staff remark on a customer — mirrors backend RemarkView. */
export interface RemarkView {
  id: number;
  body: string;
  author: string | null;
  /** ISO timestamp. */
  at: string | null;
}

/** A staff call log on a customer — mirrors backend CallLogView. */
export interface CallLogView {
  id: number;
  callType: string;
  outcome: string;
  callbackOn: string | null;
  notes: string | null;
  author: string | null;
  /** ISO timestamp. */
  at: string | null;
}

export interface AddCallLogInput {
  callType: string;
  outcome: string;
  callbackOn?: string | null;
  notes?: string | null;
}

/**
 * Admin-managed company payee shown on the borrower repay screen. The `*Url` fields are short-lived
 * presigned GETs for an uploaded QR image / account-info PDF (null when none is uploaded — the UI
 * then falls back to a bundled static asset).
 */
export interface PaymentSettings {
  upiId: string | null;
  accountName: string | null;
  accountNumber: string | null;
  ifsc: string | null;
  bankName: string | null;
  qrUrl: string | null;
  accountInfoUrl: string | null;
}

/** ADMIN edit of the payee (all fields optional; identity-less text + uploaded asset keys). */
export interface UpdatePaymentSettingsInput {
  upiId?: string | null;
  accountName?: string | null;
  accountNumber?: string | null;
  ifsc?: string | null;
  bankName?: string | null;
  qrObjectKey?: string | null;
  accountInfoObjectKey?: string | null;
}

/** Standard backend envelope. */
export interface ApiResponse<T> {
  success: boolean;
  message: string;
  data: T;
  error: { code: string; message: string } | null;
  timestamp: string;
}

// ---------------------------------------------------------------------------
// Error + low-level fetch (talks to the BFF, same-origin)
// ---------------------------------------------------------------------------

export class ApplicationApiError extends Error {
  /** Backend `error.code` when present, otherwise an HTTP-derived code. */
  readonly code: string;
  readonly status: number;
  /** Cross-tier correlation id from the `X-Request-Id` response header, when present. */
  readonly requestId?: string;

  constructor(message: string, code: string, status: number, requestId?: string) {
    super(message);
    this.name = "ApplicationApiError";
    this.code = code;
    this.status = status;
    this.requestId = requestId;
  }
}

type Method = "GET" | "POST" | "PUT" | "DELETE";

async function bff<T>(path: string, method: Method, body?: unknown): Promise<T> {
  let res: Response;
  try {
    res = await fetch(path, {
      method,
      headers: { "Content-Type": "application/json", Accept: "application/json" },
      body: body !== undefined ? JSON.stringify(body) : undefined,
      // BFF sets/reads httpOnly cookies; ensure they ride along.
      credentials: "same-origin",
      cache: "no-store",
    });
  } catch (e) {
    throw new ApplicationApiError(
      e instanceof Error ? e.message : "Network error reaching the server.",
      "NETWORK_ERROR",
      0,
    );
  }

  // Cross-tier correlation id echoed by the BFF/backend (X-Request-Id) — attached to every thrown
  // error so the UI can show a "ref" that greps straight to the backend logs.
  const requestId = res.headers.get("X-Request-Id") ?? undefined;

  const text = await res.text();
  let parsed: ApiResponse<T> | undefined;
  try {
    parsed = text ? (JSON.parse(text) as ApiResponse<T>) : undefined;
  } catch {
    parsed = undefined;
  }

  // Envelope-level failure (the backend returns success:false with an error).
  if (parsed && parsed.success === false) {
    const code = parsed.error?.code ?? `HTTP_${res.status}`;
    const message = parsed.error?.message ?? parsed.message ?? "Request failed.";
    throw new ApplicationApiError(message, code, res.status, requestId);
  }

  if (!res.ok) {
    throw new ApplicationApiError(
      parsed?.message ?? `Request failed with status ${res.status}.`,
      parsed?.error?.code ?? `HTTP_${res.status}`,
      res.status,
      requestId,
    );
  }

  if (!parsed) {
    throw new ApplicationApiError("Empty response from server.", "EMPTY_RESPONSE", res.status, requestId);
  }

  return parsed.data;
}

/** PUT raw bytes (File or Blob) straight to a presigned S3 URL — never through the BFF. */
async function putToPresignedUrl(url: string, body: Blob, contentType: string): Promise<void> {
  const res = await fetch(url, { method: "PUT", body, headers: { "Content-Type": contentType } });
  if (!res.ok) {
    throw new ApplicationApiError(`Upload failed (status ${res.status}).`, `UPLOAD_FAILED_${res.status}`, res.status);
  }
}

// ---------------------------------------------------------------------------
// Borrower client — routes under /api/borrower/*
// ---------------------------------------------------------------------------

const BORROWER_BASE = "/api/borrower/applications";
const BORROWER_LOAN_BASE = "/api/borrower/loan";

export const borrowerApi = {
  /** Create a DRAFT application for the given customer. */
  create: (customerId: number) =>
    bff<ApplicationView>(`${BORROWER_BASE}`, "POST", { customerId }),

  /**
   * Returning-borrower reborrow: start a new advance reusing the saved KYC profile (customerId is
   * resolved server-side from the session). Returns PRE_APPROVED (good standing → choose amount) or
   * REVIEW_PENDING (past delinquency → held for KYC review). Throws NO_PRIOR_LOAN / ACTIVE_LOAN.
   */
  reborrow: () => bff<ApplicationView>(`${BORROWER_BASE}/reborrow`, "POST"),

  /** DRAFT -> KYC_PENDING. */
  submitKyc: (id: number) =>
    bff<ApplicationView>(`${BORROWER_BASE}/${id}/submit-kyc`, "POST"),

  /** Borrower declared self-employed — auto-rejects and starts the 90-day cooling-off window. */
  selfEmployed: (id: number) =>
    bff<ApplicationView>(`${BORROWER_BASE}/${id}/self-employed`, "POST"),

  /** Set amount/purpose/salary-day once KYC is approved (stays KYC_APPROVED). */
  apply: (
    id: number,
    payload: {
      amountPaise: number;
      purpose?: string;
      eligibleLimitPaise?: number;
      salaryCreditDay?: number;
    },
  ) => bff<ApplicationView>(`${BORROWER_BASE}/${id}/apply`, "POST", payload),

  /** Poll a single application. */
  get: (id: number) => bff<ApplicationView>(`${BORROWER_BASE}/${id}`, "GET"),

  /** The borrower's own applications, newest first (for "Past loans" / "Transactions"). */
  myApplications: () => bff<ApplicationView[]>(`${BORROWER_BASE}/mine`, "GET"),

  /** Event/audit trail for an application. */
  events: (id: number) => bff<EventView[]>(`${BORROWER_BASE}/${id}/events`, "GET"),

  /** Loan summary (net disbursed, due date, total repayable) once ACTIVE. */
  loan: (loanId: number) => bff<LoanView>(`${BORROWER_LOAN_BASE}/${loanId}`, "GET"),

  /** Prepayment-aware balance as of a date (interest only to the day paid). */
  outstanding: (loanId: number, asOf?: string) =>
    bff<OutstandingView>(
      `${BORROWER_LOAN_BASE}/${loanId}/outstanding${asOf ? `?asOf=${encodeURIComponent(asOf)}` : ""}`,
      "GET",
    ),

  /** Record a (full / partial / prepayment) repayment with proof — lands PENDING_VERIFICATION. */
  recordRepayment: (
    loanId: number,
    payload: { amountPaise: number; method: PaymentMethodName; txnRef?: string; proofUrl?: string; paidOn?: string },
  ) => bff<PaymentView>(`${BORROWER_LOAN_BASE}/${loanId}/repayments`, "POST", payload),

  /** Repayments recorded against this loan (the borrower's payment history). */
  repayments: (loanId: number) => bff<PaymentView[]>(`${BORROWER_LOAN_BASE}/${loanId}/repayments`, "GET"),

  /** Save/update the customer KYC details for this application. */
  saveProfile: (id: number, profile: ProfileInput) =>
    bff<ProfileView>(`${BORROWER_BASE}/${id}/profile`, "PUT", profile),

  /** Self-edit own profile (non-identity fields); verification-linked edits reset the matching check. */
  editProfile: (id: number, edit: EditProfileInput) =>
    bff<ProfileView>(`${BORROWER_BASE}/${id}/profile/self`, "PUT", edit),

  /** Read back the (masked) profile. */
  getProfile: (id: number) => bff<ProfileView>(`${BORROWER_BASE}/${id}/profile`, "GET"),

  /** The borrower's notification preferences (server-persisted; honored by the engine). */
  getPreferences: () => bff<BorrowerPreferences>(`/api/borrower/preferences`, "GET"),
  updatePreferences: (prefs: BorrowerPreferences) =>
    bff<BorrowerPreferences>(`/api/borrower/preferences`, "PUT", prefs),

  /** Upload one document (bytes as base64). */
  uploadDocument: (
    id: number,
    doc: { docType: string; fileName: string; contentType?: string; dataBase64: string },
  ) => bff<DocumentView>(`${BORROWER_BASE}/${id}/documents`, "POST", doc),

  /** List documents already uploaded for this application. */
  documents: (id: number) => bff<DocumentView[]>(`${BORROWER_BASE}/${id}/documents`, "GET"),
};

/** Server-side onboarding position — what makes a second device resume mid-flow (revamp.md C1). */
export const journeyApi = {
  get: (id: number) => bff<JourneyView>(`${BORROWER_BASE}/${id}/journey`, "GET"),
  advance: (id: number, step: string) =>
    bff<JourneyView>(`${BORROWER_BASE}/${id}/journey/${step}`, "POST"),
};

// ---------------------------------------------------------------------------
// Verification (P5 sequential onboarding) — routes under /api/borrower/applications/{id}/verify/*
// ---------------------------------------------------------------------------

/** Outcome of a single verification check (mirrors backend StepResult). */
export type CheckStatus = "PASS" | "FAIL" | "REVIEW" | "PENDING";

/** A verification step's live result. `derived` carries step-specific extras (urls, flags, …). */
export interface StepResult {
  checkType: string;
  status: CheckStatus;
  message: string | null;
  derived: Record<string, unknown>;
}

/** Result of an OTP send: whether it went out, and (dev/mock only) the code itself. */
export interface OtpRequestResult {
  sent: boolean;
  devCode: string | null;
  ttlSeconds: number;
}

/** Required-step completion snapshot (Phase 3.2) — mirrors backend VerificationProgress. */
export interface VerificationProgress {
  required: number;
  completed: number;
  failed: number;
  pending: number;
  percent: number;
}

/** One row in the pending-API dashboard (Phase 3.3) — mirrors backend VerificationOverviewRow. */
export interface VerificationOverviewRow {
  applicationId: number;
  customerId: number | null;
  borrowerName: string | null;
  borrowerMobile: string | null;
  checkType: string;
  status: CheckStatus;
  provider: string | null;
  message: string | null;
  updatedAt: string | null;
  /** The owning application's lifecycle status (e.g. "KYC_PENDING") — lets staff surfaces scope
   *  verification rows to applications that still need a KYC decision. */
  applicationStatus: ApplicationStatus | null;
}

/** Pending-API dashboard payload (Phase 3.3) — status tallies + rows. */
export interface VerificationOverview {
  passed: number;
  review: number;
  failed: number;
  pending: number;
  neverRun: number;
  rows: VerificationOverviewRow[];
}

/** Result of a staff-triggered KYC reminder (Phase 3.4). */
export interface ReminderResult {
  sent: boolean;
  pendingCount: number;
  pendingSteps: string;
}

/** Result of asking the app-scoped verify endpoint for a presigned PUT URL. */
export interface VerifyPresign {
  key: string;
  url: string;
}

/** A single document version the borrower consents to (e.g. "loan-agreement@1"). */
export type AgreementVersion = string;

export const verificationApi = {
  /** PAN identity match. */
  pan: (id: number, pan: string) =>
    bff<StepResult>(`${BORROWER_BASE}/${id}/verify/pan`, "POST", { pan }),

  /** Official/work email → employer match. */
  email: (id: number, officialEmail: string) =>
    bff<StepResult>(`${BORROWER_BASE}/${id}/verify/email`, "POST", { officialEmail }),

  /** Address: either live geolocation (lat/long) or a typed manual address. */
  address: (
    id: number,
    body: { latitude: number; longitude: number } | { manualAddress: string },
  ) => bff<StepResult>(`${BORROWER_BASE}/${id}/verify/address`, "POST", body),

  /** DigiLocker: start the consent flow (returns derived.clientId + derived.url). */
  digilockerInit: (id: number, redirectUrl: string) =>
    bff<StepResult>(`${BORROWER_BASE}/${id}/verify/digilocker/init`, "POST", { redirectUrl }),

  /** DigiLocker: poll consent progress (derived.completed / .failed / .status). */
  digilockerStatus: (id: number) =>
    bff<StepResult>(`${BORROWER_BASE}/${id}/verify/digilocker/status`, "GET"),

  /** DigiLocker: finalise once the consent flow reports completed. */
  digilockerComplete: (id: number) =>
    bff<StepResult>(`${BORROWER_BASE}/${id}/verify/digilocker/complete`, "POST"),

  /**
   * Credit bureau pull. `otp` is the already-verified bureau-consent code (see `bureauConsent` below)
   * forwarded so it can be threaded into Digitap's Credit Analytics payload, which mandates it — omit
   * it only for a staff-triggered manual retry. Score/category are never surfaced to the borrower.
   */
  bureau: (id: number, otp?: string) =>
    bff<StepResult>(`${BORROWER_BASE}/${id}/verify/bureau`, "POST", otp ? { otp } : undefined),

  /**
   * Declared salary + uploaded slip object keys (min 3 months). Optionally sets the salary-credit day
   * (1–31) in the same call — used by the reborrow salary step so the customer confirms their salary
   * day alongside re-verifying income; omitted on the first-time onboarding path (day set at apply).
   */
  salary: (id: number, monthlySalaryPaise: number, slipObjectKeys: string[], salaryCreditDay?: number) =>
    bff<StepResult>(`${BORROWER_BASE}/${id}/verify/salary`, "POST", {
      monthlySalaryPaise,
      slipObjectKeys,
      salaryCreditDay,
    }),

  /** Penny-drop on the salary account → name match. */
  pennyDrop: (id: number, accountNumber: string, ifsc: string) =>
    bff<StepResult>(`${BORROWER_BASE}/${id}/verify/penny-drop`, "POST", { accountNumber, ifsc }),

  /** Selfie liveness/face match against the uploaded selfie object key (Digitap fallback path). */
  selfie: (id: number, selfieObjectKey: string) =>
    bff<StepResult>(`${BORROWER_BASE}/${id}/verify/selfie`, "POST", { selfieObjectKey }),

  /**
   * Start the Signzy liveness video journey (primary selfie path). The result's
   * `derived.videoUrl` is where to redirect the borrower; `derived.fallback === true` means Signzy
   * liveness is unavailable and the caller should use the camera-capture + `selfie` fallback.
   */
  selfieLivenessInit: (id: number) =>
    bff<StepResult>(`${BORROWER_BASE}/${id}/verify/selfie/liveness/init`, "POST"),

  /** Poll the liveness journey result (PENDING until the borrower finishes; then PASS/REVIEW). */
  selfieLivenessStatus: (id: number) =>
    bff<StepResult>(`${BORROWER_BASE}/${id}/verify/selfie/liveness/status`, "GET"),

  /** Record consent to the agreement document set. */
  agreement: (id: number, versions: AgreementVersion[]) =>
    bff<StepResult>(`${BORROWER_BASE}/${id}/verify/agreement`, "POST", { versions }),

  /**
   * Send the bureau-consent OTP — a purpose-dedicated code, isolated from the login OTP (a concurrent
   * login OTP request can no longer clobber this one, or vice versa). Application-scoped: the mobile is
   * resolved server-side from the profile, not passed by the client.
   */
  requestBureauConsentOtp: (id: number) =>
    bff<OtpRequestResult>(`${BORROWER_BASE}/${id}/verify/bureau-consent/otp`, "POST"),

  /**
   * Record OTP-verified consent to the credit-bureau enquiry. Runs BEFORE the PAN fetch; the OTP is
   * verified server-side against the mobile on file (never one supplied by the client).
   */
  bureauConsent: (id: number, otp: string, consentText: string) =>
    bff<StepResult>(`${BORROWER_BASE}/${id}/verify/bureau-consent`, "POST", { otp, consentText }),

  /** The full verification status board for this application. */
  summary: (id: number) => bff<StepResult[]>(`${BORROWER_BASE}/${id}/verify/summary`, "GET"),

  /** Ask the app-scoped endpoint for a presigned PUT URL (echo `key` back on the verify call). */
  presignUpload: (
    id: number,
    body: { docType: string; fileName: string; contentType: string },
  ) => bff<VerifyPresign>(`${BORROWER_BASE}/${id}/verify/presign-upload`, "POST", body),

  /** PUT raw bytes (File or Blob) straight to the presigned S3 URL — never through the BFF. */
  putToPresignedUrl,

  /**
   * Persist already-uploaded S3 keys as `ApplicationDocument` rows under an arbitrary docType — the
   * generic counterpart to `salary(...)`'s hardcoded SALARY_SLIP persistence. Used for the 6-month
   * bank-statement upload on the bank-details page (docType "BANK_STATEMENT").
   */
  uploadedDocuments: (id: number, body: { docType: string; objectKeys: string[] }) =>
    bff<void>(`${BORROWER_BASE}/${id}/verify/documents`, "POST", body),
};

// ---------------------------------------------------------------------------
// Offer journey (revamp.md Phase 3) — what the borrower walks after credit sanctions their file.
// Routes under /api/borrower/applications/{id}/offer/*. The identity checks inside this journey
// (DigiLocker, selfie, address) stay on `verificationApi` — only what Phase 3 newly captures is here.
// ---------------------------------------------------------------------------

export type ReferenceRelation =
  | "PARENT" | "SPOUSE" | "SIBLING" | "RELATIVE" | "FRIEND" | "COLLEAGUE" | "MANAGER" | "NEIGHBOUR";

/** The relations the references screen offers, in the order the backend lists them. */
export const REFERENCE_RELATIONS: ReferenceRelation[] = [
  "PARENT", "SPOUSE", "SIBLING", "RELATIVE", "FRIEND", "COLLEAGUE", "MANAGER", "NEIGHBOUR",
];

export interface ReferenceView {
  slot: number;
  fullName: string;
  mobile: string;
  relation: string;
}

export interface ReferenceInput {
  fullName: string;
  mobile: string;
  relation: string;
}

/** Every number on the loan-summary screen, computed server-side with the real loan math. */
export interface OfferSummaryView {
  applicationId: number;
  /** What credit sanctioned — the ceiling. */
  ceilingPaise: number;
  /** What the borrower chose to draw. */
  principalPaise: number;
  processingFeePaise: number;
  gstPaise: number;
  netDisbursedPaise: number;
  interestPaise: number;
  totalRepayablePaise: number;
  tenureDays: number;
  repaymentDate: string | null;
  /** Calendar days, 18:00 IST cut-off — no weekend or holiday roll. */
  expectedDisbursalDate: string;
}

export interface SanctionLetterView {
  documentId: number;
  url: string;
}

export interface DisbursalAccountView {
  accountNumber: string | null;
  ifsc: string | null;
  holderName: string | null;
  bank: string | null;
  verified: boolean;
  pennyDropRequired: boolean;
  /** Non-null: the borrower burnt their penny-drop attempts and must wait this out. */
  lockedUntil: string | null;
  attemptsLeft: number;
}

const OFFER = (id: number) => `${BORROWER_BASE}/${id}/offer`;

export const offerApi = {
  /** Draw down an amount within the sanctioned ceiling. Stays SANCTIONED. */
  chooseAmount: (id: number, amountPaise: number) =>
    bff<ApplicationView>(`${OFFER(id)}/amount`, "POST", { amountPaise }),

  references: (id: number) => bff<ReferenceView[]>(`${OFFER(id)}/references`, "GET"),

  /** Both references at once — the backend rejects anything but two distinct, non-self contacts. */
  saveReferences: (id: number, references: ReferenceInput[]) =>
    bff<ReferenceView[]>(`${OFFER(id)}/references`, "POST", { references }),

  summary: (id: number) => bff<OfferSummaryView>(`${OFFER(id)}/summary`, "GET"),

  /** Renders the Key Fact Statement to S3 and returns a short-lived URL to read it. */
  sanctionLetter: (id: number) =>
    bff<SanctionLetterView>(`${OFFER(id)}/sanction-letter`, "POST"),

  /**
   * Start Aadhaar e-sign. `derived.url` is the provider page to send the borrower to;
   * `derived.fallback === true` means the provider is unavailable and they should draw instead.
   */
  esignInit: (id: number, payload: { successRedirectUrl: string; failureRedirectUrl: string }) =>
    bff<StepResult>(`${OFFER(id)}/esign/init`, "POST", payload),

  /** Poll the Aadhaar e-sign session once the provider redirects the borrower back. */
  esignStatus: (id: number) => bff<StepResult>(`${OFFER(id)}/esign/status`, "GET"),

  /**
   * Fallback: record a drawn signature. Without a signature by one route or the other the
   * application cannot reach disbursement.
   */
  esign: (id: number, payload: { signatureDataUrl: string; latitude?: number; longitude?: number; accuracyMeters?: number }) =>
    bff<StepResult>(`${OFFER(id)}/esign`, "POST", payload),

  disbursalAccount: (id: number) =>
    bff<DisbursalAccountView>(`${OFFER(id)}/disbursal-account`, "GET"),

  /**
   * The last step: confirms where the money lands and hands the file to disbursement. A penny drop
   * fires unless this exact account-number/IFSC pair already has a successful verification.
   */
  confirmDisbursalAccount: (
    id: number,
    payload: { accountNumber: string; ifsc: string; holderName?: string; bank?: string },
  ) => bff<ApplicationView>(`${OFFER(id)}/disbursal-account`, "POST", payload),
};

// ---------------------------------------------------------------------------
// Staff client — routes under /api/staff/*
// ---------------------------------------------------------------------------

const STAFF_BASE = "/api/staff/applications";
const STAFF_LOAN_BASE = "/api/staff/loan";

export const staffApi = {
  /** List applications by status, e.g. KYC_PENDING. */
  listByStatus: (status: ApplicationStatus) =>
    bff<ApplicationView[]>(`${STAFF_BASE}?status=${encodeURIComponent(status)}`, "GET"),

  /** The credit head's assignment queue (KYC_APPROVED + applied). */
  creditQueue: () => bff<ApplicationView[]>(`${STAFF_BASE}/credit-queue`, "GET"),

  /**
   * ACTIVE staff holding {@code role} for assignee pickers (default CREDIT_EXECUTIVE). Any staff
   * role may read it — deliberately NOT `adminApi.listStaff()`, which is ADMIN-only.
   */
  creditExecutives: (role = "CREDIT_EXECUTIVE") =>
    bff<StaffSummary[]>(
      `${STAFF_BASE}/credit-executives?role=${encodeURIComponent(role)}`,
      "GET",
    ),

  /** Application counts per status for the dashboard pipeline; statuses with no rows default to 0. */
  stats: () =>
    bff<Partial<Record<ApplicationStatus, number>>>(`${STAFF_BASE}/stats`, "GET"),

  /** ADMIN-only: every application (complete + incomplete) with full KYC detail + completeness. */
  listAllApplications: () => bff<AdminApplicationView[]>(`${STAFF_BASE}/all`, "GET"),

  /** TELECALLER/ADMIN: every pre-SANCTIONED application, enriched with completeness + staleness. */
  telecalling: () => bff<TelecallingView[]>(`${STAFF_BASE}/telecalling`, "GET"),

  /** ADMIN — the rejection register, optionally filtered by reason code. */
  rejections: (reason?: string) =>
    bff<RejectionView[]>(`${STAFF_BASE}/rejections${reason ? `?reason=${encodeURIComponent(reason)}` : ""}`, "GET"),

  get: (id: number) => bff<ApplicationView>(`${STAFF_BASE}/${id}`, "GET"),

  events: (id: number) => bff<EventView[]>(`${STAFF_BASE}/${id}/events`, "GET"),

  /** The two contacts the borrower named in the Phase-3 offer journey (V46). */
  references: (id: number) => bff<ReferenceView[]>(`${STAFF_BASE}/${id}/references`, "GET"),

  // --- maker-checker actions ---
  kycDecision: (id: number, decision: boolean, notes?: string) =>
    bff<ApplicationView>(`${STAFF_BASE}/${id}/kyc-decision`, "POST", { decision, notes }),

  /** Credit Head hands the file to an executive: KYC_PENDING → CREDIT_EXEC_PENDING. */
  assign: (id: number, executiveId: number) =>
    bff<ApplicationView>(`${STAFF_BASE}/${id}/assign`, "POST", { executiveId }),

  /** "Accept lead" — the Credit Executive's FINAL decision (V45). No Head counter-approval. */
  sanction: (
    id: number,
    payload: { sanctionedAmountPaise: number; salaryCreditDay: number; remarks?: string },
  ) => bff<ApplicationView>(`${STAFF_BASE}/${id}/sanction`, "POST", payload),

  /** "Reject lead" — the borrower is told nothing; the remarks go to the staff-only register. */
  rejectLead: (id: number, notes?: string) =>
    bff<ApplicationView>(`${STAFF_BASE}/${id}/reject-lead`, "POST", { notes }),

  /** "Mark lead pending" — a staff-only tag; the lead keeps its status and its place in the queue. */
  markPending: (id: number, notes: string) =>
    bff<ApplicationView>(`${STAFF_BASE}/${id}/mark-pending`, "POST", { notes }),

  /** Own decisions by default; a Head may pass a team member's id, ADMIN anyone's. */
  decisions: (staffId?: number) =>
    bff<DecisionView[]>(`/api/staff/decisions${staffId ? `?staffId=${staffId}` : ""}`, "GET"),

  /** Staff whose decision history the caller may open (a Head's team; everyone for ADMIN). */
  inspectableStaff: () => bff<StaffSummary[]>(`/api/staff/decisions/inspectable`, "GET"),

  disbursementDecision: (id: number, decision: boolean, txnRef?: string, notes?: string) =>
    bff<ApplicationView>(`${STAFF_BASE}/${id}/disbursement-decision`, "POST", { decision, txnRef, notes }),


  /** Cancel a pre-disbursement application (staff/admin). Backend rejects once past disbursement. */
  cancel: (id: number, notes?: string) =>
    bff<ApplicationView>(`${STAFF_BASE}/${id}/cancel`, "POST", { notes }),

  // --- loan view (staff) ---
  loan: (loanId: number) => bff<LoanView>(`${STAFF_LOAN_BASE}/${loanId}`, "GET"),

  outstanding: (loanId: number, asOf: string) =>
    bff<OutstandingView>(
      `${STAFF_LOAN_BASE}/${loanId}/outstanding?asOf=${encodeURIComponent(asOf)}`,
      "GET",
    ),

  // --- repayment verification (accountant maker-checker) ---
  /** Repayments awaiting proof verification, across all loans (accountant queue). */
  pendingRepayments: () => bff<PaymentView[]>(`${STAFF_LOAN_BASE}/pending-repayments`, "GET"),

  /** Company-wide transactions ledger (disbursals + repayments); optional borrower/direction filter. */
  transactions: (q?: string, direction?: TransactionDirection, range?: { from?: string; to?: string }) => {
    const params = new URLSearchParams();
    if (q) params.set("q", q);
    if (direction) params.set("direction", direction);
    if (range?.from) params.set("from", range.from);
    if (range?.to) params.set("to", range.to);
    const qs = params.toString();
    return bff<TransactionView[]>(`${STAFF_LOAN_BASE}/transactions${qs ? `?${qs}` : ""}`, "GET");
  },

  /** Repayments recorded against one loan. */
  repayments: (loanId: number) => bff<PaymentView[]>(`${STAFF_LOAN_BASE}/${loanId}/repayments`, "GET"),

  /** Confirm proof for a payment → reduces outstanding, closes the loan/application at zero. */
  verifyRepayment: (loanId: number, paymentId: number) =>
    bff<PaymentView>(`${STAFF_LOAN_BASE}/${loanId}/repayments/${paymentId}/verify`, "POST"),

  /** Reject a payment (proof didn't match the transfer); the balance is unchanged. Reason is required. */
  rejectRepayment: (loanId: number, paymentId: number, body: { reason: RejectionReasonCode; note?: string }) =>
    bff<PaymentView>(`${STAFF_LOAN_BASE}/${loanId}/repayments/${paymentId}/reject`, "POST", body),

  // --- customer review (any reviewing role) ---
  /** The customer's KYC details (PAN masked). */
  getProfile: (id: number) => bff<ProfileView>(`${STAFF_BASE}/${id}/profile`, "GET"),

  /** The application's uploaded documents (metadata). */
  documents: (id: number) => bff<DocumentView[]>(`${STAFF_BASE}/${id}/documents`, "GET"),

  /** One document's bytes (base64) for view/download (legacy inline storage). */
  document: (id: number, docId: number) =>
    bff<DocumentContent>(`${STAFF_BASE}/${id}/documents/${docId}`, "GET"),

  /** A presigned GET URL for an S3-backed document (view/download in a new tab). */
  documentUrl: (id: number, docId: number) =>
    bff<DocumentUrlView>(`${STAFF_BASE}/${id}/documents/${docId}/url`, "GET"),

  /** The application's verification step results (PAN/email/address/salary/…). */
  verifications: (id: number) => bff<StepResult[]>(`${STAFF_BASE}/${id}/verifications`, "GET"),
  /** Required-step completion snapshot for the progress tracker (Phase 3.2). */
  verificationProgress: (id: number) =>
    bff<VerificationProgress>(`${STAFF_BASE}/${id}/verification-progress`, "GET"),
  /** Staff manual override of a verification step (KYC approver / admin): PASS or FAIL with a note. */
  manualVerificationDecision: (id: number, checkType: string, decision: boolean, notes?: string) =>
    bff<StepResult>(`${STAFF_BASE}/${id}/verifications/${checkType}/decision`, "POST", { decision, notes }),
  retryVerification: async (id: number, checkType: string, input: Record<string, unknown>) => {
    let timer: ReturnType<typeof setTimeout> | undefined;
    try {
      return await Promise.race([
        bff<StepResult>(`${STAFF_BASE}/${id}/verifications/${checkType}/retry`, "POST", input),
        new Promise<never>((_, reject) => { timer = setTimeout(() => reject(new Error("Verification retry timed out after 30 seconds.")), 30_000); }),
      ]);
    } finally { if (timer) clearTimeout(timer); }
  },
  /** Pending-API dashboard: cross-application verification overview + tallies (Phase 3.3). */
  verificationOverview: (filters?: { status?: string; checkType?: string; q?: string }) => {
    const params = new URLSearchParams();
    if (filters?.status) params.set("status", filters.status);
    if (filters?.checkType) params.set("checkType", filters.checkType);
    if (filters?.q) params.set("q", filters.q);
    const qs = params.toString();
    return bff<VerificationOverview>(`${STAFF_BASE}/verifications/overview${qs ? `?${qs}` : ""}`, "GET");
  },
  /** KYC approver / admin nudges the borrower with their pending verification steps (Phase 3.4). */
  sendReminder: (id: number) => bff<ReminderResult>(`${STAFF_BASE}/${id}/send-reminder`, "POST"),

  /** Staff-only credit brief: 1–5★ rating + categorized bureau facts + the CREDIT_BRIEF PDF doc id. */
  creditBrief: (id: number) => bff<CreditBriefView>(`${STAFF_BASE}/${id}/credit-brief`, "GET"),

  /** ADMIN uploads a document for an application (base64 body, matching the borrower upload path). */
  uploadDocument: (
    id: number,
    doc: { docType: string; fileName: string; contentType?: string; dataBase64: string },
  ) => bff<DocumentView>(`${STAFF_BASE}/${id}/documents`, "POST", doc),

  /** ADMIN deletes a document (the delete half of the CRM replace flow). */
  deleteDocument: (id: number, docId: number) =>
    bff<null>(`${STAFF_BASE}/${id}/documents/${docId}`, "DELETE"),
};

// ---------------------------------------------------------------------------
// Customers (borrower-centric) — routes under /api/staff/customers/*
// ---------------------------------------------------------------------------

const CUSTOMERS_BASE = "/api/staff/customers";

export const customersApi = {
  /** All customers, optionally filtered by name / customer id. */
  list: (q?: string) =>
    bff<CustomerSummary[]>(`${CUSTOMERS_BASE}${q ? `?q=${encodeURIComponent(q)}` : ""}`, "GET"),

  /** One customer's full history (profile + applications + loans + payments). */
  get: (customerId: number) => bff<CustomerDetail>(`${CUSTOMERS_BASE}/${customerId}`, "GET"),

  /** ADMIN corrects a customer's KYC / salary data (non-identity fields); changes are audited. */
  updateProfile: (customerId: number, body: UpdateCustomerInput) =>
    bff<ProfileView>(`${CUSTOMERS_BASE}/${customerId}/profile`, "PUT", body),

  /** One customer's audited profile/salary change history (newest first). */
  changes: (customerId: number) =>
    bff<ProfileChangeView[]>(`${CUSTOMERS_BASE}/${customerId}/changes`, "GET"),

  /** Unified activity timeline: lifecycle + re-verify + profile edits + remarks (newest first). */
  activity: (customerId: number) =>
    bff<ActivityEntry[]>(`${CUSTOMERS_BASE}/${customerId}/activity`, "GET"),

  /** Staff remarks on a customer. */
  remarks: (customerId: number) =>
    bff<RemarkView[]>(`${CUSTOMERS_BASE}/${customerId}/remarks`, "GET"),

  /** Add a staff remark to a customer. */
  addRemark: (customerId: number, body: string) =>
    bff<RemarkView>(`${CUSTOMERS_BASE}/${customerId}/remarks`, "POST", { body }),

  /** Assign (or clear) the staff owner. staffId null → unallocate. */
  assignOwner: (customerId: number, staffId: number | null) =>
    bff<CustomerDetail>(`${CUSTOMERS_BASE}/${customerId}/owner`, "POST", { staffId }),

  /** Staff call logs on a customer. */
  callLogs: (customerId: number) =>
    bff<CallLogView[]>(`${CUSTOMERS_BASE}/${customerId}/call-logs`, "GET"),

  /** Add a staff call log to a customer. */
  addCallLog: (customerId: number, body: AddCallLogInput) =>
    bff<CallLogView>(`${CUSTOMERS_BASE}/${customerId}/call-logs`, "POST", body),

  /** ADMIN — permanently delete a customer and ALL their data (irreversible cascade). */
  remove: (customerId: number) =>
    bff<CustomerDeletionResult>(`${CUSTOMERS_BASE}/${customerId}`, "DELETE"),

  /** Documents across every one of this customer's applications, newest application first. */
  documents: (customerId: number) =>
    bff<ApplicationDocumentGroup[]>(`${CUSTOMERS_BASE}/${customerId}/documents`, "GET"),
};

// ---------------------------------------------------------------------------
// Telecaller leads — routes under /api/staff/leads/*
// ---------------------------------------------------------------------------

export type LeadCallStatus =
  | "NOT_CALLED"
  | "CALLED"
  | "CALLBACK"
  | "NO_ANSWER"
  | "NOT_INTERESTED"
  | "WRONG_NUMBER"
  | "CONNECTED";

export type LeadSource = "DSA" | "REFERRAL" | "WALK_IN" | "OTHER";

export interface LeadView {
  id: number;
  name: string;
  mobile: string;
  email: string | null;
  city: string | null;
  employer: string | null;
  monthlySalaryPaise: number | null;
  loanAmountInterestedPaise: number | null;
  source: LeadSource | null;
  sourceDetail: string | null;
  callStatus: LeadCallStatus;
  qualityRating: number | null;
  notes: string | null;
  remarks: string | null;
  createdByStaffId: number;
  createdByStaffName: string | null;
  createdAt: string;
  updatedAt: string | null;
}

export interface CreateLeadInput {
  name: string;
  mobile: string;
  email?: string;
  city?: string;
  employer?: string;
  monthlySalaryPaise?: number;
  loanAmountInterestedPaise?: number;
  source?: LeadSource;
  sourceDetail?: string;
  notes?: string;
}

export interface UpdateLeadInput {
  name?: string;
  mobile?: string;
  email?: string;
  city?: string;
  employer?: string;
  monthlySalaryPaise?: number;
  loanAmountInterestedPaise?: number;
  source?: LeadSource;
  sourceDetail?: string;
  notes?: string;
}

export interface DispositionInput {
  callStatus: LeadCallStatus;
  qualityRating?: number | null;
  remarks?: string;
}

export interface LeadListParams {
  q?: string;
  callStatus?: LeadCallStatus;
  source?: LeadSource;
  createdBy?: number;
  from?: string;
  to?: string;
  minRating?: number;
  maxRating?: number;
}

export interface LeadStats {
  total: number;
  byCallStatus: { status: string; count: number }[];
  bySource: { source: string; count: number }[];
  byQualityRating: { rating: number; count: number }[];
  unratedCount: number;
  byDay: { date: string; created: number; called: number }[];
  byStaff: { staffId: number; staffName: string | null; count: number }[];
  avgQualityRating: number | null;
}

const LEADS_BASE = "/api/staff/leads";

function leadsQuery(params?: LeadListParams): string {
  if (!params) return "";
  const sp = new URLSearchParams();
  if (params.q) sp.set("q", params.q);
  if (params.callStatus) sp.set("callStatus", params.callStatus);
  if (params.source) sp.set("source", params.source);
  if (params.createdBy != null) sp.set("createdBy", String(params.createdBy));
  if (params.from) sp.set("from", params.from);
  if (params.to) sp.set("to", params.to);
  if (params.minRating != null) sp.set("minRating", String(params.minRating));
  if (params.maxRating != null) sp.set("maxRating", String(params.maxRating));
  const s = sp.toString();
  return s ? `?${s}` : "";
}

export const leadsApi = {
  list: (params?: LeadListParams) =>
    bff<LeadView[]>(`${LEADS_BASE}${leadsQuery(params)}`, "GET"),

  get: (id: number) => bff<LeadView>(`${LEADS_BASE}/${id}`, "GET"),

  create: (body: CreateLeadInput) => bff<LeadView>(LEADS_BASE, "POST", body),

  update: (id: number, body: UpdateLeadInput) =>
    bff<LeadView>(`${LEADS_BASE}/${id}`, "PUT", body),

  disposition: (id: number, body: DispositionInput) =>
    bff<LeadView>(`${LEADS_BASE}/${id}/disposition`, "PUT", body),

  stats: (params?: { from?: string; to?: string; createdBy?: number }) => {
    const sp = new URLSearchParams();
    if (params?.from) sp.set("from", params.from);
    if (params?.to) sp.set("to", params.to);
    if (params?.createdBy != null) sp.set("createdBy", String(params.createdBy));
    const s = sp.toString();
    return bff<LeadStats>(`${LEADS_BASE}/stats${s ? `?${s}` : ""}`, "GET");
  },
};

/** Summary returned by a cascade customer delete. */
export interface CustomerDeletionResult {
  customerId: number;
  applications: number;
  loans: number;
  totalRows: number;
}

// ---------------------------------------------------------------------------
// Dashboard analytics — routes under /api/staff/dashboard/*
// ---------------------------------------------------------------------------

/** One day's counts in the dashboard trend window. */
export interface TrendPoint {
  date: string;
  applications: number;
  disbursed: number;
  repaid: number;
}

/** Trend window + this-week-vs-last-week deltas — mirrors backend TrendResponse. */
export interface TrendResponse {
  points: TrendPoint[];
  applicationsThisWeek: number;
  applicationsLastWeek: number;
  disbursedThisWeek: number;
  disbursedLastWeek: number;
  repaidThisWeek: number;
  repaidLastWeek: number;
}

export const dashboardApi = {
  /** Daily applications / disbursals / repayments over the last `days` (default 30). */
  trends: (days = 30) =>
    bff<TrendResponse>(`/api/staff/dashboard/trends?days=${days}`, "GET"),
};

// ---------------------------------------------------------------------------
// Admin (IAM) — staff users, invites, fraud blocklist
// ---------------------------------------------------------------------------

export type StaffRoleName =
  | "CREDIT_EXECUTIVE"
  | "CREDIT_HEAD"
  | "DISBURSEMENT_HEAD"
  | "ACCOUNTANT"
  | "COLLECTION_HEAD"
  | "COLLECTION_EXECUTIVE"
  | "TELECALLER"
  | "ADMIN"
  | "DEVELOPER";

export type StaffStatus = "INVITED" | "ACTIVE" | "DISABLED";

export type BlocklistType = "PAN" | "AADHAAR_REF" | "PHONE" | "DEVICE" | "BANK_ACCOUNT";

export interface StaffResponse {
  id: number;
  email: string;
  name: string;
  role: StaffRoleName;
  status: StaffStatus;
  /** Self-editable org fields (Phase 2.2). */
  department?: string | null;
  designation?: string | null;
}

export interface InviteResponse {
  id: number;
  email: string;
  role: StaffRoleName;
  token: string;
  expiresAt: string;
}

export interface BlocklistResponse {
  id: number;
  type: BlocklistType;
  value: string;
  reason: string | null;
  active: boolean;
}

/** One company expense (ADMIN-tracked spend). Mirrors backend ExpenseResponse. Money is paise. */
export interface ExpenseResponse {
  id: number;
  description: string;
  amountPaise: number;
  paidTo: string;
  notes: string | null;
  /** ISO yyyy-mm-dd. */
  expenseDate: string;
  createdAt: string | null;
  /** Name of the admin who recorded it. */
  addedBy: string | null;
  /** Short-lived presigned URL for an uploaded receipt/attachment, or null when none. */
  receiptUrl: string | null;
}

const ADMIN_STAFF_BASE = "/api/staff/users";
const ADMIN_INVITES_BASE = "/api/staff/invites";
const ADMIN_BLOCKLIST_BASE = "/api/admin/blocklist";
const ADMIN_EXPENSES_BASE = "/api/admin/expenses";

export const adminApi = {
  // --- staff users ---
  listStaff: () => bff<StaffResponse[]>(ADMIN_STAFF_BASE, "GET"),
  getStaff: (id: number) => bff<StaffResponse>(`${ADMIN_STAFF_BASE}/${id}`, "GET"),
  /** The calling staffer's own account (any staff role). */
  myProfile: () => bff<StaffResponse>(`${ADMIN_STAFF_BASE}/me`, "GET"),
  /** Self-edit own display name + department/designation (not role/status). */
  updateMyProfile: (payload: { name?: string; department?: string | null; designation?: string | null }) =>
    bff<StaffResponse>(`${ADMIN_STAFF_BASE}/me`, "PUT", payload),
  /** Create a staff account with an email + password so they can sign in (ADMIN only). */
  createStaff: (payload: { email: string; name: string; role: StaffRoleName; password: string }) =>
    bff<StaffResponse>(ADMIN_STAFF_BASE, "POST", payload),
  updateStaff: (id: number, payload: { role: StaffRoleName; status: StaffStatus }) =>
    bff<StaffResponse>(`${ADMIN_STAFF_BASE}/${id}`, "PUT", payload),
  disableStaff: (id: number) => bff<null>(`${ADMIN_STAFF_BASE}/${id}`, "DELETE"),

  // --- invites ---
  listInvites: () => bff<InviteResponse[]>(ADMIN_INVITES_BASE, "GET"),
  createInvite: (payload: { email: string; role: StaffRoleName }) =>
    bff<InviteResponse>(ADMIN_INVITES_BASE, "POST", payload),
  acceptInvite: (payload: { token: string; name: string }) =>
    bff<StaffResponse>(`${ADMIN_INVITES_BASE}/accept`, "POST", payload),

  // --- fraud blocklist ---
  listBlocklist: () => bff<BlocklistResponse[]>(ADMIN_BLOCKLIST_BASE, "GET"),
  addBlocklist: (payload: { type: BlocklistType; value: string; reason?: string }) =>
    bff<BlocklistResponse>(ADMIN_BLOCKLIST_BASE, "POST", payload),
  removeBlocklist: (id: number) => bff<null>(`${ADMIN_BLOCKLIST_BASE}/${id}`, "DELETE"),

  // --- company expenses (ADMIN only) ---
  listExpenses: () => bff<ExpenseResponse[]>(ADMIN_EXPENSES_BASE, "GET"),
  addExpense: (payload: {
    description: string;
    amountPaise: number;
    paidTo: string;
    notes?: string;
    expenseDate?: string;
    /** S3 key of an already-uploaded receipt/attachment (optional). */
    receiptObjectKey?: string;
  }) => bff<ExpenseResponse>(ADMIN_EXPENSES_BASE, "POST", payload),
  removeExpense: (id: number) => bff<null>(`${ADMIN_EXPENSES_BASE}/${id}`, "DELETE"),
};

// ---------------------------------------------------------------------------
// Payment settings (admin-managed company payee) — routes under /api/payment-settings
// ---------------------------------------------------------------------------

export const paymentSettingsApi = {
  /** The current payee (borrower repay + staff admin read). */
  get: () => bff<PaymentSettings>("/api/payment-settings", "GET"),

  /** ADMIN edit of the payee fields / uploaded asset keys. */
  update: (body: UpdatePaymentSettingsInput) =>
    bff<PaymentSettings>("/api/payment-settings", "PUT", body),
};

// ---------------------------------------------------------------------------
// Feature flags (dev-controlled, read-only) — route under /api/feature-flags
// ---------------------------------------------------------------------------

/** Dev-controlled feature flags as { key: enabled }. Changed only via SQL; the UI just reads them. */
export type FeatureFlags = Record<string, boolean>;

export const featureFlagsApi = {
  /** The current flag states, so the UI can hide a disabled feature (e.g. referral). */
  get: () => bff<FeatureFlags>("/api/feature-flags", "GET"),
};

// ---------------------------------------------------------------------------
// Referral (refer-a-friend) — borrower /api/borrower/referral/*, staff /api/staff/referral/*
// ---------------------------------------------------------------------------

/** The borrower's own referral panel: their code, reward, share copy + earnings roll-up. */
export interface MyReferral {
  enabled: boolean;
  code: string;
  rewardPaise: number;
  rewardRupees: number;
  shareMessage: string;
  /** Friends whose first loan was disbursed (the referral qualified). */
  referredQualifiedCount: number;
  /** Total ₹ already credited to me (paise). */
  totalEarnedPaise: number;
  /** Total ₹ owed to me but not yet paid (paise). */
  pendingPaise: number;
}

/** Outcome of applying a code at signup (the happy path; guard failures throw ApplicationApiError). */
export interface ApplyReferralResult {
  accepted: boolean;
  message: string;
  referrerName: string | null;
  rewardPaise: number;
}

/** Lenient code preview for live signup feedback. */
export interface ValidateReferralResult {
  valid: boolean;
  referrerName: string | null;
  rewardPaise: number;
  message: string;
}

export type ReferralBeneficiaryRole = "REFERRER" | "REFERRED";
export type ReferralPayoutStatus = "PENDING" | "PAID";

/** One ₹-reward payout row (Disbursement-Head approval dashboard + referral-expense view). */
export interface ReferralPayout {
  id: number;
  referralId: number;
  beneficiaryCustomerId: number;
  beneficiaryName: string | null;
  beneficiaryRole: ReferralBeneficiaryRole;
  counterpartyCustomerId: number | null;
  counterpartyName: string | null;
  amountPaise: number;
  status: ReferralPayoutStatus;
  txnRef: string | null;
  paidAt: string | null;
  paidBy: string | null;
  qualifyingLoanId: number | null;
  createdAt: string | null;
}

/** Totals for the referral-expense dashboard. */
export interface ReferralExpenseSummary {
  pendingCount: number;
  pendingPaise: number;
  paidCount: number;
  paidPaise: number;
  totalCount: number;
  totalPaise: number;
}

const BORROWER_REFERRAL_BASE = "/api/borrower/referral";

/** Borrower-facing referral client. */
export const referralApi = {
  /** The caller's own code + reward + earnings (code minted lazily on first read). */
  me: () => bff<MyReferral>(`${BORROWER_REFERRAL_BASE}/me`, "GET"),

  /** Apply a referral code to the calling borrower (best-effort from the signup UI). */
  apply: (code: string) =>
    bff<ApplyReferralResult>(`${BORROWER_REFERRAL_BASE}/apply`, "POST", { code }),

  /** Preview a code for live signup feedback (never throws on a bad code; returns valid=false). */
  validate: (code: string) =>
    bff<ValidateReferralResult>(
      `${BORROWER_REFERRAL_BASE}/validate?code=${encodeURIComponent(code)}`,
      "GET",
    ),
};

const STAFF_REFERRAL_BASE = "/api/staff/referral";

/** Disbursement-Head / Admin referral payout client. */
export const staffReferralApi = {
  /** Payout queue / expense list. `status` PENDING|PAID filters; omitted → all (newest first). */
  payouts: (status?: ReferralPayoutStatus) =>
    bff<ReferralPayout[]>(`${STAFF_REFERRAL_BASE}/payouts${status ? `?status=${status}` : ""}`, "GET"),

  /** Mark a payout paid, logging the bank/UPI transaction id. */
  pay: (id: number, txnRef: string) =>
    bff<ReferralPayout>(`${STAFF_REFERRAL_BASE}/payouts/${id}/pay`, "POST", { txnRef }),

  /** Totals for the referral-expense dashboard. */
  expenses: () => bff<ReferralExpenseSummary>(`${STAFF_REFERRAL_BASE}/expenses`, "GET"),
};

// ---------------------------------------------------------------------------
// Storage (presigned uploads) — routes under /api/storage
// ---------------------------------------------------------------------------

/** Result of asking for a presigned PUT URL (raw — the storage endpoint is NOT envelope-wrapped). */
export interface PresignUpload {
  key: string;
  url: string;
  method: string;
  expiresInSeconds: number;
}

export const storageApi = {
  /** Ask the backend for a presigned PUT URL for a categorised upload. */
  presignUpload: async (body: {
    category: string;
    filename: string;
    contentType: string;
  }): Promise<PresignUpload> => {
    const res = await fetch("/api/storage/presign-upload", {
      method: "POST",
      headers: { "Content-Type": "application/json", Accept: "application/json" },
      body: JSON.stringify(body),
      credentials: "same-origin",
      cache: "no-store",
    });
    const text = await res.text();
    if (!res.ok) {
      // BFF/backend error paths return the ApiResponse envelope.
      let code = `HTTP_${res.status}`;
      let message = `Upload could not be prepared (status ${res.status}).`;
      try {
        const env = JSON.parse(text) as ApiResponse<unknown>;
        code = env.error?.code ?? code;
        message = env.error?.message ?? env.message ?? message;
      } catch {
        /* non-JSON error body */
      }
      throw new ApplicationApiError(message, code, res.status);
    }
    return JSON.parse(text) as PresignUpload;
  },

  /** PUT the file bytes straight to the presigned S3 URL (never through the BFF). */
  putToPresignedUrl: (url: string, file: File): Promise<void> =>
    putToPresignedUrl(url, file, file.type || "application/octet-stream"),
};

// ---------------------------------------------------------------------------
// Collections — cases, interactions, settlements (maker-checker), DPD helper
// ---------------------------------------------------------------------------

export type DpdBucket = "UPCOMING" | "T0_T7" | "T8_T30" | "T30_T60" | "T60_T90" | "T90_PLUS";

/** Real loan + borrower snapshot surfaced to collections (mirrors backend LoanSummary). */
export interface LoanSummary {
  loanId: number;
  customerId: number | null;
  applicationId: number | null;
  status: string | null;
  principalPaise: number | null;
  netDisbursedPaise: number | null;
  totalRepayablePaise: number | null;
  outstandingPaise: number | null;
  disbursedOn: string | null;
  dueDate: string | null;
  borrowerName: string | null;
  panMasked: string | null;
  employer: string | null;
  employmentStatus: string | null;
  monthlySalaryPaise: number | null;
  salaryBank: string | null;
}

/** Staff snapshot for assignee pickers / name rendering (mirrors backend StaffSummary). */
export interface StaffSummary {
  id: number;
  name: string;
  role: string;
  active: boolean;
}

/** A row in the collections worklist (case + live DPD + key loan/borrower fields). */
export interface CaseView {
  id: string; // case UUID
  loanId: number;
  assignedOfficerId: number | null;
  assignedOfficerName: string | null;
  createdAt: string;
  dpd: number;
  bucket: DpdBucket;
  loanStatus: string | null;
  borrowerName: string | null;
  outstandingPaise: number | null;
  dueDate: string | null;
}

/** Full case detail: case + live DPD + the complete loan/borrower snapshot. */
export interface CaseDetailView {
  id: string;
  loanId: number;
  assignedOfficerId: number | null;
  assignedOfficerName: string | null;
  createdAt: string;
  dpd: number;
  bucket: DpdBucket;
  loan: LoanSummary | null;
}

export interface InteractionView {
  id: string;
  collectionCaseId: string;
  type: string;
  outcome: string;
  promiseToPayDate: string | null;
  proofRef: string | null;
  loggedAt: string;
}

export type SettlementStatusName = "PROPOSED" | "APPROVED" | "REJECTED";

export interface SettlementView {
  id: string;
  collectionCaseId: string;
  settlementAmountPaise: number | null;
  proposedBy: number | null;
  proposedByName: string | null;
  approvedBy: number | null;
  approvedByName: string | null;
  rejectedBy: number | null;
  rejectedByName: string | null;
  status: SettlementStatusName;
  createdAt: string;
  approvedAt: string | null;
  rejectedAt: string | null;
}

/**
 * What a collections officer is recording (V47; revamp.md decision 43). A settlement writes off
 * part of the debt, so it takes an extra hop through the Collection Head; part and full payments go
 * straight to the Accountant.
 */
export type CollectionPaymentKind = "PART_PAYMENT" | "FULL_PAYMENT" | "SETTLEMENT";

export const COLLECTION_PAYMENT_KINDS: Array<{ value: CollectionPaymentKind; label: string; hint: string }> = [
  { value: "PART_PAYMENT", label: "Part payment", hint: "Some of the balance; the rest is still owed." },
  { value: "FULL_PAYMENT", label: "Full payment", hint: "Clears the balance outright." },
  {
    value: "SETTLEMENT",
    label: "Settlement",
    hint: "Full & final for less than the balance — needs a settlement open on the case, and the Collection Head's approval.",
  },
];

export type CollectionPaymentStatusName =
  | "PENDING_HEAD"
  | "PENDING_ACCOUNTANT"
  | "VALIDATED"
  | "REJECTED";

/** A collections payment and its approval chain (mirrors backend CollectionPaymentView). */
export interface CollectionPaymentView {
  id: string;
  collectionCaseId: string;
  loanId: number | null;
  kind: CollectionPaymentKind;
  amountPaise: number | null;
  paidOn: string | null;
  txnRef: string | null;
  proofRef: string | null;
  settlementId: string | null;
  status: CollectionPaymentStatusName;
  raisedBy: number | null;
  raisedByName: string | null;
  raisedAt: string;
  validatedBy: number | null;
  validatedByName: string | null;
  validatedAt: string | null;
  remarks: string | null;
  /** The loan-ledger payment the Accountant's validation minted; null until validated. */
  ledgerPaymentId: number | null;
  borrowerName: string | null;
}

export interface DpdView {
  dueDate: string;
  asOf: string;
  dpd: number;
  bucket: DpdBucket;
}

const COLLECTIONS_BASE = "/api/staff/collections";

export const collectionsApi = {
  listCases: () => bff<CaseView[]>(`${COLLECTIONS_BASE}/cases`, "GET"),
  getCase: (caseId: string) => bff<CaseDetailView>(`${COLLECTIONS_BASE}/cases/${caseId}`, "GET"),
  openCase: (loanId: number) => bff<CaseDetailView>(`${COLLECTIONS_BASE}/cases`, "POST", { loanId }),
  assignOfficer: (caseId: string, officerId: number) =>
    bff<CaseDetailView>(`${COLLECTIONS_BASE}/cases/${caseId}/assign`, "POST", { officerId }),

  // NOTE: the backend still exposes GET /api/collections/loans ("collectible loans"), but nothing
  // calls it any more: opening a case is implicit in assigning an officer from the awaiting-repayment
  // rows on /staff/applications, so the separate "pick a loan, then open a case" list was dropped.

  /** ACTIVE collections officers, for the assignee picker. */
  listOfficers: () => bff<StaffSummary[]>(`${COLLECTIONS_BASE}/officers`, "GET"),

  listInteractions: (caseId: string) =>
    bff<InteractionView[]>(`${COLLECTIONS_BASE}/cases/${caseId}/interactions`, "GET"),
  logInteraction: (
    caseId: string,
    payload: { type: string; outcome: string; promiseToPayDate?: string; proofRef?: string },
  ) => bff<InteractionView>(`${COLLECTIONS_BASE}/cases/${caseId}/interactions`, "POST", payload),

  proposeSettlement: (caseId: string, settlementAmountPaise: number) =>
    bff<SettlementView>(`${COLLECTIONS_BASE}/cases/${caseId}/settlements`, "POST", { settlementAmountPaise }),
  listSettlements: () => bff<SettlementView[]>(`${COLLECTIONS_BASE}/settlements`, "GET"),
  approveSettlement: (settlementId: string) =>
    bff<SettlementView>(`${COLLECTIONS_BASE}/settlements/${settlementId}/approve`, "POST"),
  rejectSettlement: (settlementId: string) =>
    bff<SettlementView>(`${COLLECTIONS_BASE}/settlements/${settlementId}/reject`, "POST"),

  // ---- collections payments (V47) ----
  // The officer records, the Accountant books. Nothing here moves the borrower's balance except
  // `validatePayment(id, true)` — that is the whole shape of the rule (decision 44).

  raisePayment: (
    caseId: string,
    payload: {
      kind: CollectionPaymentKind;
      amountPaise: number;
      paidOn?: string;
      txnRef?: string;
      proofRef?: string;
      settlementId?: string;
    },
  ) => bff<CollectionPaymentView>(`${COLLECTIONS_BASE}/cases/${caseId}/payments`, "POST", payload),

  listCasePayments: (caseId: string) =>
    bff<CollectionPaymentView[]>(`${COLLECTIONS_BASE}/cases/${caseId}/payments`, "GET"),

  /** Omit `status` for the full register; pass one to read a specific desk's queue. */
  listPayments: (status?: CollectionPaymentStatusName) =>
    bff<CollectionPaymentView[]>(
      `${COLLECTIONS_BASE}/payments${status ? `?status=${status}` : ""}`,
      "GET",
    ),

  headApprovePayment: (paymentId: string) =>
    bff<CollectionPaymentView>(`${COLLECTIONS_BASE}/payments/${paymentId}/head-approve`, "POST"),

  validatePayment: (paymentId: string, accept: boolean, remarks?: string) =>
    bff<CollectionPaymentView>(`${COLLECTIONS_BASE}/payments/${paymentId}/validate`, "POST", {
      accept,
      remarks,
    }),

  dpd: (dueDate: string, asOf?: string) =>
    bff<DpdView>(
      `${COLLECTIONS_BASE}/dpd?dueDate=${encodeURIComponent(dueDate)}${asOf ? `&asOf=${encodeURIComponent(asOf)}` : ""}`,
      "GET",
    ),
};

// ---------------------------------------------------------------------------
// Notifications — the recipient's in-app inbox (borrower + staff share the shape)
// ---------------------------------------------------------------------------

/** One in-app notification (mirrors backend NotificationView). */
export interface NotificationView {
  id: number;
  /** Stable enum, e.g. "KYC_APPROVED" — drives the icon/intent. */
  type: string;
  /** Coarse grouping, e.g. "KYC" | "CREDIT" | "DISBURSEMENT" | "REPAYMENT" | "COLLECTIONS" | "STAFF_IAM" | "SECURITY" | "SYSTEM". */
  category: string;
  title: string;
  body: string;
  read: boolean;
  /** Routing ids for deep-linking on click (any may be null). */
  applicationId: number | null;
  loanId: number | null;
  caseId: string | null;
  createdAt: string;
}

/** Which session/cookie a notifications client speaks for. */
export type NotificationScope = "borrower" | "staff";

/**
 * Build a notifications client bound to one BFF namespace. The backend endpoint is the
 * same (`/api/notifications`); only the proxy prefix (and thus the cookie) differs.
 */
function makeNotificationsApi(base: string) {
  return {
    /** The caller's notifications, newest-first. */
    list: (page = 0, size = 20) =>
      bff<NotificationView[]>(`${base}?page=${page}&size=${size}`, "GET"),

    /** Unread in-app count for the bell badge. */
    unreadCount: () => bff<number>(`${base}/unread-count`, "GET"),

    /** Mark one read; resolves to the fresh unread count. */
    markRead: (id: number) => bff<number>(`${base}/${id}/read`, "POST"),

    /** Mark all read; resolves to the fresh unread count (0). */
    markAllRead: () => bff<number>(`${base}/read-all`, "POST"),
  };
}

export type NotificationsApi = ReturnType<typeof makeNotificationsApi>;

export const borrowerNotificationsApi = makeNotificationsApi("/api/borrower/notifications");
export const staffNotificationsApi = makeNotificationsApi("/api/staff/notifications");

export type ProviderApiField = { key: string; label: string; type: string; required: boolean };
export type ProviderApiCatalogItem = { operation: string; providers: string[]; fields: ProviderApiField[] };
export type ProviderApiExecution = { id: number; operation: string; provider: string; status: string; durationMs: number; request: Record<string, unknown>; response: unknown; error?: string | null; createdAt: string };
export const providerApi = {
  catalog: () => bff<ProviderApiCatalogItem[]>("/api/staff/provider-apis/catalog", "GET"),
  history: () => bff<ProviderApiExecution[]>("/api/staff/provider-apis/history", "GET"),
  execute: (operation: string, provider: string, input: Record<string, unknown>) =>
    bff<ProviderApiExecution>("/api/staff/provider-apis/execute", "POST", { operation, provider, input }),
};

/** Pick the right notifications client for a scope. */
export function notificationsApiFor(scope: NotificationScope): NotificationsApi {
  return scope === "staff" ? staffNotificationsApi : borrowerNotificationsApi;
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

/** ₹ amount (e.g. 5000) -> integer paise (500000). */
export function rupeesToPaise(rupees: number): number {
  return Math.round(rupees * 100);
}

/** Integer paise -> ₹ string, e.g. 500000 -> "₹5,000". */
export function paiseToINR(paise: number | null | undefined): string {
  if (paise == null) return "—";
  return new Intl.NumberFormat("en-IN", {
    style: "currency",
    currency: "INR",
    maximumFractionDigits: 0,
  }).format(paise / 100);
}

/** Human label for an application status. */
export function statusLabel(status: ApplicationStatus): string {
  return status
    .toLowerCase()
    .split("_")
    .map((w) => w.charAt(0).toUpperCase() + w.slice(1))
    .join(" ");
}


/** Read a browser File into base64 (no data: prefix), for the document-upload API. */
export function fileToBase64(file: File): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => {
      const result = reader.result as string; // "data:<type>;base64,<DATA>"
      const comma = result.indexOf(",");
      resolve(comma >= 0 ? result.slice(comma + 1) : result);
    };
    reader.onerror = () => reject(reader.error ?? new Error("Could not read file"));
    reader.readAsDataURL(file);
  });
}

/** Turn a base64 document into a Blob URL and either open it in a new tab or download it. */
export function openDocument(doc: DocumentContent, download = false): void {
  const bytes = Uint8Array.from(atob(doc.dataBase64), (c) => c.charCodeAt(0));
  const blob = new Blob([bytes], { type: doc.contentType || "application/octet-stream" });
  const url = URL.createObjectURL(blob);
  if (download) {
    const a = document.createElement("a");
    a.href = url;
    a.download = doc.fileName;
    document.body.appendChild(a);
    a.click();
    a.remove();
  } else {
    window.open(url, "_blank", "noopener,noreferrer");
  }
  // Give the browser time to consume the URL before revoking.
  setTimeout(() => URL.revokeObjectURL(url), 60_000);
}
