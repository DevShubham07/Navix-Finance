/**
 * A bureau report read for the BRE tab — one shape for CRIF (Fintrix crif_combine / KBA answer) and
 * Experian (Digitap / Signzy), carrying the fields the parsed `Tradeline` drops: ownership
 * (CRIF OWNERSHIP-IND / Experian AccountHoldertypeCode), lender type (CRIF CREDIT-GRANTOR-TYPE /
 * Experian Identification_Number prefix), dated per-month DPD, occupation and income.
 *
 * Primary source is the staff-only raw `CreditBriefView.providerResponse`. When that is missing (a
 * reborrow's newest file, a REVIEW snapshot, a KBA challenge) {@link reportFromTradelines} rebuilds
 * what it can from the parsed `facts.detail.tradelines`; ownership is then unknown, never assumed.
 *
 * Absent ≠ clean: every unknown stays `null` so a rule can say "no data" instead of passing.
 */

import type { CreditBriefView, Tradeline } from "@/lib/api/applications";
import type { JsonValue } from "@/lib/credit/provider-report";
import { accountTypeLabel } from "@/components/staff/credit/tradeline-table";

export type BureauKind = "CRIF" | "EXPERIAN";
export type Ownership = "INDIVIDUAL" | "JOINT" | "GUARANTOR" | "AUTHORISED_USER" | "OTHER";
export type AccountStatus = "ACTIVE" | "CLOSED" | "OTHER";

/** One reported month of an account, newest first in {@link BureauAccount.history}. */
export interface MonthDpd {
  /** YYYY-MM */
  month: string;
  /** Days past due; null = not reported that month. For an inexact Experian bucket, the bucket floor. */
  dpd: number | null;
  /** Non-performing asset class reported for the month (CRIF SUB/DBT/LSS/SMA*, Experian B/D/M). */
  npa: boolean;
}

export interface BureauAccount {
  lender: string | null;
  accountNumber: string | null;
  accountType: string;
  isPersonalLoan: boolean;
  /** null = the bureau did not say (or only the parsed tradeline was available). */
  ownership: Ownership | null;
  ownershipLabel: string | null;
  /** Raw lender-type code: CRIF CREDIT-GRANTOR-TYPE ("NBF", "PRB"…), Experian ID prefix ("NBF", "PVT"…). */
  lenderType: string | null;
  /** null = this bureau/shape does not tell us the lender type. */
  isNbfc: boolean | null;
  status: AccountStatus;
  statusLabel: string | null;
  /** ISO dates. */
  openedOn: string | null;
  closedOn: string | null;
  lastReportedOn: string | null;
  overdueRupees: number | null;
  balanceRupees: number | null;
  history: MonthDpd[];
  /** false when DPD is an Experian bucket (0 = "0-29 days"), so 11-29 days cannot be seen. */
  historyExact: boolean;
  occupation: string | null;
}

export interface ReportedValue {
  value: string;
  reportedOn: string | null;
  where: string;
}

export interface BureauReport {
  kind: BureauKind;
  /** RAW = the vendor response; PARSED = rebuilt from facts.detail.tradelines (no ownership). */
  source: "RAW" | "PARSED";
  /** The application whose bureau pull this is. */
  applicationId: number;
  /** ISO date the bureau issued the report, when known. */
  reportDate: string | null;
  score: number | null;
  accounts: BureauAccount[];
  occupations: ReportedValue[];
  incomes: ReportedValue[];
  /** Every address the bureau holds — employer addresses show up here. */
  addresses: ReportedValue[];
}

// ---------------------------------------------------------------------------
// JSON helpers
// ---------------------------------------------------------------------------

type Obj = { [k: string]: JsonValue };

