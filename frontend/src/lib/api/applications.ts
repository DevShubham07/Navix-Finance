/**
 * Typed client for the NAVIX backend application state-machine API.
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
  applicantId: number;
  status: ApplicationStatus;
  amountRequestedPaise: number | null;
  eligibleLimitPaise: number | null;
  purpose: string | null;
  assignedExecutiveId: number | null;
  loanId: number | null;
  /** A pre-approved reborrow that reached disbursement without credit review (fast-track section). */
  fastTrack?: boolean;
  /** Staff-only credit headline (populated on staff queue rows; never on borrower paths). */
  creditScore?: number | null;
  starRating?: number | null;
  recommendation?: string | null;
}

/**
 * ADMIN-only flat view of an application with full KYC detail + onboarding completeness — covers
 * complete AND incomplete (DRAFT / partially filled) applications. Mirrors backend AdminApplicationView.
 */
export interface AdminApplicationView {
  id: number;
  applicantId: number;
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
  aadhaar: string | null;
  mobile: string | null;
  email: string | null;
  dob: string | null;
  address: string | null;
  employer: string | null;
  employmentStatus: string | null;
  monthlySalaryPaise: number | null;
  salaryBank: string | null;
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
}

export interface EventView {
  id: number;
  fromStatus: ApplicationStatus | null;
  toStatus: ApplicationStatus | null;
  actorId: number | null;
  actorRole: string | null;
  action: string | null;
  notes: string | null;
  at: string;
}

export interface LoanView {
  id: number;
  applicantId: number;
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
}

export interface OutstandingView {
  loanId: number;
  asOf: string;
  outstandingPaise: number;
  /** Non-null when collections has an approved settlement: outstandingPaise is then the
   *  settlement-capped full-and-final figure. */
  settledAmountPaise?: number | null;
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
}

export type TransactionType = "DISBURSAL" | "REPAYMENT";
export type TransactionDirection = "OUTGOING" | "INCOMING";

/** One row in the accountant's company-wide transactions ledger (mirrors backend TransactionView). */
export interface TransactionView {
  id: string;
  type: TransactionType;
  direction: TransactionDirection;
  loanId: number | null;
  applicantId: number | null;
  borrowerName: string | null;
  pan: string | null;
  amountPaise: number;
  txnRef: string | null;
  status: string | null;
  date: string | null;
}

/**
 * Applicant KYC snapshot for an application. Staff see the full, unmasked identity + verification
 * detail; on the borrower's own read the credit/risk/bureau fields come back null.
 */
export interface ProfileView {
  applicationId: number;
  fullName: string | null;
  pan: string | null;
  aadhaar: string | null;
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
  /** Staff-only credit headline (score + 1–5★ rating + verdict). Null until the bureau is pulled. */
  creditScore?: number | null;
  starRating?: number | null;
  recommendation?: string | null;
  /** Staff-only verification + risk detail (null on borrower-facing reads). */
  bureauSource?: string | null;
  riskCategory?: string | null;
  panVerified?: boolean | null;
  aadhaarLinked?: boolean | null;
  emailVerified?: boolean | null;
  addressVerified?: boolean | null;
  pennyDropVerified?: boolean | null;
  nameMatchScore?: number | null;
  creditBriefSummary?: string | null;
  creditBriefGeneratedAt?: string | null;
}

/** What the borrower submits for their KYC profile (all fields optional). */
export interface ProfileInput {
  fullName?: string;
  pan?: string;
  aadhaar?: string; // 12 digits; uniqueness enforced server-side
  mobile?: string; // 10 digits; uniqueness enforced server-side
  email?: string; // contact email — gates email notifications
  dob?: string; // ISO yyyy-mm-dd
  address?: string;
  employer?: string;
  employmentStatus?: string;
  monthlySalaryPaise?: number;
  salaryBank?: string;
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
  applicantId: number;
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
}

