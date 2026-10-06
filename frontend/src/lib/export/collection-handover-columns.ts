import type { CollectionHandoverRow } from "@/lib/api/applications";
import type { ExportColumn } from "@/lib/export/exporters";
import { formatDate, formatDateTime } from "@/lib/utils";

/**
 * Column blocks for the DPD-bucket export that is handed to an outside collection agency.
 *
 * The page owns the order (and the loan / staff columns that come off the worklist row); this file
 * owns everything read from the handover payload — full identity, both addresses, references and
 * each verification result. Split out because it is ~100 column definitions that would otherwise
 * bury the page.
 */

type Fmt = "text" | "rupees" | "date" | "datetime" | "percent";

function show(v: unknown, fmt: Fmt = "text"): string {
  if (v == null || v === "") return "—";
  if (typeof v === "boolean") return v ? "Yes" : "No";
  if (fmt === "rupees") return typeof v === "number" ? (v / 100).toFixed(2) : String(v);
  // A 0..1 match score reads as a percentage; anything already above 1 is printed as it came.
  if (fmt === "percent") return typeof v === "number" && v <= 1 ? `${Math.round(v * 100)}%` : String(v);
  if (fmt === "date") return typeof v === "string" ? formatDate(v) : String(v);
  if (fmt === "datetime") return typeof v === "string" ? formatDateTime(v) : String(v);
  return typeof v === "object" ? JSON.stringify(v) : String(v);
}

