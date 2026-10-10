/**
 * BRE — the Business Rule Engine shown on the staff Customer 360 "BRE" tab.
 *
 * Nine credit-policy rules evaluated in the browser from data the platform already holds (bureau
 * report, verification rows, profile, documents). **Flags only**: nothing here rejects, blocks or
 * changes a file — CLAUDE.md §14 "no verification hard-blocks a borrower".
 *
 * Every rule is a list of checks, each PASS / FLAG / MANUAL / UNKNOWN:
 *  - MANUAL  — the evidence exists but only a person can read it (no OCR of card photos, nothing is
 *              extracted from salary slips today).
 *  - UNKNOWN — the data has not arrived (bureau not pulled, DigiLocker runs after sanction…). Absent
 *              is never clean and never a fail.
 * Informational checks are shown but do not decide the rule. A rule rolls up as
 * FLAG > UNKNOWN > MANUAL > PASS.
 */

import type { BureauState, DocumentView, ProfileView, StepResult } from "@/lib/api/applications";
import { paiseToINR } from "@/lib/api/applications";
import {
  inWindow,
  lookbackWindow,
  windowHistory,
  worstDpdInWindow,
  LOOKBACK_MONTHS,
  type BureauAccount,
  type BureauReport,
  type Window,
} from "@/lib/customers/bre/bureau-report";
import { employerNamesAgree, matchesMasked, nameSimilarity, NAME_MATCH_THRESHOLD } from "@/lib/customers/bre/matching";
import { analyseProfile, type ProfileAnalysis } from "@/lib/customers/bre/profile-analysis";
import { maskAadhaar } from "@/lib/aadhaar";

export type BreOutcome = "PASS" | "FLAG" | "MANUAL" | "UNKNOWN";

export const MIN_CREDIT_SCORE = 600;
export const MAX_NBFC_DPD_DAYS = 10;
/** Two salary figures "match" when they are within this fraction of the declared salary. */
export const SALARY_MATCH_TOLERANCE = 0.1;

export interface BreCheck {
  label: string;
  outcome: BreOutcome;
  detail?: string;
  /** Shown, but does not decide the rule. */
  informational?: boolean;
}

export interface BreEvidence {
  label: string;
  value: string | null;
  mono?: boolean;
}

/** One side of a side-by-side comparison (rules 6-9). `docTypes` = documents a reviewer can open. */
export interface BreCompare {
  label: string;
  value: string | null;
  outcome: BreOutcome;
  note?: string;
  docTypes?: string[];
}

export interface BreAccountRow {
  lender: string | null;
  accountType: string;
  status: string;
  openedOn: string | null;
  closedOn: string | null;
  ownership: string | null;
  lenderType: string | null;
  worstDpd: number | null;
  /** The account itself breaks the rule (guarantor, DPD over the limit…). */
  flagged: boolean;
}

export interface BreRule {
  id: number;
  key: string;
  title: string;
  /** The policy, in one sentence. */
  policy: string;
  outcome: BreOutcome;
  summary: string;
  checks: BreCheck[];
  evidence: BreEvidence[];
  compare?: BreCompare[];
  accounts?: BreAccountRow[];
  /** Customer-360 tab holding the underlying data. */
  sourceTab?: string;
}

export interface BreInput {
  today: Date;
  profile: ProfileView | null;
  /** Newest row per check type across the customer's applications (reborrows split evidence). */
  steps: readonly StepResult[];
  report: BureauReport | null;
  bureau: {
    state: BureauState | null;
    score: number | null;
    source: string | null;
    generatedAt: string | null;
  };
  /** The typed 12-digit Aadhaar (profile, else an earlier application's profile). */
  aadhaar: string | null;
  documents: readonly { applicationId: number; doc: DocumentView }[];
  /** Bank-statement analyser salary (paise); null until the analyser API is live. */
  bankSalaryPaise: number | null;
  bankAnalyserLive: boolean;
  /** Staging only (NEXT_PUBLIC_BRE_STAGING_PASS=true): rules in STAGING_PASS_RULES report PASS. */
  stagingPass?: boolean;
}

/** Rules a staging build reports as passed. Rules 2, 4, 7 and 9 always evaluate for real. */
export const STAGING_PASS_RULES: readonly number[] = [1, 3, 5, 6, 8];

function stagingPassed(r: BreRule): BreRule {
  return { ...r, outcome: "PASS", summary: "Validated", checks: r.checks.map((c) => ({ ...c, outcome: "PASS" })), compare: r.compare?.map((c) => ({ ...c, outcome: "PASS" })), accounts: r.accounts?.map((a) => ({ ...a, flagged: false })) };
}