const isObj = (v: unknown): v is Obj => v != null && typeof v === "object" && !Array.isArray(v);
const at = (v: unknown, ...path: string[]): JsonValue | undefined => {
  let cur: unknown = v;
  for (const p of path) {
    if (!isObj(cur)) return undefined;
    cur = cur[p];
  }
  return cur as JsonValue | undefined;
};
/** A vendor list that arrives as a bare object when it has one entry, or "" when empty. */
const list = (v: unknown): Obj[] => (Array.isArray(v) ? v.filter(isObj) : isObj(v) ? [v] : []);
const text = (v: unknown): string | null => {
  if (v == null) return null;
  const s = String(v).trim();
  return s === "" ? null : s;
};
/** Indian-grouped money strings ("1,12,685"), "" / null → null. */
const money = (v: unknown): number | null => {
  const s = text(v);
  if (s == null) return null;
  const n = Number(s.replace(/,/g, ""));
  return Number.isFinite(n) ? n : null;
};
const pad = (n: number) => String(n).padStart(2, "0");
/** CRIF "dd-MM-yyyy" → ISO. */
const crifDate = (v: unknown): string | null => {
  const m = /^(\d{2})-(\d{2})-(\d{4})$/.exec(text(v) ?? "");
  return m ? `${m[3]}-${m[2]}-${m[1]}` : null;
};
/** Experian "yyyyMMdd" (string or number) → ISO. */
const experianDate = (v: unknown): string | null => {
  const m = /^(\d{4})(\d{2})(\d{2})$/.exec(text(v) ?? "");
  return m ? `${m[1]}-${m[2]}-${m[3]}` : null;
};
const plausibleScore = (v: unknown): number | null => {
  const n = money(v);
  return n != null && n >= 300 && n <= 900 ? Math.round(n) : null;
};

const MONTHS = ["JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC"];

/** CRIF asset classes that mean non-performing regardless of the DPD figure (backend npaClass). */
const crifNpa = (cls: string | null) => {
  const c = (cls ?? "").trim().toUpperCase();
  return c === "SUB" || c === "DBT" || c === "LSS" || c.startsWith("SMA");
};

/** CRIF COMBINED-PAYMENT-HISTORY: "Aug:2026,000/XXX|Jul:2026,027/STD|" — newest first; XXX = not reported. */
export function parseCrifHistory(raw: string | null | undefined): MonthDpd[] {
  if (!raw || !raw.includes(",")) return [];
  const out: MonthDpd[] = [];
  for (const seg of raw.split("|")) {
    const comma = seg.indexOf(",");
    if (comma < 0) continue;
    const [mon, year] = seg.slice(0, comma).trim().split(":");
    const mi = MONTHS.indexOf((mon ?? "").trim().slice(0, 3).toUpperCase());
    const y = Number(year);
    if (mi < 0 || !Number.isInteger(y)) continue;
    const [dpdRaw, cls] = seg.slice(comma + 1).split("/", 2);
    const dpd = /^\d+$/.test((dpdRaw ?? "").trim()) ? Number(dpdRaw.trim()) : null;
    out.push({ month: `${y}-${pad(mi + 1)}`, dpd, npa: crifNpa(cls ?? null) });
  }
  return out;
}

/** Experian Payment_History_Profile bucket → DPD floor (null = not available). Char 0 = `firstMonth`. */
function parseExperianProfile(profile: string | null, firstMonth: string | null): MonthDpd[] {
  if (!profile || profile.trim() === "N" || !firstMonth) return [];
  const [y0, m0] = firstMonth.split("-").map(Number);
  const out: MonthDpd[] = [];
  for (let i = 0; i < profile.length; i++) {
    const c = profile[i];
    const d = new Date(y0, m0 - 1 - i, 1);
    const month = `${d.getFullYear()}-${pad(d.getMonth() + 1)}`;
    const floor = c === "0" || c === "S" ? 0 : "123456".includes(c) ? Number(c) * 30 : null;
    out.push({ month, dpd: floor, npa: "BDM".includes(c) });
  }
  return out;
}

// ---------------------------------------------------------------------------
// Classification
// ---------------------------------------------------------------------------

/** Experian Account_Type codes that are personal loans (5, 41 microfinance-personal, 45 P2P, 69 STPL). */
const EXPERIAN_PERSONAL_CODES = new Set([5, 41, 45, 69]);
/** CRIF CREDIT-GRANTOR-TYPE / Experian ID prefix that mean an NBFC. */
export const NBFC_LENDER_CODES = new Set(["NBF", "NBFC"]);

