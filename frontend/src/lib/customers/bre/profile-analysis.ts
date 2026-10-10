/**
 * Who the borrower works for and as what — read from the bureau report (until the bank statement
 * analyser is live, the CRIF report is the only third-party view of it) plus the KYC data we hold.
 * Feeds BRE rule 5 and the "CRIF report analysis" panel shown in the Banking and Third-party tabs.
 *
 * Flags only: a hit means "a human should look", never a decline.
 */

import type { ProfileView, StepResult } from "@/lib/api/applications";
import {
  inWindow,
  lookbackWindow,
  worstDpdInWindow,
  type BureauReport,
  type Window,
} from "@/lib/customers/bre/bureau-report";

export type ProfessionCategory = "SELF_EMPLOYED" | "ARMY" | "LAWYER" | "MHA";

/**
 * The professions DhanBoost does not lend to. Matched as whole words against employer names,
 * occupations, email domains and bureau addresses. The Central Armed Police Forces, Assam Rifles and
 * the Intelligence Bureau are listed under the Ministry of Home Affairs because that is who employs
 * them. Edit here to change the policy — the BRE tab and the CRIF panel both read this list.
 */
export const RESTRICTED_PROFESSIONS: { category: Exclude<ProfessionCategory, "SELF_EMPLOYED">; label: string; patterns: RegExp[] }[] = [
  {
    category: "ARMY",
    label: "Army / armed forces",
    patterns: [/\barmy\b/i, /\bmilitary\b/i, /\barmed forces?\b/i],
  },
  {
    category: "LAWYER",
    label: "Lawyer / advocate",
    patterns: [
      /\blawyers?\b/i, /\badvocates?\b/i, /\battorneys?\b/i, /\bsolicitors?\b/i,
      /\blaw (firm|office|offices|chambers?|associates|partners)\b/i, /\blegal (practitioner|consultant)s?\b/i,
      /\bbar council\b/i,
    ],
  },
  {
    category: "MHA",
    label: "Ministry of Home Affairs",
    patterns: [
      /\bministry of home affairs\b/i, /\bMHA\b/, /\bmha\.(gov|nic)\.in\b/i,
      /\b(CRPF|BSF|CISF|ITBP|SSB|NSG)\b/, /\bcentral reserve police\b/i, /\bborder security force\b/i,
      /\bcentral industrial security\b/i, /\bindo.?tibetan border police\b/i, /\bsashastra seema bal\b/i,
      /\bassam rifles\b/i, /\bintelligence bureau\b/i,
    ],
  },
];

/** Occupation text that means the borrower is not salaried. Only applied to occupation fields. */
const SELF_EMPLOYED = /self.?employ|non.?salaried|\bbusiness\b|\bprofessional\b|\bproprietor/i;
const SALARIED = /\bsalaried\b|^salaried|code s\)/i;

export interface ProfessionHit {
  category: ProfessionCategory;
  label: string;
  source: string;
  value: string;
}

export interface ScannedSource {
  source: string;
  value: string;
  /** Occupation-type fields are also checked for self-employment; names/addresses only for professions. */
  occupation: boolean;
}

export interface AccountMix {
  total: number;
  active: number;
  closed: number;
  personalLoans: number;
  personalLoansInWindow: number;
  nbfcLoans: number;
  nbfcLoansInWindow: number;
  creditCards: number;
  nonIndividual: number;
  /** Worst DPD reported in the window across every account; null = nothing reported in the window. */
  worstDpdInWindow: number | null;
}

export interface ProfileAnalysis {
  /** "SALARIED" / "SELF_EMPLOYED" from the bureau occupations, null when it reports none. */
  bureauEmployment: "SALARIED" | "SELF_EMPLOYED" | null;
  sources: ScannedSource[];
  hits: ProfessionHit[];
  mix: AccountMix | null;
  window: Window;
}