export interface BreResult {
  rules: BreRule[];
  analysis: ProfileAnalysis;
  window: Window;
  counts: Record<BreOutcome, number>;
}

// ---------------------------------------------------------------------------
// helpers
// ---------------------------------------------------------------------------

const str = (v: unknown): string | null => (v == null || String(v).trim() === "" ? null : String(v).trim());
const stepOf = (steps: readonly StepResult[], type: string) => steps.find((s) => s.checkType === type);
const derivedOf = (steps: readonly StepResult[], type: string) => (stepOf(steps, type)?.derived ?? {}) as Record<string, unknown>;
const bool = (v: unknown): boolean | null => (typeof v === "boolean" ? v : null);
const ok = (b: boolean | null, pass = "PASS" as BreOutcome): BreOutcome => (b == null ? "UNKNOWN" : b ? pass : "FLAG");
const docsOf = (input: BreInput, types: readonly string[]) => input.documents.filter((d) => types.includes(d.doc.docType.toUpperCase()));

export function rollup(checks: readonly BreCheck[]): BreOutcome {
  const deciding = checks.filter((c) => !c.informational);
  if (deciding.some((c) => c.outcome === "FLAG")) return "FLAG";
  if (deciding.length === 0 || deciding.some((c) => c.outcome === "UNKNOWN")) return "UNKNOWN";
  if (deciding.some((c) => c.outcome === "MANUAL")) return "MANUAL";
  return "PASS";
}

const SUMMARY: Record<BreOutcome, string> = {
  PASS: "Validated",
  FLAG: "Flagged for review",
  MANUAL: "Automated checks clear — needs a manual look",
  UNKNOWN: "Not enough data to validate yet",
};

function rule(r: Omit<BreRule, "outcome" | "summary"> & { summary?: string }): BreRule {
  const outcome = rollup(r.checks);
  const firstFlag = r.checks.find((c) => !c.informational && c.outcome === "FLAG");
  return { ...r, outcome, summary: r.summary ?? (firstFlag ? `${firstFlag.label}${firstFlag.detail ? ` — ${firstFlag.detail}` : ""}` : SUMMARY[outcome]) };
}

const monthLabel = (iso: string) => {
  const [y, m] = iso.split("-").map(Number);
  return new Date(y, m - 1, 1).toLocaleDateString("en-IN", { month: "short", year: "numeric" });
};
const windowLabel = (w: Window) => `${monthLabel(w.start)} – ${monthLabel(w.end)}`;
const bureauName = (input: BreInput) =>
  input.report ? (input.report.kind === "CRIF" ? "CRIF" : "Experian") : /crif/i.test(input.bureau.source ?? "") ? "CRIF" : /experian/i.test(input.bureau.source ?? "") ? "Experian" : "Bureau";
const ownershipText = (a: BureauAccount) => a.ownershipLabel ?? (a.ownership ? a.ownership.replace(/_/g, " ").toLowerCase() : null);

function row(a: BureauAccount, w: Window, flagged = false): BreAccountRow {
  return {
    lender: a.lender,
    accountType: a.accountType,
    status: a.statusLabel ?? a.status.toLowerCase(),
    openedOn: a.openedOn,
    closedOn: a.closedOn,
    ownership: ownershipText(a),
    lenderType: a.lenderType,
    worstDpd: worstDpdInWindow(a, w),
    flagged,
  };
}

/** The "no report" check every bureau rule shares: a confirmed no-hit fails, not-yet-pulled is unknown. */
function noReportCheck(input: BreInput, label: string): BreCheck {
  return input.bureau.state === "NO_RECORD"
    ? { label, outcome: "FLAG", detail: "No bureau record found for this borrower" }
    : { label, outcome: "UNKNOWN", detail: "Bureau report not available yet" };
}

// ---------------------------------------------------------------------------
// rules
// ---------------------------------------------------------------------------