/**
 * CRIF sends the account type as text: "Personal Loan", "Short Term Personal Loan". "Auto Loan
 * (Personal)" is an auto loan, not a personal loan — the words must appear together, and secured
 * products are excluded outright.
 */
export function isPersonalLoanType(type: string | null): boolean {
  if (!type) return false;
  const t = type.trim();
  if (/^\d+$/.test(t)) return EXPERIAN_PERSONAL_CODES.has(Number(t));
  return /\bpersonal\s+loan\b/i.test(t) && !/auto|two.?wheeler|vehicle|housing|home|gold|property|education|business/i.test(t);
}

function crifOwnership(raw: string | null): Ownership | null {
  if (!raw) return null;
  if (/guarant/i.test(raw)) return "GUARANTOR";
  if (/joint/i.test(raw)) return "JOINT";
  if (/authori[sz]ed|supl|supplement|add.?on/i.test(raw)) return "AUTHORISED_USER";
  if (/individual/i.test(raw)) return "INDIVIDUAL";
  return "OTHER";
}

/** Experian AccountHoldertypeCode (1 individual, 2 joint, 3 authorised user, 7 guarantor). */
function experianOwnership(raw: string | null): { ownership: Ownership | null; label: string | null } {
  if (!raw) return { ownership: null, label: null };
  const code = Number(raw);
  const map: Record<number, [Ownership, string]> = {
    1: ["INDIVIDUAL", "Individual"],
    2: ["JOINT", "Joint"],
    3: ["AUTHORISED_USER", "Authorised user"],
    7: ["GUARANTOR", "Guarantor"],
  };
  const hit = map[code];
  return hit ? { ownership: hit[0], label: `${hit[1]} (code ${raw})` } : { ownership: "OTHER", label: `Holder code ${raw}` };
}

function statusOf(label: string | null, closedOn: string | null): AccountStatus {
  if (closedOn) return "CLOSED";
  if (!label) return "OTHER";
  if (/closed/i.test(label)) return "CLOSED";
  if (/^(active|current|open)/i.test(label)) return "ACTIVE";
  return "OTHER";
}

const EXPERIAN_STATUS: Record<string, string> = {
  "11": "Active", "21": "Active", "13": "Closed", "15": "Closed", "30": "Restructured", "32": "Settled",
  "43": "Written-off", "45": "Post-WO settled", "71": "Delinquent", "78": "Delinquent", "80": "Delinquent",
  "82": "Delinquent", "84": "Delinquent", "97": "Delinquent",
};

// ---------------------------------------------------------------------------
// Shape detection + parsing
// ---------------------------------------------------------------------------

/** The CRIF report object: crif_combine nests it at canonical.data.credit_report, the KBA answer at data. */
function crifRoot(raw: unknown): Obj | null {
  for (const c of [at(raw, "canonical", "data", "credit_report"), at(raw, "data", "credit_report"), at(raw, "data"), at(raw, "canonical", "data")]) {
    if (isObj(c) && (c["PERSONAL-INFO-VARIATION"] !== undefined || c["RESPONSES"] !== undefined)) return c;
  }
  return null;
}

/** The Experian report object across Digitap (INProfileResponse) and Signzy (jsonExperianReport / credit_report). */
function experianRoot(raw: unknown): Obj | null {
  for (const c of [at(raw, "result", "result_json", "INProfileResponse"), at(raw, "data", "jsonExperianReport"), at(raw, "data", "credit_report"), at(raw, "INProfileResponse")]) {
    if (isObj(c) && (c["CAIS_Account"] !== undefined || c["Current_Application"] !== undefined)) return c;
  }
  return null;
}