export function collectionHandoverColumns<Row extends { applicationId: number | null }>(
  lookup: (applicationId: number | null) => CollectionHandoverRow | undefined,
) {
  type Col = ExportColumn<Row>;
  const of = (r: Row) => lookup(r.applicationId);

  /** A `customer_profile` field. */
  const p = (header: string, key: string, fmt?: Fmt): Col => ({
    header,
    value: (r) => show(of(r)?.profile[key], fmt),
  });
  /** A `loan_application` field. */
  const a = (header: string, key: string, fmt?: Fmt): Col => ({
    header,
    value: (r) => show(of(r)?.application[key], fmt),
  });
  /** One key of a check's whitelisted `derived` result. */
  const d = (header: string, check: string, key: string, fmt?: Fmt): Col => ({
    header,
    value: (r) => show(of(r)?.checks[check]?.derived[key], fmt),
  });
  /** A column on the check row itself (status, provider, message, nameMatch, score, at). */
  const c = (
    header: string,
    check: string,
    field: "status" | "provider" | "message" | "nameMatch" | "score" | "at",
    fmt?: Fmt,
  ): Col => ({ header, value: (r) => show(of(r)?.checks[check]?.[field], fmt) });
  const ref = (n: 1 | 2): Col[] =>
    (["name", "mobile", "relation"] as const).map((field) => ({
      header: `Reference ${n} ${field}`,
      value: (r) => show(of(r)?.references[n - 1]?.[field]),
    }));

  return {
    /** The agency's first question: where does this person live. Sits right after name + PAN. */
    addresses: [
      d("DigiLocker address", "AADHAAR", "address"),
      d("DigiLocker address line", "AADHAAR", "addressLine"),
      d("DigiLocker landmark", "AADHAAR", "landmark"),
      d("DigiLocker city", "AADHAAR", "city"),
      d("DigiLocker district", "AADHAAR", "district"),
      d("DigiLocker state", "AADHAAR", "state"),
      d("DigiLocker PIN", "AADHAAR", "pincode"),
      d("Geolocation address", "ADDRESS", "address"),
      d("Geolocation district", "ADDRESS", "district"),
      d("Geolocation state", "ADDRESS", "state"),
      d("Geolocation PIN", "ADDRESS", "pincode"),
      d("Typed address (no GPS)", "ADDRESS", "manualAddress"),
      d("Signing latitude", "ESIGN", "latitude"),
      d("Signing longitude", "ESIGN", "longitude"),
    ] as Col[],
    contact: [
      p("Salary-account mobile", "salaryAccountMobile"),
      p("Personal email", "email"),
      p("Official email", "officialEmail"),
      p("Profile address", "address"),
    ] as Col[],
    identity: [
      p("Date of birth", "dob", "date"),
      d("Gender", "AADHAAR", "gender"),
      p("Aadhaar number", "aadhaar"),
      d("Aadhaar (masked, DigiLocker)", "AADHAAR", "maskedAadhaar"),
    ] as Col[],
    references: [
      ...ref(1),
      ...ref(2),
      p("Emergency contact name", "emergencyContactName"),
      p("Emergency contact phone", "emergencyContactPhone"),
      p("Emergency contact relation", "emergencyContactRelation"),
    ] as Col[],
    sanction: [
      a("Sanctioned amount (₹)", "sanctionedAmountPaise", "rupees"),
      a("Tenure (days)", "sanctionTenureDays"),
      a("Salary day", "salaryCreditDay"),
      a("Purpose", "purpose"),
    ] as Col[],
    bank: [
      a("Disbursal account", "disbursalAccountNumber"),
      a("Disbursal IFSC", "disbursalIfsc"),
      a("Disbursal account holder", "disbursalHolderName"),
      a("Disbursal bank", "disbursalBank"),
      p("Salary account", "salaryAccountNumber"),
      p("Salary IFSC", "salaryIfsc"),
      p("Salary bank", "salaryBank"),
      c("Penny drop status", "PENNY_DROP", "status"),
      d("Penny drop beneficiary", "PENNY_DROP", "beneficiaryName"),
      c("Penny drop name match", "PENNY_DROP", "nameMatch", "percent"),
      d("Penny drop bank RRN", "PENNY_DROP", "bankRrn"),
    ] as Col[],
    employment: [
      p("Employment status", "employmentStatus"),
      p("Annual salary (₹)", "annualSalaryPaise", "rupees"),
      p("UAN", "uan"),
      p("Last salary date", "previousSalaryDate", "date"),
    ] as Col[],
    /** EPFO employment check + the work-email employer match. */
    company: [
      c("Company check status", "EMPLOYMENT", "status"),
      d("EPFO record found", "EMPLOYMENT", "found"),
      d("Currently employed", "EMPLOYMENT", "employed"),
      d("At declared employer", "EMPLOYMENT", "employedAtDeclaredEmployer"),
      d("EPFO employer name", "EMPLOYMENT", "employerName"),
      d("Date of joining", "EMPLOYMENT", "dateOfJoining"),
      d("Date of exit", "EMPLOYMENT", "dateOfExit"),
      d("Tenure (months)", "EMPLOYMENT", "tenureMonths"),
      d("Employer name match", "EMPLOYMENT", "employerNameMatch"),
      d("Employee name match", "EMPLOYMENT", "employeeNameMatch"),
      d("Recent PF filing", "EMPLOYMENT", "recentPfFiling"),
      d("EPFO establishment ID", "EMPLOYMENT", "establishmentId"),
      d("Leave reason", "EMPLOYMENT", "leaveReason"),
      c("Company check note", "EMPLOYMENT", "message"),
      c("Work email status", "EMAIL", "status"),
      d("Work email: employer matched", "EMAIL", "establishmentMatched"),
      d("Work email: person matched", "EMAIL", "individualMatched"),
      d("Work email: matched establishment", "EMAIL", "matchedEstablishment"),
      d("Work email: company name", "EMAIL", "companyName"),
      d("Work email: domain", "EMAIL", "domain"),
      d("Work email: generic address", "EMAIL", "genericEmail"),
    ] as Col[],
    pan: [
      c("PAN check status", "PAN", "status"),
      c("PAN check provider", "PAN", "provider"),
      d("Name on PAN", "PAN", "fullName"),
      d("DOB on PAN", "PAN", "dob"),
      d("PAN status", "PAN", "panStatus"),
      d("PAN–Aadhaar linked", "PAN", "aadhaarLinked"),
      c("PAN name match", "PAN", "nameMatch", "percent"),
      d("PAN state", "PAN", "addressState"),
      d("PAN PIN", "PAN", "addressZip"),
      d("PAN allotment date", "PAN", "panAllotmentDate"),
      d("PAN Aadhaar mismatch", "PAN", "aadhaarMismatch"),
      c("PAN check note", "PAN", "message"),
    ] as Col[],
    digilocker: [
      c("DigiLocker status", "DIGILOCKER", "status"),
      c("Aadhaar check status", "AADHAAR", "status"),
      c("Aadhaar check provider", "AADHAAR", "provider"),
      d("Name on Aadhaar", "AADHAAR", "fullName"),
      d("DOB on Aadhaar", "AADHAAR", "dob"),
      c("Aadhaar name match", "AADHAAR", "nameMatch", "percent"),
      d("Aadhaar mismatch", "AADHAAR", "aadhaarMismatch"),
      c("Aadhaar check note", "AADHAAR", "message"),
      c("Aadhaar verified at", "AADHAAR", "at", "datetime"),
      c("Address check status", "ADDRESS", "status"),
    ] as Col[],
    selfieAndEsign: [
      c("Selfie status", "SELFIE", "status"),
      d("Selfie live", "SELFIE", "live"),
      d("Selfie face matched", "SELFIE", "faceMatched"),
      d("Selfie match %", "SELFIE", "matchPercentage"),
      d("Selfie liveness score", "SELFIE", "livenessScore"),
      c("eSign status", "ESIGN", "status"),
      d("eSigned at", "ESIGN", "signedAt", "datetime"),
    ] as Col[],
    /** The score and its headline only — never what is inside the bureau report. */
    credit: [
      p("Credit score", "bureauScore"),
      p("Bureau", "bureauSource"),
      p("Credit rating (stars)", "creditStarRating"),
      c("Bureau check status", "BUREAU", "status"),
      p("Risk category", "riskCategory"),
    ] as Col[],
    consent: [p("T&C accepted at", "termsAcceptedAt", "datetime")] as Col[],
  };
}