function r1Score(input: BreInput): BreRule {
  const score = input.bureau.score ?? input.report?.score ?? input.profile?.creditScore ?? null;
  const identityMismatch = str(derivedOf(input.steps, "BUREAU").identityMismatch);
  const checks: BreCheck[] = [
    score == null
      ? noReportCheck(input, `Credit score above ${MIN_CREDIT_SCORE}`)
      : {
          label: `Credit score above ${MIN_CREDIT_SCORE}`,
          outcome: score > MIN_CREDIT_SCORE ? "PASS" : "FLAG",
          detail: `${bureauName(input)} score ${score}`,
        },
  ];
  if (identityMismatch) checks.push({ label: "Bureau report belongs to this borrower", outcome: "FLAG", detail: identityMismatch });
  return rule({
    id: 1,
    key: "credit-score",
    title: `Credit score above ${MIN_CREDIT_SCORE}`,
    policy: `The bureau credit score must be more than ${MIN_CREDIT_SCORE}.`,
    checks,
    evidence: [
      { label: "Score", value: score == null ? null : String(score), mono: true },
      { label: "Threshold", value: `> ${MIN_CREDIT_SCORE}` },
      { label: "Bureau", value: bureauName(input) },
      { label: "Report date", value: input.report?.reportDate ?? input.bureau.generatedAt?.slice(0, 10) ?? null },
    ],
    sourceTab: "credit",
  });
}

const personalLoansInWindow = (input: BreInput, w: Window) => (input.report?.accounts ?? []).filter((a) => a.isPersonalLoan && inWindow(a, w));
const nbfcLoansInWindow = (input: BreInput, w: Window) => (input.report?.accounts ?? []).filter((a) => a.isNbfc === true && inWindow(a, w));

function r2PersonalLoan(input: BreInput, w: Window): BreRule {
  const label = `Personal loan active or closed in the last ${LOOKBACK_MONTHS} months`;
  const pls = personalLoansInWindow(input, w);
  const allPls = (input.report?.accounts ?? []).filter((a) => a.isPersonalLoan);
  const checks: BreCheck[] = [
    !input.report
      ? noReportCheck(input, label)
      : pls.length > 0
        ? { label, outcome: "PASS", detail: `${pls.length} personal loan${pls.length === 1 ? "" : "s"} in ${windowLabel(w)}` }
        : { label, outcome: "FLAG", detail: allPls.length > 0 ? `${allPls.length} personal loan(s) on file, none in ${windowLabel(w)}` : "No personal loan on the bureau report" },
  ];
  return rule({
    id: 2,
    key: "personal-loan",
    title: `Personal loan in the last ${LOOKBACK_MONTHS} months`,
    policy: `At least one personal loan, active or closed, in the ${LOOKBACK_MONTHS} months up to the bureau report.`,
    checks,
    evidence: [
      { label: "Window", value: windowLabel(w) },
      { label: "Personal loans in window", value: input.report ? String(pls.length) : null, mono: true },
      { label: "Personal loans (all time)", value: input.report ? String(allPls.length) : null, mono: true },
      { label: "Accounts on report", value: input.report ? String(input.report.accounts.length) : null, mono: true },
    ],
    accounts: pls.map((a) => row(a, w)),
    sourceTab: "credit",
  });
}

function r3Ownership(input: BreInput, w: Window): BreRule {
  const label = "Loans are held individually, not as guarantor";
  const considered = [...new Set([...personalLoansInWindow(input, w), ...nbfcLoansInWindow(input, w)])];
  const nonIndividual = considered.filter((a) => a.ownership != null && a.ownership !== "INDIVIDUAL");
  const unknown = considered.filter((a) => a.ownership == null);
  const guarantorElsewhere = (input.report?.accounts ?? []).filter((a) => a.ownership === "GUARANTOR" && !considered.includes(a));
  const checks: BreCheck[] = [
    !input.report
      ? noReportCheck(input, label)
      : considered.length === 0
        ? { label, outcome: "UNKNOWN", detail: `No personal or NBFC loan in the last ${LOOKBACK_MONTHS} months to check` }
        : nonIndividual.length > 0
          ? { label, outcome: "FLAG", detail: nonIndividual.map((a) => `${a.lender ?? "Account"}: ${ownershipText(a)}`).join("; ") }
          : unknown.length > 0
            ? { label, outcome: "UNKNOWN", detail: input.report.source === "PARSED" ? "Ownership is only in the raw bureau report, which is not stored for this file" : `${unknown.length} loan(s) report no ownership` }
            : { label, outcome: "PASS", detail: `All ${considered.length} loan(s) held individually` },
  ];
  if (guarantorElsewhere.length > 0) {
    checks.push({ label: "Guarantor on other accounts", outcome: "FLAG", detail: `${guarantorElsewhere.length} other account(s) list the borrower as guarantor`, informational: true });
  }
  return rule({
    id: 3,
    key: "ownership",
    title: "Individual ownership, not guarantor",
    policy: "The loans relied on (rules 2 and 4) must be the borrower's own — individual ownership, not as a guarantor.",
    checks,
    evidence: [
      { label: "Loans checked", value: input.report ? String(considered.length) : null, mono: true },
      { label: "Individual", value: input.report ? String(considered.filter((a) => a.ownership === "INDIVIDUAL").length) : null, mono: true },
      { label: "Guarantor / joint", value: input.report ? String(nonIndividual.length) : null, mono: true },
      { label: "Source", value: input.report ? (input.report.source === "RAW" ? `${bureauName(input)} report` : "Parsed tradelines (no ownership)") : null },
    ],
    accounts: considered.map((a) => row(a, w, a.ownership != null && a.ownership !== "INDIVIDUAL")),
    sourceTab: "credit",
  });
}