const str = (v: unknown): string | null => (v == null || String(v).trim() === "" ? null : String(v).trim());
const stepOf = (steps: readonly StepResult[], type: string) => steps.find((s) => s.checkType === type);

/** Every place an employer, occupation or profession can show up, labelled by where it came from. */
export function professionSources(report: BureauReport | null, profile: ProfileView | null, steps: readonly StepResult[]): ScannedSource[] {
  const out: ScannedSource[] = [];
  const add = (source: string, value: unknown, occupation = false) => {
    const v = str(value);
    if (v) out.push({ source, value: v, occupation });
  };
  add("Declared employment type", profile?.employmentStatus?.replace(/_/g, " "), true);
  add("Declared employer", profile?.employer);
  const epfo = stepOf(steps, "EMPLOYMENT")?.derived ?? {};
  add("EPFO employer", epfo.employerName);
  const email = stepOf(steps, "EMAIL")?.derived ?? {};
  add("Official email company", email.companyName);
  add("Official email establishment", email.matchedEstablishment);
  const domain = str(email.domain) ?? str(profile?.officialEmail?.split("@")[1]);
  add("Official email domain", domain);
  const bureau = report ? (report.kind === "CRIF" ? "CRIF" : "Experian") : "Bureau";
  for (const o of report?.occupations ?? []) add(`${bureau} occupation · ${o.where}`, o.value, true);
  for (const a of report?.addresses ?? []) add(`${bureau} address`, a.value);
  return out;
}

export function scanProfessions(sources: readonly ScannedSource[]): ProfessionHit[] {
  const hits: ProfessionHit[] = [];
  for (const s of sources) {
    if (s.occupation && SELF_EMPLOYED.test(s.value) && !SALARIED.test(s.value)) {
      hits.push({ category: "SELF_EMPLOYED", label: "Self-employed", source: s.source, value: s.value });
    }
    for (const p of RESTRICTED_PROFESSIONS) {
      if (p.patterns.some((re) => re.test(s.value))) hits.push({ category: p.category, label: p.label, source: s.source, value: s.value });
    }
  }
  return hits;
}

export function accountMix(report: BureauReport | null, w: Window): AccountMix | null {
  if (!report) return null;
  const a = report.accounts;
  const worst = a.map((x) => worstDpdInWindow(x, w)).filter((d): d is number => d != null);
  return {
    total: a.length,
    active: a.filter((x) => x.status === "ACTIVE").length,
    closed: a.filter((x) => x.status === "CLOSED").length,
    personalLoans: a.filter((x) => x.isPersonalLoan).length,
    personalLoansInWindow: a.filter((x) => x.isPersonalLoan && inWindow(x, w)).length,
    nbfcLoans: a.filter((x) => x.isNbfc === true).length,
    nbfcLoansInWindow: a.filter((x) => x.isNbfc === true && inWindow(x, w)).length,
    creditCards: a.filter((x) => /credit card/i.test(x.accountType)).length,
    nonIndividual: a.filter((x) => x.ownership != null && x.ownership !== "INDIVIDUAL").length,
    worstDpdInWindow: worst.length === 0 ? null : Math.max(...worst),
  };
}

export function analyseProfile(
  report: BureauReport | null,
  profile: ProfileView | null,
  steps: readonly StepResult[],
  generatedAt: string | null | undefined,
  today: Date,
): ProfileAnalysis {
  const window = lookbackWindow(report, generatedAt, today);
  const sources = professionSources(report, profile, steps);
  const occ = (report?.occupations ?? []).map((o) => o.value);
  const bureauEmployment = occ.some((o) => SELF_EMPLOYED.test(o) && !SALARIED.test(o))
    ? "SELF_EMPLOYED"
    : occ.some((o) => SALARIED.test(o))
      ? "SALARIED"
      : null;
  return { bureauEmployment, sources, hits: scanProfessions(sources), mix: accountMix(report, window), window };
}