function parseCrif(r: Obj, applicationId: number): BureauReport {
  const occupations: ReportedValue[] = [];
  const incomes: ReportedValue[] = [];
  for (const e of list(at(r, "EMPLOYMENT-DETAILS", "EMPLOYMENT-DETAIL"))) {
    const occ = text(e["OCCUPATION"]);
    if (occ) occupations.push({ value: occ, reportedOn: crifDate(e["DATE-REPORTED"]), where: `Employment details${text(e["ACCT-TYPE"]) ? ` (${text(e["ACCT-TYPE"])})` : ""}` });
  }
  const accounts: BureauAccount[] = [];
  for (const resp of list(at(r, "RESPONSES", "RESPONSE"))) {
    const l = resp["LOAN-DETAILS"];
    if (!isObj(l)) continue;
    const accountType = text(l["ACCT-TYPE"]) ?? "—";
    const lenderType = text(l["CREDIT-GRANTOR-TYPE"]);
    const closedOn = crifDate(l["CLOSED-DATE"]);
    const statusLabel = text(l["ACCOUNT-STATUS"]);
    const ownershipLabel = text(l["OWNERSHIP-IND"]);
    const lender = text(l["CREDIT-GUARANTOR"]);
    const occ = text(l["OCCUPATION"]);
    if (occ) occupations.push({ value: occ, reportedOn: crifDate(l["DATE-REPORTED"]), where: `${lender ?? "Account"} · ${accountType}` });
    const inc = text(l["INCOME-AMOUNT"]);
    if (inc && money(inc) !== 0) incomes.push({ value: inc, reportedOn: crifDate(l["DATE-REPORTED"]), where: `${lender ?? "Account"}${text(l["INCOME-FREQUENCY"]) ? ` · ${text(l["INCOME-FREQUENCY"])}` : ""}` });
    accounts.push({
      lender,
      accountNumber: text(l["ACCT-NUMBER"]),
      accountType,
      isPersonalLoan: isPersonalLoanType(accountType),
      ownership: crifOwnership(ownershipLabel),
      ownershipLabel,
      lenderType,
      isNbfc: lenderType == null ? null : NBFC_LENDER_CODES.has(lenderType.toUpperCase()),
      status: statusOf(statusLabel, closedOn),
      statusLabel,
      openedOn: crifDate(l["DISBURSED-DT"]),
      closedOn,
      lastReportedOn: crifDate(l["DATE-REPORTED"]),
      overdueRupees: money(l["OVERDUE-AMT"]),
      balanceRupees: money(l["CURRENT-BAL"]),
      history: parseCrifHistory(text(l["COMBINED-PAYMENT-HISTORY"])),
      historyExact: true,
      occupation: occ,
    });
  }
  const addresses = list(at(r, "PERSONAL-INFO-VARIATION", "ADDRESS-VARIATIONS", "VARIATION"))
    .map((v) => ({ value: text(v["VALUE"]), reportedOn: crifDate(v["REPORTED-DATE"]), where: "Address on bureau file" }))
    .filter((v): v is ReportedValue => v.value != null);
  const score = list(at(r, "SCORES", "SCORE")).map((s) => plausibleScore(s["SCORE-VALUE"])).find((s) => s != null) ?? null;
  return {
    kind: "CRIF",
    source: "RAW",
    applicationId,
    reportDate: crifDate(at(r, "HEADER", "DATE-OF-ISSUE")) ?? crifDate(at(r, "HEADER", "DATE-OF-REQUEST")),
    score,
    accounts,
    occupations,
    incomes,
    addresses,
  };
}