function r4Nbfc(input: BreInput, w: Window): BreRule {
  const hasLabel = `NBFC loan in the last ${LOOKBACK_MONTHS} months`;
  const dpdLabel = `No DPD over ${MAX_NBFC_DPD_DAYS} days on those NBFC loans`;
  const report = input.report;
  const nbfc = nbfcLoansInWindow(input, w);
  const lenderTypeKnown = (report?.accounts ?? []).some((a) => a.isNbfc != null);
  const checks: BreCheck[] = [];
  if (!report) {
    checks.push(noReportCheck(input, hasLabel));
  } else if (nbfc.length > 0) {
    checks.push({ label: hasLabel, outcome: "PASS", detail: `${nbfc.length} NBFC loan${nbfc.length === 1 ? "" : "s"} in ${windowLabel(w)}` });
    const breaches: string[] = [];
    let unclear = 0;
    let unseen = 0;
    for (const a of nbfc) {
      const months = windowHistory(a, w);
      const bad = months.filter((h) => h.npa || (h.dpd != null && h.dpd > MAX_NBFC_DPD_DAYS));
      if (bad.length > 0) {
        const worst = bad.reduce((x, y) => ((y.dpd ?? 0) > (x.dpd ?? 0) ? y : x));
        breaches.push(`${a.lender ?? "NBFC"}: ${worst.npa && (worst.dpd ?? 0) <= MAX_NBFC_DPD_DAYS ? "non-performing" : `${worst.dpd} DPD`} in ${monthLabel(`${worst.month}-01`)}`);
      } else if (months.every((h) => h.dpd == null)) {
        unseen++;
      } else if (!a.historyExact) {
        unclear++;
      }
    }
    checks.push(
      breaches.length > 0
        ? { label: dpdLabel, outcome: "FLAG", detail: breaches.join("; ") }
        : unseen > 0
          ? { label: dpdLabel, outcome: "UNKNOWN", detail: `${unseen} NBFC loan(s) report no payment history in the window` }
          : unclear > 0
            ? { label: dpdLabel, outcome: "MANUAL", detail: "Experian reports 0–29 DPD as one bucket — confirm none exceeded 10 days" }
            : { label: dpdLabel, outcome: "PASS", detail: `Worst ${Math.max(0, ...nbfc.map((a) => worstDpdInWindow(a, w) ?? 0))} DPD in the window` },
    );
  } else if (!lenderTypeKnown) {
    checks.push({ label: hasLabel, outcome: "UNKNOWN", detail: "This bureau report does not say which lenders are NBFCs" });
  } else {
    checks.push({ label: hasLabel, outcome: "FLAG", detail: `No NBFC loan in ${windowLabel(w)}` });
  }
  return rule({
    id: 4,
    key: "nbfc",
    title: `NBFC loan in the last ${LOOKBACK_MONTHS} months, no DPD over ${MAX_NBFC_DPD_DAYS} days`,
    policy: `There must be an NBFC loan in the last ${LOOKBACK_MONTHS} months, and none of those NBFC loans may have been more than ${MAX_NBFC_DPD_DAYS} days past due in that period.`,
    checks,
    evidence: [
      { label: "Window", value: windowLabel(w) },
      { label: "NBFC loans in window", value: report ? String(nbfc.length) : null, mono: true },
      { label: "NBFC loans (all time)", value: report ? String(report.accounts.filter((a) => a.isNbfc === true).length) : null, mono: true },
      { label: "Worst NBFC DPD (window)", value: nbfc.length > 0 ? String(Math.max(0, ...nbfc.map((a) => worstDpdInWindow(a, w) ?? 0))) : null, mono: true },
    ],
    accounts: nbfc.map((a) => {
      const worst = worstDpdInWindow(a, w);
      return row(a, w, (worst != null && worst > MAX_NBFC_DPD_DAYS) || windowHistory(a, w).some((h) => h.npa));
    }),
    sourceTab: "credit",
  });
}

