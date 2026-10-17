/**
 * The full-profile customer export (staff → Customers → [id] → Export): one CSV or one PDF carrying
 * everything the customer page knows about a borrower, section by section — profile, applications,
 * loans, repayments, verifications, references, documents.
 *
 * Pure builders only: they turn a `CustomerDetail` plus the export-only extras the page fetches on
 * demand into sectioned rows, and the exporters in `exporters.ts` do the download. Kept free of
 * React and the DOM so the shape of the file is unit-testable row by row.
 *
 * Two deliberate content decisions:
 *  - Aadhaar and PAN are written IN FULL. The export is ADMIN-only and staff already read both
 *    unmasked on the Personal tab (CLAUDE.md §6) — masking here would only make the file useless
 *    for the KYC reconciliation it exists for.
 *  - The disbursal account (number / IFSC) is NOT written. `ApplicationView` holds it in full so the
 *    Disbursement Head can make the transfer, with the standing instruction never to log or export it.
 */

import type {
  ApplicationDocumentGroup,
  ApplicationView,
  CustomerDetail,
  LoanView,
  PaymentView,
  ProfileView,
  ReferenceView,
  StepResult,
} from "@/lib/api/applications";
import { statusLabel } from "@/lib/api/applications";
import { formatDate, formatDateTime } from "@/lib/utils";
import { csvCell } from "./exporters";

/** What the page fetches, for the export only, beside the `CustomerDetail` it already holds. */
export interface CustomerExportExtras {
  /** `customersApi.documents` — names, types and dates only; never the file bytes. */
  documents: ApplicationDocumentGroup[];
  /** `staffApi.verifications` for the latest application. */
  verifications: StepResult[];
  /** `staffApi.references` for the latest application. */
  references: ReferenceView[];
}

/** One titled block of the CSV / one table of the PDF. */
export interface CustomerExportSection {
  title: string;
  header: string[];
  rows: (string | number | null | undefined)[][];
}

type Cell = string | number | null | undefined;

// ---------------------------------------------------------------------------
// Formatting
// ---------------------------------------------------------------------------

/**
 * Paise → rupees with two decimals and no grouping ("12500.00"): a spreadsheet reads it as a number
 * and the PDF right-aligns it. The currency lives in the column header ("… (Rs.)"), not the cell.
 */
export function rupees(paise: number | null | undefined): string {
  return paise == null ? "" : (paise / 100).toFixed(2);
}

/** Calendar date as "dd MMM yyyy" (IST); blank when missing. */
function date(v: string | null | undefined): string {
  return v ? formatDate(v) : "";
}

/** Event timestamp as "dd MMM yyyy, hh:mm am" (IST); blank when missing. */
function dateTime(v: string | null | undefined): string {
  return v ? formatDateTime(v) : "";
}

function yesNo(v: boolean | null | undefined): string {
  return v == null ? "" : v ? "Yes" : "No";
}

function text(v: string | number | null | undefined): string {
  return v == null ? "" : String(v);
}

/** Casing kept for the acronyms staff read as acronyms; everything else is sentence-cased. */
const ACRONYMS: Record<string, string> = {
  PAN: "PAN",
  GST: "GST",
  UAN: "UAN",
  KYC: "KYC",
  CRIF: "CRIF",
  EPFO: "EPFO",
  OTP: "OTP",
  UPI: "UPI",
  NACH: "NACH",
  DIGILOCKER: "DigiLocker",
  ESIGN: "eSign",
};

/** `AADHAAR_CARD_FRONT` → "Aadhaar card front"; `PENNY_DROP` → "Penny drop"; `PAN` stays "PAN". */
export function humanise(code: string | null | undefined): string {
  if (!code) return "";
  return code
    .split("_")
    .filter(Boolean)
    .map((w, i) => {
      const upper = w.toUpperCase();
      if (ACRONYMS[upper]) return ACRONYMS[upper];
      const lower = w.toLowerCase();
      return i === 0 ? lower.charAt(0).toUpperCase() + lower.slice(1) : lower;
    })
    .join(" ");
}

/** `customer-5914823-20261017` — the file base both formats share, from the customer id and a date. */
export function customerExportFileBase(customerId: number, now: Date): string {
  // IST calendar day, like every other date in the file.
  const parts = new Intl.DateTimeFormat("en-CA", {
    timeZone: "Asia/Kolkata",
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
  }).format(now);
  return `customer-${customerId}-${parts.replace(/-/g, "")}`;
}

// ---------------------------------------------------------------------------
// Sections
// ---------------------------------------------------------------------------