/** A customer's full history: latest profile + every application, loan and payment (mirrors backend). */
export interface CustomerDetail {
  applicantId: number;
  profile: ProfileView | null;
  applications: ApplicationView[];
  loans: LoanView[];
  payments: PaymentView[];
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

/** Staff credit brief for an application: 1–5★ rating headline + facts + the CREDIT_BRIEF PDF doc id. */
export interface CreditBriefView {
  applicationId: number;
  available: boolean;
  creditScore: number | null;
  starRating: number | null;
  recommendation: string | null;
  summary: string | null;
  generatedAt: string | null;
  /** The stored CREDIT_BRIEF document id — fetch its presigned URL via staffApi.documentUrl. */
  documentId: number | null;
  facts: CreditBriefFacts | null;
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

  constructor(message: string, code: string, status: number) {
    super(message);
    this.name = "ApplicationApiError";
    this.code = code;
    this.status = status;
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
    throw new ApplicationApiError(message, code, res.status);
  }

  if (!res.ok) {
    throw new ApplicationApiError(
      parsed?.message ?? `Request failed with status ${res.status}.`,
      parsed?.error?.code ?? `HTTP_${res.status}`,
      res.status,
    );
  }

  if (!parsed) {
    throw new ApplicationApiError("Empty response from server.", "EMPTY_RESPONSE", res.status);
  }