function r5Occupation(input: BreInput, analysis: ProfileAnalysis): BreRule {
  const declared = str(input.profile?.employmentStatus)?.toUpperCase().replace(/[\s-]+/g, "_") ?? null;
  const checks: BreCheck[] = [
    {
      label: "Declared salaried, not self-employed",
      outcome: declared == null ? "UNKNOWN" : declared.includes("SELF") ? "FLAG" : "PASS",
      detail: declared == null ? "No employment type on file" : `Declared ${declared.replace(/_/g, " ").toLowerCase()}`,
    },
    analysis.bureauEmployment == null
      ? { label: "Bureau occupation is salaried", outcome: "UNKNOWN", detail: "The bureau reports no occupation", informational: true }
      : {
          label: "Bureau occupation is salaried",
          outcome: analysis.bureauEmployment === "SALARIED" ? "PASS" : "FLAG",
          detail: `${bureauName(input)} reports ${analysis.bureauEmployment === "SALARIED" ? "salaried" : "self-employed"}`,
        },
  ];
  const professionHits = analysis.hits.filter((h) => h.category !== "SELF_EMPLOYED");
  checks.push(
    professionHits.length > 0
      ? { label: "Not army, lawyer or Ministry of Home Affairs", outcome: "FLAG", detail: professionHits.map((h) => `${h.label} (${h.source}: "${h.value}")`).join("; ") }
      : { label: "Not army, lawyer or Ministry of Home Affairs", outcome: "PASS", detail: `${analysis.sources.length} source(s) scanned` },
  );
  const selfHits = analysis.hits.filter((h) => h.category === "SELF_EMPLOYED" && !h.source.startsWith("Declared"));
  if (selfHits.length > 0 && analysis.bureauEmployment !== "SELF_EMPLOYED") {
    checks.push({ label: "No self-employment signal elsewhere", outcome: "FLAG", detail: selfHits.map((h) => `${h.source}: "${h.value}"`).join("; ") });
  }
  return rule({
    id: 5,
    key: "occupation",
    title: "Not self-employed, army, lawyer or MHA",
    policy: "No loans to the self-employed, the army, lawyers/advocates or the Ministry of Home Affairs. The bank statement analyser is not live, so this reads the CRIF report and KYC data.",
    checks,
    evidence: [
      { label: "Declared employment", value: input.profile?.employmentStatus ?? null },
      { label: "Declared employer", value: str(input.profile?.employer) },
      { label: "EPFO employer", value: str(derivedOf(input.steps, "EMPLOYMENT").employerName) },
      { label: `${bureauName(input)} occupation`, value: [...new Set((input.report?.occupations ?? []).map((o) => o.value))].join(", ") || null },
    ],
    sourceTab: "banking",
  });
}

const CARD_TYPES = ["AADHAAR_CARD_FRONT", "AADHAAR_CARD_BACK", "PAN_CARD_FRONT", "PAN_CARD_BACK"];
const AADHAAR_CARD_TYPES = ["AADHAAR_CARD_FRONT", "AADHAAR_CARD_BACK", "AADHAAR_FRONT", "AADHAAR_BACK"];