function parseExperian(r: Obj, applicationId: number): BureauReport {
  const occupations: ReportedValue[] = [];
  const incomes: ReportedValue[] = [];
  const other = at(r, "Current_Application", "Current_Application_Details", "Current_Other_Details");
  const empStatus = text(at(other, "Employment_Status"));
  if (empStatus) occupations.push({ value: empStatus, reportedOn: null, where: "Applicant employment status" });
  const appIncome = text(at(other, "Income"));
  if (appIncome && money(appIncome) !== 0) incomes.push({ value: appIncome, reportedOn: null, where: "Applicant income" });

  const accounts: BureauAccount[] = [];
  for (const a of list(at(r, "CAIS_Account", "CAIS_Account_DETAILS"))) {
    const typeCode = text(a["Account_Type"]);
    const idPrefix = text(a["Identification_Number"])?.slice(0, 3).toUpperCase() ?? null;
    const closedOn = experianDate(a["Date_Closed"]);
    const statusCode = text(a["Account_Status"]);
    const statusLabel = statusCode ? (EXPERIAN_STATUS[String(Number(statusCode))] ?? `Status ${statusCode}`) : null;
    const lender = text(a["Subscriber_Name"]);
    const { ownership, label } = experianOwnership(text(a["AccountHoldertypeCode"]));
    const occ = text(a["Occupation_Code"]);
    const occLabel = occ == null ? null : occ.toUpperCase() === "S" ? "Salaried (code S)" : `Occupation code ${occ}`;
    if (occLabel) occupations.push({ value: occLabel, reportedOn: experianDate(a["Date_Reported"]), where: `${lender ?? "Account"} · ${accountTypeLabel(typeCode)}` });
    const inc = text(a["Income"]);
    if (inc && money(inc) !== 0) incomes.push({ value: inc, reportedOn: experianDate(a["Date_Reported"]), where: lender ?? "Account" });
    const dated = list(a["CAIS_Account_History"])
      .map((h) => {
        const y = Number(text(h["Year"]));
        const m = Number(text(h["Month"]));
        if (!Number.isInteger(y) || !Number.isInteger(m) || m < 1 || m > 12) return null;
        const dpdRaw = text(h["Days_Past_Due"]);
        const cls = (text(h["Asset_Classification"]) ?? "").toUpperCase();
        return { month: `${y}-${pad(m)}`, dpd: dpdRaw != null && /^\d+$/.test(dpdRaw) ? Number(dpdRaw) : null, npa: "BDM".includes(cls) && cls !== "" };
      })
      .filter((h): h is MonthDpd => h != null)
      .sort((x, y) => (x.month < y.month ? 1 : x.month > y.month ? -1 : 0));
    const reported = experianDate(a["Date_Reported"]);
    accounts.push({
      lender,
      accountNumber: text(a["Account_Number"]),
      accountType: accountTypeLabel(typeCode),
      isPersonalLoan: isPersonalLoanType(typeCode),
      ownership,
      ownershipLabel: label,
      lenderType: idPrefix,
      isNbfc: idPrefix == null ? null : NBFC_LENDER_CODES.has(idPrefix),
      status: statusLabel === "Closed" || closedOn ? "CLOSED" : statusLabel === "Active" ? "ACTIVE" : "OTHER",
      statusLabel,
      openedOn: experianDate(a["Open_Date"]),
      closedOn,
      lastReportedOn: reported,
      overdueRupees: money(a["Amount_Past_Due"]),
      balanceRupees: money(a["Current_Balance"]),
      history: dated.length > 0 ? dated : parseExperianProfile(text(a["Payment_History_Profile"]), reported?.slice(0, 7) ?? null),
      historyExact: dated.length > 0,
      occupation: occLabel,
    });
  }
  const score =
    plausibleScore(at(r, "SCORE", "BureauScore")) ?? plausibleScore(at(r, "SCORE", "FCIREXScore"));
  return {
    kind: "EXPERIAN",
    source: "RAW",
    applicationId,
    reportDate: experianDate(at(r, "Header", "ReportDate")) ?? experianDate(at(r, "CreditProfileHeader", "ReportDate")),
    score,
    accounts,
    occupations,
    incomes,
    addresses: [],
  };
}

/** Parse a raw bureau `providerResponse`; null for a non-report shape (REVIEW snapshot, KBA challenge, null). */
export function parseBureauReport(raw: JsonValue | null | undefined, applicationId: number): BureauReport | null {
  if (raw == null) return null;
  const crif = crifRoot(raw);
  if (crif) return parseCrif(crif, applicationId);
  const exp = experianRoot(raw);
  if (exp) return parseExperian(exp, applicationId);
  return null;
}

/**
 * Rebuild a report from the parsed tradelines when the raw response is gone. Lender type survives
 * for CRIF only (portfolioTypeCode carries CREDIT-GRANTOR-TYPE there); ownership never does.
 */