/** Every `ProfileView` field, in reading order, with the label staff see for it. */
const PROFILE_FIELDS: [label: string, value: (p: ProfileView) => Cell][] = [
  ["Full name", (p) => p.fullName],
  ["Mobile", (p) => p.mobile],
  ["Date of birth", (p) => date(p.dob)],
  ["PAN", (p) => p.pan],
  ["Aadhaar", (p) => p.aadhaar],
  ["Address", (p) => p.address],
  ["Personal email", (p) => p.email],
  ["Official email", (p) => p.officialEmail],
  ["Employer", (p) => p.employer],
  ["Employment status", (p) => p.employmentStatus],
  ["UAN", (p) => p.uan],
  ["Monthly salary (Rs.)", (p) => rupees(p.monthlySalaryPaise)],
  ["Annual salary (Rs.)", (p) => rupees(p.annualSalaryPaise)],
  ["Salary percentage (%)", (p) => text(p.salaryPercentage)],
  ["Increment percentage (%)", (p) => text(p.incrementPercentage)],
  ["Salary bank", (p) => p.salaryBank],
  ["Salary account number", (p) => p.salaryAccountNumber],
  ["Salary account IFSC", (p) => p.salaryIfsc],
  ["Salary account mobile", (p) => p.salaryAccountMobile],
  ["Previous salary date", (p) => date(p.previousSalaryDate)],
  ["Emergency contact name", (p) => p.emergencyContactName],
  ["Emergency contact phone", (p) => p.emergencyContactPhone],
  ["Emergency contact relation", (p) => p.emergencyContactRelation],
  ["Credit score", (p) => text(p.creditScore)],
  ["Star rating", (p) => text(p.starRating)],
  ["Recommendation", (p) => p.recommendation],
  ["Bureau source", (p) => p.bureauSource],
  ["Risk category", (p) => p.riskCategory],
  ["Name match score", (p) => text(p.nameMatchScore)],
  ["PAN verified", (p) => yesNo(p.panVerified)],
  ["Aadhaar linked", (p) => yesNo(p.aadhaarLinked)],
  ["Aadhaar verified (DigiLocker)", (p) => yesNo(p.aadhaarVerified)],
  ["Official email verified", (p) => yesNo(p.emailVerified)],
  ["Personal email OTP verified", (p) => yesNo(p.personalEmailVerified)],
  ["Official email OTP verified", (p) => yesNo(p.officialEmailOtpVerified)],
  ["Address verified", (p) => yesNo(p.addressVerified)],
  ["Penny drop verified", (p) => yesNo(p.pennyDropVerified)],
  ["Terms version", (p) => p.termsVersion],
  ["Terms accepted at", (p) => dateTime(p.termsAcceptedAt)],
  ["PEP declared at", (p) => dateTime(p.pepDeclaredAt)],
  ["Credit brief summary", (p) => p.creditBriefSummary],
  ["Credit brief generated at", (p) => dateTime(p.creditBriefGeneratedAt)],
];

function profileSection(detail: CustomerDetail): CustomerExportSection {
  const p = detail.profile;
  const rows: Cell[][] = [
    ["Customer ID", detail.customerId],
    ["Owner", detail.ownerName ?? "Unallocated"],
    ["Admin limit override (Rs.)", rupees(detail.limitOverridePaise)],
    ["Bureau state", humanise(detail.creditBrief?.bureauState)],
  ];
  if (p) for (const [label, value] of PROFILE_FIELDS) rows.push([label, value(p)]);
  return { title: "Customer profile", header: ["Field", "Value"], rows };
}

function applicationsSection(apps: ApplicationView[]): CustomerExportSection {
  return {
    title: "Applications",
    header: [
      "Application #",
      "Status",
      "Created",
      "Stage entered",
      "Amount requested (Rs.)",
      "Eligible limit (Rs.)",
      "Sanctioned amount (Rs.)",
      "Repayment date",
      "Tenure (days)",
      "Salary credit day",
      "Purpose",
      "Journey step",
      "Assigned to",
      "Credit decided by",
      "Disbursed by",
      "Collection officer",
      "Loan #",
      "Pending reason",
      "Sanction remarks",
    ],
    rows: apps.map((a) => [
      a.id,
      statusLabel(a.status),
      date(a.createdAt),
      dateTime(a.currentStageEnteredAt),
      rupees(a.amountRequestedPaise),
      rupees(a.eligibleLimitPaise),
      rupees(a.sanctionedAmountPaise),
      date(a.approvedRepaymentDate),
      text(a.sanctionTenureDays),
      text(a.salaryCreditDay),
      a.purpose,
      humanise(a.journeyStep),
      a.assignedExecutiveName,
      a.creditDecidedByName,
      a.disbursedByName,
      a.collectionOfficerName,
      a.loanId,
      a.pendingReason,
      a.sanctionRemarks,
    ]),
  };
}