function r6AadhaarPan(input: BreInput): BreRule {
  const pan = derivedOf(input.steps, "PAN");
  const aad = derivedOf(input.steps, "AADHAAR");
  const linked = bool(pan.aadhaarLinked) ?? input.profile?.aadhaarLinked ?? null;
  const panName = str(pan.fullName) ?? str(input.profile?.fullName);
  const aadhaarName = str(aad.fullName);
  const sim = panName && aadhaarName ? nameSimilarity(panName, aadhaarName) : null;
  const cards = docsOf(input, [...CARD_TYPES, "AADHAAR_FRONT", "AADHAAR_BACK"]);
  const cardCheck: BreCheck =
    cards.length > 0
      ? { label: "Name on the uploaded Aadhaar and PAN card pictures", outcome: "MANUAL", detail: `Compare the name on ${cards.length} uploaded card image(s) — pictures are not read automatically` }
      : { label: "Name on the uploaded Aadhaar and PAN card pictures", outcome: "UNKNOWN", detail: "No card pictures uploaded" };
  const checks: BreCheck[] = [
    { label: "Aadhaar linked to PAN", outcome: ok(linked), detail: linked == null ? "PAN check has not reported the link yet" : linked ? "Linked (PAN provider)" : "PAN provider reports not linked" },
    sim == null
      ? { label: "Name on PAN matches name on Aadhaar", outcome: "UNKNOWN", detail: aadhaarName ? "No PAN name on file" : "Aadhaar name arrives with DigiLocker, after sanction" }
      : { label: "Name on PAN matches name on Aadhaar", outcome: sim >= NAME_MATCH_THRESHOLD ? "PASS" : "FLAG", detail: `${Math.round(sim * 100)}% match` },
    cardCheck,
  ];
  return rule({
    id: 6,
    key: "aadhaar-pan",
    title: "Aadhaar–PAN linkage and name",
    policy: "Aadhaar must be linked to the PAN, and the Aadhaar must carry the same name as the PAN and the uploaded card pictures.",
    checks,
    evidence: [
      { label: "PAN", value: str(pan.panNumber) ?? str(input.profile?.pan), mono: true },
      { label: "Aadhaar", value: input.aadhaar ?? str(aad.maskedAadhaar), mono: true },
      { label: "Linked", value: linked == null ? null : linked ? "Yes" : "No" },
      { label: "Name match", value: sim == null ? null : `${Math.round(sim * 100)}%` },
    ],
    compare: [
      { label: "Name on PAN record", value: panName, outcome: panName ? "PASS" : "UNKNOWN" },
      { label: "Name on Aadhaar (DigiLocker)", value: aadhaarName, outcome: sim == null ? "UNKNOWN" : sim >= NAME_MATCH_THRESHOLD ? "PASS" : "FLAG", note: sim == null ? undefined : `${Math.round(sim * 100)}% match` },
      { label: "Name on card pictures", value: null, outcome: cardCheck.outcome, note: cards.length > 0 ? "Open and compare" : "Not uploaded", docTypes: CARD_TYPES },
    ],
    sourceTab: "third-party",
  });
}

function r7UanEmployer(input: BreInput): BreRule {
  const emp = stepOf(input.steps, "EMPLOYMENT");
  const d = (emp?.derived ?? {}) as Record<string, unknown>;
  const providerError = d.providerError === true;
  const epfoUan = str(d.uan);
  const typedUan = str(input.profile?.uan);
  const declaredEmployer = str(input.profile?.employer) ?? str(d.declaredEmployer);
  const epfoEmployer = str(d.employerName);
  const employerAgree = declaredEmployer && epfoEmployer ? d.employerNameMatch === true || employerNamesAgree(declaredEmployer, epfoEmployer) : null;
  const slips = docsOf(input, ["SALARY_SLIP"]);
  const emailMatch = bool(derivedOf(input.steps, "EMAIL").establishmentMatched);
  const checks: BreCheck[] = [
    !emp
      ? { label: "UAN found on EPFO", outcome: "UNKNOWN", detail: "EPFO check has not run" }
      : providerError
        ? { label: "UAN found on EPFO", outcome: "UNKNOWN", detail: "EPFO was unavailable — retry from Verifications" }
        : { label: "UAN found on EPFO", outcome: d.found === true && epfoUan ? "PASS" : d.found === false ? "FLAG" : "UNKNOWN", detail: d.found === false ? "No EPFO record for this borrower" : epfoUan ? `UAN ${epfoUan}` : undefined },
    d.found === true && typeof d.employed === "boolean"
      ? { label: "Currently employed (no EPFO exit)", outcome: d.employed ? "PASS" : "FLAG", detail: d.employed ? undefined : `Exit recorded${str(d.dateOfExit) ? ` on ${str(d.dateOfExit)}` : ""}` }
      : { label: "Currently employed (no EPFO exit)", outcome: "UNKNOWN", informational: true },
    typedUan && epfoUan
      ? { label: "Declared UAN matches EPFO UAN", outcome: typedUan.replace(/\D/g, "") === epfoUan.replace(/\D/g, "") ? "PASS" : "FLAG", detail: `Declared ${typedUan}, EPFO ${epfoUan}` }
      : { label: "Declared UAN matches EPFO UAN", outcome: "UNKNOWN", detail: typedUan ? "No EPFO UAN to compare" : "Borrower did not enter a UAN", informational: true },
    employerAgree == null
      ? { label: "Company name matches EPFO employer", outcome: "UNKNOWN", detail: declaredEmployer ? "No EPFO employer on file" : "No declared employer" }
      : { label: "Company name matches EPFO employer", outcome: employerAgree ? "PASS" : "FLAG", detail: `Declared "${declaredEmployer}" vs EPFO "${epfoEmployer}"` },
    { label: "Official email matches the employer", outcome: ok(emailMatch), detail: emailMatch == null ? "Email employer check has not run" : undefined, informational: true },
    slips.length > 0
      ? { label: "UAN and employer on the salary slips", outcome: "MANUAL", detail: `Check ${slips.length} salary slip(s) — slips are not read automatically` }
      : { label: "UAN and employer on the salary slips", outcome: "UNKNOWN", detail: "No salary slips uploaded" },
  ];
  return rule({
    id: 7,
    key: "uan-employer",
    title: "UAN and company name",
    policy: "The UAN must be verified on EPFO and against the salary slips, and the company name must match.",
    checks,
    evidence: [
      { label: "EPFO UAN", value: epfoUan, mono: true },
      { label: "Declared UAN", value: typedUan, mono: true },
      { label: "Joined", value: str(d.dateOfJoining) },
      { label: "Salary slips", value: String(slips.length), mono: true },
    ],
    compare: [
      { label: "Declared employer", value: declaredEmployer, outcome: declaredEmployer ? "PASS" : "UNKNOWN" },
      { label: "EPFO employer", value: epfoEmployer, outcome: employerAgree == null ? "UNKNOWN" : employerAgree ? "PASS" : "FLAG" },
      { label: "Salary slips", value: slips.length > 0 ? `${slips.length} on file` : null, outcome: slips.length > 0 ? "MANUAL" : "UNKNOWN", note: slips.length > 0 ? "Open and compare UAN + employer" : "Not uploaded", docTypes: ["SALARY_SLIP"] },
    ],
    sourceTab: "third-party",
  });
}