export function reportFromTradelines(brief: CreditBriefView | null | undefined): BureauReport | null {
  const tradelines = brief?.facts?.detail?.tradelines;
  if (!brief || !tradelines || tradelines.length === 0) return null;
  const crif = tradelines.some((t) => (t.paymentHistory ?? "").includes(",")) || /CRIF/i.test(brief.bureauSource ?? "");
  const accounts: BureauAccount[] = tradelines.map((t: Tradeline) => {
    const lenderType = crif ? t.portfolioTypeCode : null;
    const statusLabel = crif ? t.accountStatusCode : t.accountStatusCode ? (EXPERIAN_STATUS[String(Number(t.accountStatusCode))] ?? `Status ${t.accountStatusCode}`) : null;
    const reported = t.dateReported ?? null;
    return {
      lender: t.lender,
      accountNumber: t.accountNumberMasked,
      accountType: accountTypeLabel(t.accountTypeCode),
      isPersonalLoan: isPersonalLoanType(t.accountTypeCode),
      ownership: null,
      ownershipLabel: null,
      lenderType,
      isNbfc: lenderType == null ? null : NBFC_LENDER_CODES.has(lenderType.toUpperCase()),
      status: crif ? statusOf(statusLabel, t.closedOn) : t.closedOn || statusLabel === "Closed" ? "CLOSED" : statusLabel === "Active" ? "ACTIVE" : "OTHER",
      statusLabel,
      openedOn: t.openedOn,
      closedOn: t.closedOn,
      lastReportedOn: reported,
      overdueRupees: t.amountPastDueRupees,
      balanceRupees: t.currentBalanceRupees,
      history: crif ? parseCrifHistory(t.paymentHistory) : parseExperianProfile(t.paymentHistory, (reported ?? t.closedOn)?.slice(0, 7) ?? null),
      historyExact: crif,
      occupation: null,
    };
  });
  return {
    kind: crif ? "CRIF" : "EXPERIAN",
    source: "PARSED",
    applicationId: brief.applicationId,
    reportDate: null,
    score: brief.creditScore ?? brief.facts?.creditScore ?? null,
    accounts,
    occupations: [],
    incomes: [],
    addresses: [],
  };
}

// ---------------------------------------------------------------------------
// The 9-month window
// ---------------------------------------------------------------------------

export const LOOKBACK_MONTHS = 9;

export interface Window {
  /** ISO date the window ends on (the report date, else the pull date, else today). */
  end: string;
  /** ISO date LOOKBACK_MONTHS before `end`. */
  start: string;
  /** YYYY-MM of `start` — a reported month counts when it is at or after this. */
  startMonth: string;
}

const iso = (d: Date) => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;

/** The window ends on the report's own date: a report pulled in March judges March, not today. */
export function lookbackWindow(report: BureauReport | null, generatedAt: string | null | undefined, today: Date): Window {
  const endIso = report?.reportDate ?? (generatedAt ? generatedAt.slice(0, 10) : null) ?? iso(today);
  const [y, m, d] = endIso.split("-").map(Number);
  const start = new Date(y, m - 1 - LOOKBACK_MONTHS, 1);
  // Clamp the day to the target month's length (31 May − 9 months = 31 Aug, but 31 Mar − 1 = 28/29 Feb).
  const lastDay = new Date(start.getFullYear(), start.getMonth() + 1, 0).getDate();
  start.setDate(Math.min(d, lastDay));
  return { end: endIso, start: iso(start), startMonth: iso(start).slice(0, 7) };
}

/** Was the account open at any point inside the window (active now, or closed on/after its start)? */
export function inWindow(a: BureauAccount, w: Window): boolean {
  if (a.openedOn && a.openedOn > w.end) return false;
  if (a.status === "CLOSED") {
    const closed = a.closedOn ?? a.lastReportedOn;
    return closed != null && closed >= w.start;
  }
  return true;
}

/** Months of an account's history that fall inside the window. */
export function windowHistory(a: BureauAccount, w: Window): MonthDpd[] {
  const endMonth = w.end.slice(0, 7);
  return a.history.filter((h) => h.month >= w.startMonth && h.month <= endMonth);
}

/** Worst reported DPD inside the window; null when no month in the window was reported. */
export function worstDpdInWindow(a: BureauAccount, w: Window): number | null {
  const known = windowHistory(a, w).filter((h) => h.dpd != null).map((h) => h.dpd as number);
  return known.length === 0 ? null : Math.max(...known);
}