function loansSection(detail: CustomerDetail): CustomerExportSection {
  const breakdown = detail.outstandingByLoanId ?? {};
  return {
    title: "Loans",
    header: [
      "Loan #",
      "Principal (Rs.)",
      "Processing fee (Rs.)",
      "GST (Rs.)",
      "Net disbursed (Rs.)",
      "Disbursed on",
      "Due date",
      "Closure date",
      "Total repayable (Rs.)",
      "Interest accrued (Rs.)",
      "Penalty (Rs.)",
      "Paid (verified) (Rs.)",
      "Outstanding (Rs.)",
      "Status",
      "Txn ref",
    ],
    rows: detail.loans.map((l: LoanView) => {
      const o = breakdown[String(l.id)];
      return [
        l.id,
        rupees(l.principalPaise),
        rupees(l.processingFeePaise),
        rupees(l.gstPaise),
        rupees(l.netDisbursedPaise),
        date(l.disbursedOn),
        date(l.dueDate),
        date(l.closedOn),
        rupees(l.totalRepayablePaise),
        rupees(o?.interestPaise),
        rupees(o?.penaltyPaise),
        rupees(o?.verifiedPaise),
        // The itemised figure is the penalty-aware compute-on-read balance; the loan's own column
        // is the recompute cache (CLAUDE.md §9), so prefer the former when it is there.
        rupees(o?.outstandingPaise ?? l.outstandingPaise),
        humanise(l.status),
        l.disbursalTxnRef,
      ];
    }),
  };
}

function repaymentsSection(payments: PaymentView[]): CustomerExportSection {
  return {
    title: "Repayments",
    header: ["Payment #", "Loan #", "Date", "Amount (Rs.)", "Method", "Status", "Txn ref", "Rejection reason"],
    rows: payments.map((p) => [
      p.id,
      p.loanId,
      date(p.paidOn),
      rupees(p.amountPaise),
      humanise(p.method),
      humanise(p.status),
      p.txnRef,
      p.rejectionReason ? [humanise(p.rejectionReason), p.rejectionNote].filter(Boolean).join(" — ") : "",
    ]),
  };
}

function verificationsSection(steps: StepResult[]): CustomerExportSection {
  return {
    title: "Verifications",
    header: ["Check", "Status", "Provider", "Checked at", "Message"],
    rows: steps.map((s) => [humanise(s.checkType), humanise(s.status), s.provider, dateTime(s.checkedAt), s.message]),
  };
}

function referencesSection(refs: ReferenceView[]): CustomerExportSection {
  return {
    title: "References",
    header: ["Slot", "Name", "Mobile", "Relation"],
    rows: refs.map((r) => [r.slot, r.fullName, r.mobile, humanise(r.relation)]),
  };
}

function documentsSection(groups: ApplicationDocumentGroup[]): CustomerExportSection {
  return {
    title: "Documents",
    header: ["Type", "File name", "Uploaded", "Application #"],
    rows: groups.flatMap((g) =>
      g.documents.map((d) => [humanise(d.docType), d.fileName, dateTime(d.uploadedAt), g.applicationId]),
    ),
  };
}

/** The sections of the full-profile export, in the order they appear in both the CSV and the PDF. */
export function buildCustomerCsvSections(detail: CustomerDetail, extras: CustomerExportExtras): CustomerExportSection[] {
  return [
    profileSection(detail),
    applicationsSection(detail.applications),
    loansSection(detail),
    repaymentsSection(detail.payments),
    verificationsSection(extras.verifications),
    referencesSection(extras.references),
    documentsSection(extras.documents),
  ];
}

/**
 * The sectioned CSV text: a title row, the header row and the body rows per section, sections
 * separated by one blank line. Every cell goes through `csvCell`, so borrower-typed text that starts
 * with = + - @ is neutralised before a spreadsheet can run it as a formula.
 */
export function customerCsvText(sections: CustomerExportSection[]): string {
  return sections
    .map((s) =>
      [csvCell(s.title), s.header.map(csvCell).join(","), ...s.rows.map((r) => r.map(csvCell).join(","))].join("\n"),
    )
    .join("\n\n");
}

/** Title, subtitle and sections for `exportPdfSections` — the PDF twin of the CSV builder. */
export function buildCustomerPdf(
  detail: CustomerDetail,
  extras: CustomerExportExtras,
): { title: string; subtitle: string; sections: CustomerExportSection[] } {
  const name = detail.profile?.fullName?.trim() || `Customer #${detail.customerId}`;
  const latest = detail.applications[0];
  return {
    title: `Customer profile — ${name} (#${detail.customerId})`,
    subtitle: latest ? `Latest application #${latest.id} · ${statusLabel(latest.status)}` : "No applications yet",
    sections: buildCustomerCsvSections(detail, extras),
  };
}