function r8AadhaarTriangle(input: BreInput): BreRule {
  const aad = derivedOf(input.steps, "AADHAAR");
  const pan = derivedOf(input.steps, "PAN");
  const typed = input.aadhaar;
  const digilocker = str(aad.maskedAadhaar);
  const panMask = str(pan.maskedAadhaar);
  const vsDigilocker = matchesMasked(typed, digilocker);
  const vsPan = matchesMasked(typed, panMask);
  const photos = docsOf(input, AADHAAR_CARD_TYPES);
  const duplicate = stepOf(input.steps, "AADHAAR_DUPLICATE");
  const fraud = aad.aadhaarMismatch === true || pan.aadhaarMismatch === true;
  const photoCheck: BreCheck =
    photos.length > 0
      ? { label: "Aadhaar number on the uploaded card photo", outcome: "MANUAL", detail: "Compare the number on the photo — pictures are not read automatically" }
      : { label: "Aadhaar number on the uploaded card photo", outcome: "UNKNOWN", detail: "No Aadhaar card photo uploaded" };
  const checks: BreCheck[] = [
    {
      label: "Typed Aadhaar matches DigiLocker (last 4)",
      outcome: vsDigilocker === "MATCH" ? "PASS" : vsDigilocker === "MISMATCH" ? "FLAG" : "UNKNOWN",
      detail: vsDigilocker === "UNKNOWN" ? (typed ? "DigiLocker runs after sanction" : "No typed Aadhaar on file") : `Typed ${maskAadhaar(typed ?? "")} vs DigiLocker ${digilocker}`,
    },
    photoCheck,
  ];
  if (vsPan !== "UNKNOWN") checks.push({ label: "Typed Aadhaar matches the PAN record", outcome: vsPan === "MATCH" ? "PASS" : "FLAG", detail: `PAN record shows ${panMask}`, informational: vsPan === "MATCH" });
  if (fraud) checks.push({ label: "No Aadhaar mismatch recorded", outcome: "FLAG", detail: "The verification flow recorded an Aadhaar mismatch (fraud rule)" });
  if (duplicate && duplicate.status !== "PASS") checks.push({ label: "Aadhaar not held by another customer", outcome: "FLAG", detail: duplicate.message ?? "Same Aadhaar number on another customer" });
  return rule({
    id: 8,
    key: "aadhaar-match",
    title: "Aadhaar: typed vs DigiLocker vs photo",
    policy: "The Aadhaar number the borrower typed, the last 4 digits DigiLocker returns and the number on the uploaded photo must all agree; otherwise flag.",
    checks,
    evidence: [
      { label: "Typed", value: typed, mono: true },
      { label: "DigiLocker", value: digilocker, mono: true },
      { label: "PAN record", value: panMask, mono: true },
      { label: "Card photos", value: String(photos.length), mono: true },
    ],
    compare: [
      { label: "Typed by borrower", value: typed, outcome: typed ? "PASS" : "UNKNOWN" },
      { label: "DigiLocker (last 4)", value: digilocker, outcome: vsDigilocker === "MATCH" ? "PASS" : vsDigilocker === "MISMATCH" ? "FLAG" : "UNKNOWN", note: digilocker ? undefined : "After sanction" },
      { label: "Uploaded card photo", value: null, outcome: photoCheck.outcome, note: photos.length > 0 ? "Open and compare" : "Not uploaded", docTypes: AADHAAR_CARD_TYPES },
    ],
    sourceTab: "documents",
  });
}