  return parsed.data;
}

// ---------------------------------------------------------------------------
// Borrower client — routes under /api/borrower/*
// ---------------------------------------------------------------------------

const BORROWER_BASE = "/api/borrower/applications";
const BORROWER_LOAN_BASE = "/api/borrower/loan";

export const borrowerApi = {
  /** Create a DRAFT application for the given applicant. */
  create: (applicantId: number) =>
    bff<ApplicationView>(`${BORROWER_BASE}`, "POST", { applicantId }),

  /**
   * Returning-borrower reborrow: start a new advance reusing the saved KYC profile (applicantId is
   * resolved server-side from the session). Returns PRE_APPROVED (good standing → choose amount) or
   * REVIEW_PENDING (past delinquency → held for KYC review). Throws NO_PRIOR_LOAN / ACTIVE_LOAN.
   */
  reborrow: () => bff<ApplicationView>(`${BORROWER_BASE}/reborrow`, "POST"),

  /** DRAFT -> KYC_PENDING. */
  submitKyc: (id: number) =>
    bff<ApplicationView>(`${BORROWER_BASE}/${id}/submit-kyc`, "POST"),

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

  /** Save/update the applicant KYC details for this application. */
  saveProfile: (id: number, profile: ProfileInput) =>
    bff<ProfileView>(`${BORROWER_BASE}/${id}/profile`, "PUT", profile),

  /** Read back the (masked) profile. */
  getProfile: (id: number) => bff<ProfileView>(`${BORROWER_BASE}/${id}/profile`, "GET"),

  /** Upload one document (bytes as base64). */
  uploadDocument: (
    id: number,
    doc: { docType: string; fileName: string; contentType?: string; dataBase64: string },
  ) => bff<DocumentView>(`${BORROWER_BASE}/${id}/documents`, "POST", doc),

  /** List documents already uploaded for this application. */
  documents: (id: number) => bff<DocumentView[]>(`${BORROWER_BASE}/${id}/documents`, "GET"),
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
  applicantId: number | null;
  borrowerName: string | null;
  checkType: string;
  status: CheckStatus;
  provider: string | null;
  message: string | null;
  updatedAt: string | null;
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

  /** Credit bureau pull (automatic — no input; score/category never surfaced to the borrower). */
  bureau: (id: number) => bff<StepResult>(`${BORROWER_BASE}/${id}/verify/bureau`, "POST"),

  /** Declared salary + uploaded slip object keys (min 3 months). */
  salary: (id: number, monthlySalaryPaise: number, slipObjectKeys: string[]) =>
    bff<StepResult>(`${BORROWER_BASE}/${id}/verify/salary`, "POST", { monthlySalaryPaise, slipObjectKeys }),

  /** Penny-drop on the salary account → name match. */
  pennyDrop: (id: number, accountNumber: string, ifsc: string) =>
    bff<StepResult>(`${BORROWER_BASE}/${id}/verify/penny-drop`, "POST", { accountNumber, ifsc }),

  /** Selfie liveness/face match against the uploaded selfie object key. */
  selfie: (id: number, selfieObjectKey: string) =>
    bff<StepResult>(`${BORROWER_BASE}/${id}/verify/selfie`, "POST", { selfieObjectKey }),

  /** Record consent to the agreement document set. */
  agreement: (id: number, versions: AgreementVersion[]) =>
    bff<StepResult>(`${BORROWER_BASE}/${id}/verify/agreement`, "POST", { versions }),

  /** The full verification status board for this application. */
  summary: (id: number) => bff<StepResult[]>(`${BORROWER_BASE}/${id}/verify/summary`, "GET"),

  /** Ask the app-scoped endpoint for a presigned PUT URL (echo `key` back on the verify call). */
  presignUpload: (
    id: number,
    body: { docType: string; fileName: string; contentType: string },
  ) => bff<VerifyPresign>(`${BORROWER_BASE}/${id}/verify/presign-upload`, "POST", body),

  /** PUT raw bytes (File or Blob) straight to the presigned S3 URL — never through the BFF. */
  putToPresignedUrl: async (url: string, body: Blob, contentType: string): Promise<void> => {
    const res = await fetch(url, {
      method: "PUT",
      body,
      headers: { "Content-Type": contentType },
    });
    if (!res.ok) {
      throw new ApplicationApiError(`Upload failed (status ${res.status}).`, `UPLOAD_FAILED_${res.status}`, res.status);
    }
  },
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

  /** ADMIN-only: every application (complete + incomplete) with full KYC detail + completeness. */
  listAllApplications: () => bff<AdminApplicationView[]>(`${STAFF_BASE}/all`, "GET"),

  get: (id: number) => bff<ApplicationView>(`${STAFF_BASE}/${id}`, "GET"),

  events: (id: number) => bff<EventView[]>(`${STAFF_BASE}/${id}/events`, "GET"),

  // --- maker-checker actions ---
  kycDecision: (id: number, decision: boolean, notes?: string) =>
    bff<ApplicationView>(`${STAFF_BASE}/${id}/kyc-decision`, "POST", { decision, notes }),

  /** Clear (or reject) a flagged returning borrower: REVIEW_PENDING → PRE_APPROVED / REJECTED. */
  reviewDecision: (id: number, decision: boolean, notes?: string) =>
    bff<ApplicationView>(`${STAFF_BASE}/${id}/review-decision`, "POST", { decision, notes }),

  assign: (id: number, executiveId: number) =>
    bff<ApplicationView>(`${STAFF_BASE}/${id}/assign`, "POST", { executiveId }),

  execDecision: (id: number, decision: boolean, notes?: string) =>
    bff<ApplicationView>(`${STAFF_BASE}/${id}/exec-decision`, "POST", { decision, notes }),

  headDecision: (
    id: number,
    payload: { decision: boolean; approvedAmountPaise?: number; notes?: string },
  ) => bff<ApplicationView>(`${STAFF_BASE}/${id}/head-decision`, "POST", payload),

  disbursementDecision: (id: number, decision: boolean, txnRef?: string, notes?: string) =>
    bff<ApplicationView>(`${STAFF_BASE}/${id}/disbursement-decision`, "POST", { decision, txnRef, notes }),

  accountantValidate: (id: number, decision: boolean, txnRef?: string, notes?: string) =>
    bff<ApplicationView>(`${STAFF_BASE}/${id}/accountant-validate`, "POST", { decision, txnRef, notes }),

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
  transactions: (q?: string, direction?: TransactionDirection) => {
    const params = new URLSearchParams();
    if (q) params.set("q", q);
    if (direction) params.set("direction", direction);
    const qs = params.toString();
    return bff<TransactionView[]>(`${STAFF_LOAN_BASE}/transactions${qs ? `?${qs}` : ""}`, "GET");
  },

  /** Repayments recorded against one loan. */
  repayments: (loanId: number) => bff<PaymentView[]>(`${STAFF_LOAN_BASE}/${loanId}/repayments`, "GET"),

  /** Confirm proof for a payment → reduces outstanding, closes the loan/application at zero. */
  verifyRepayment: (loanId: number, paymentId: number) =>
    bff<PaymentView>(`${STAFF_LOAN_BASE}/${loanId}/repayments/${paymentId}/verify`, "POST"),

  // --- applicant review (any reviewing role) ---
  /** The applicant's KYC details (PAN masked). */
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
};

// ---------------------------------------------------------------------------
// Customers (borrower-centric) — routes under /api/staff/customers/*
// ---------------------------------------------------------------------------

const CUSTOMERS_BASE = "/api/staff/customers";

export const customersApi = {
  /** All customers, optionally filtered by name / applicant id. */
  list: (q?: string) =>
    bff<CustomerSummary[]>(`${CUSTOMERS_BASE}${q ? `?q=${encodeURIComponent(q)}` : ""}`, "GET"),

  /** One customer's full history (profile + applications + loans + payments). */
  get: (applicantId: number) => bff<CustomerDetail>(`${CUSTOMERS_BASE}/${applicantId}`, "GET"),

  /** ADMIN corrects a customer's KYC / salary data (non-identity fields); changes are audited. */
  updateProfile: (applicantId: number, body: UpdateCustomerInput) =>
    bff<ProfileView>(`${CUSTOMERS_BASE}/${applicantId}/profile`, "PUT", body),

  /** One customer's audited profile/salary change history (newest first). */
  changes: (applicantId: number) =>
    bff<ProfileChangeView[]>(`${CUSTOMERS_BASE}/${applicantId}/changes`, "GET"),
};

// ---------------------------------------------------------------------------
// Admin (IAM) — staff users, invites, fraud blocklist
// ---------------------------------------------------------------------------

export type StaffRoleName =
  | "KYC_APPROVER"
  | "CREDIT_EXECUTIVE"
  | "CREDIT_HEAD"
  | "DISBURSEMENT_HEAD"
  | "ACCOUNTANT"
  | "COLLECTION_HEAD"
  | "COLLECTION_EXECUTIVE"
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
  putToPresignedUrl: async (url: string, file: File): Promise<void> => {
    const res = await fetch(url, {
      method: "PUT",
      body: file,
      headers: { "Content-Type": file.type || "application/octet-stream" },
    });
    if (!res.ok) {
      throw new ApplicationApiError(
        `Upload failed (status ${res.status}).`,
        `UPLOAD_FAILED_${res.status}`,
        res.status,
      );
    }
  },
};

// ---------------------------------------------------------------------------
// Collections — cases, interactions, settlements (maker-checker), DPD helper
// ---------------------------------------------------------------------------

export type DpdBucket = "UPCOMING" | "T0_T7" | "T8_T30" | "T30_T60" | "T60_T90" | "T90_PLUS";

/** Real loan + borrower snapshot surfaced to collections (mirrors backend LoanSummary). */
export interface LoanSummary {
  loanId: number;
  applicantId: number | null;
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

export interface SettlementView {
  id: string;
  collectionCaseId: string;
  settlementAmountPaise: number | null;
  proposedBy: number | null;
  proposedByName: string | null;
  approvedBy: number | null;
  approvedByName: string | null;
  createdAt: string;
  approvedAt: string | null;
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

  /** Loans eligible to open a case against (ACTIVE/OVERDUE, due on or before dueBy). */
  listCollectibleLoans: (dueBy?: string) =>
    bff<LoanSummary[]>(
      `${COLLECTIONS_BASE}/loans${dueBy ? `?dueBy=${encodeURIComponent(dueBy)}` : ""}`,
      "GET",
    ),
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