function r9Salary(input: BreInput): BreRule {
  const declared = input.profile?.monthlySalaryPaise ?? null;
  const bank = input.bankSalaryPaise;
  const slips = docsOf(input, ["SALARY_SLIP"]);
  const within = (a: number, b: number) => Math.abs(a - b) <= SALARY_MATCH_TOLERANCE * Math.max(a, b);
  const bankCheck: BreCheck =
    bank == null
      ? { label: "Bank statement salary matches declared", outcome: "UNKNOWN", detail: input.bankAnalyserLive ? "No salary identified on the statement" : "Bank statement analyser not live yet", informational: !input.bankAnalyserLive }
      : declared == null
        ? { label: "Bank statement salary matches declared", outcome: "UNKNOWN", detail: "No declared salary to compare" }
        : { label: "Bank statement salary matches declared", outcome: within(bank, declared) ? "PASS" : "FLAG", detail: `${paiseToINR(bank)} vs ${paiseToINR(declared)} declared` };
  const checks: BreCheck[] = [
    { label: "Declared salary on file", outcome: declared == null ? "UNKNOWN" : "PASS", detail: declared == null ? undefined : `${paiseToINR(declared)} / month` },
    bankCheck,
    slips.length > 0
      ? { label: "Salary slips match declared", outcome: "MANUAL", detail: `Compare ${slips.length} salary slip(s) — slips are not read automatically` }
      : { label: "Salary slips match declared", outcome: "UNKNOWN", detail: "No salary slips uploaded" },
  ];
  const income = input.report?.incomes[0];
  return rule({
    id: 9,
    key: "salary-match",
    title: "Salary: bank statement vs slips vs declared",
    policy: `The salary from the bank statement analyser, the salary slips and the borrower's declaration must agree (within ${Math.round(SALARY_MATCH_TOLERANCE * 100)}%).`,
    checks,
    evidence: [
      { label: "Declared (monthly)", value: declared == null ? null : paiseToINR(declared) },
      { label: "Bank statement", value: bank == null ? null : paiseToINR(bank) },
      { label: "Salary slips", value: String(slips.length), mono: true },
      { label: "Bureau-reported income", value: income ? `₹${income.value} (${income.where})` : null },
    ],
    compare: [
      { label: "Declared by borrower", value: declared == null ? null : paiseToINR(declared), outcome: declared == null ? "UNKNOWN" : "PASS" },
      { label: "Bank statement analyser", value: bank == null ? null : paiseToINR(bank), outcome: bankCheck.outcome, note: bank == null ? (input.bankAnalyserLive ? "Not identified" : "Analyser not live") : undefined },
      { label: "Salary slips", value: slips.length > 0 ? `${slips.length} on file` : null, outcome: slips.length > 0 ? "MANUAL" : "UNKNOWN", note: slips.length > 0 ? "Open and compare" : "Not uploaded", docTypes: ["SALARY_SLIP"] },
    ],
    sourceTab: "banking",
  });
}

export function evaluateBre(input: BreInput): BreResult {
  const window = lookbackWindow(input.report, input.bureau.generatedAt, input.today);
  const analysis = analyseProfile(input.report, input.profile, input.steps, input.bureau.generatedAt, input.today);
  const rules = [
    r1Score(input),
    r2PersonalLoan(input, window),
    r3Ownership(input, window),
    r4Nbfc(input, window),
    r5Occupation(input, analysis),
    r6AadhaarPan(input),
    r7UanEmployer(input),
    r8AadhaarTriangle(input),
    r9Salary(input),
  ].map((r) => (input.stagingPass && STAGING_PASS_RULES.includes(r.id) ? stagingPassed(r) : r));
  const counts: Record<BreOutcome, number> = { PASS: 0, FLAG: 0, MANUAL: 0, UNKNOWN: 0 };
  for (const r of rules) counts[r.outcome]++;
  return { rules, analysis, window, counts };
}

/** Newest row per check type: the selected file first, then earlier files for what it lacks. */
export function mergeSteps(perApplication: readonly (readonly StepResult[] | undefined)[]): StepResult[] {
  const out: StepResult[] = [];
  const seen = new Set<string>();
  for (const steps of perApplication) {
    for (const s of steps ?? []) {
      if (seen.has(s.checkType)) continue;
      seen.add(s.checkType);
      out.push(s);
    }
  }
  return out;
}
