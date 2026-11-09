"use client";

/**
 * AI Analyzer: a staff-only, rule-based summary of how comparable past DhanBoost loans turned out.
 * Advisory only: nothing here approves, rejects or blocks a loan. Every figure comes from
 * GET /api/customers/{id}/analyzer at request time; no benchmark number lives in this file.
 */

import * as React from "react";
import { useQuery } from "@tanstack/react-query";
import { Sparkles } from "lucide-react";
import { Badge, EmptyState, ErrorState, Skeleton } from "@/components/ui";
import { Section } from "@/components/staff/detail-parts";
import {
  customersApi, paiseToINR,
  type AnalyzerFacts, type AnalyzerGroup, type AnalyzerPointer, type AnalyzerView,
} from "@/lib/api/applications";
import { cn, formatDate } from "@/lib/utils";

const DISCLAIMER =
  "Rule-based summary of past DhanBoost loans, not AI. For guidance only; it never approves, rejects or blocks a loan.";

const BAND_STYLE: Record<string, { name: string; gradient: string }> = {
  LOWER: { name: "Lower risk", gradient: "from-emerald-500 to-emerald-700" },
  WATCH: { name: "Watch", gradient: "from-orange-500 to-orange-700" },
  HIGH: { name: "High risk", gradient: "from-red-500 to-red-700" },
  INCOMPLETE: { name: "Not enough information yet", gradient: "from-slate-500 to-slate-700" },
  NONE: { name: "No result", gradient: "from-navy-700 to-navy-900" },
};

const CONCERN_LABEL: Record<string, { label: string; how: string }> = {
  THIN_RECORD: { label: "Thin credit record", how: "The credit report shows fewer than 30 accounts." },
  LONG_WAIT: { label: "Long wait to salary day", how: "The repayment date is 28 or more days after the loan." },
  LARGE_TICKET: { label: "Large first amount", how: "The loan is above ₹7,500." },
  JOB_NOT_CONFIRMED: {
    label: "Job confirmation",
    how: "The EPFO check says they are not employed at the employer they declared. No EPFO result counts as no concern.",
  },
};

const INFO_LABEL: Record<string, string> = {
  SALARY_50K: "Salary ₹50,000 or more",
  STARS_3: "Star rating 3 or more",
  RECOMMENDED: "Credit report marked Recommended",
  RISK_B: "Risk category B",
  SCORE_700: "Credit score 700 or more",
};

const PATTERN_LABEL: Record<string, string> = {
  ALWAYS_PAID: "Paid every loan on time",
  PAID_THEN_OVERDUE: "Paid first, later went overdue",
  FIRST_OVERDUE_SOME_PAID: "First loan overdue, part-paid or paid late",
  NEVER_PAID: "Never paid from the first loan",
  TOO_EARLY: "Too early to tell",
};

const GUIDANCE: Record<string, string> = {
  LOWER: "Normal review.",
  WATCH: "Confirm the job and salary independently before lending; keep the amount modest.",
  HIGH: "Look hard before lending: ask for a bank statement and consider a smaller first amount or shorter wait to salary day.",
  INCOMPLETE: "Too little is on file to judge. Complete the credit check and loan details, then look again.",
};

/** "59% (16 of 27 loans)", with "few loans" under 10. */
function rate(bad: number, n: number): string {
  if (n === 0) return "no loans";
  return `${Math.round((bad * 100) / n)}% (${bad} of ${n} loans)${n < 10 ? ", few loans" : ""}`;
}

function share(part: number | null, whole: number | null): string {
  if (part == null || whole == null) return "—";
  return whole === 0 ? "none" : `${Math.round((part * 100) / whole)}%`;
}

const inr = (n: number | null): string => (n == null ? "—" : paiseToINR(n));

function pointerValue(p: AnalyzerPointer, facts: AnalyzerFacts): string {
  if (p.key === "JOB_NOT_CONFIRMED") {
    return facts.employedAtDeclared == null ? "Not known" : facts.employedAtDeclared ? "Confirmed" : "Not confirmed";
  }
  if (p.value == null) return "Not known";
  if (p.key === "THIN_RECORD") return `${p.value} accounts`;
  if (p.key === "LONG_WAIT") return `${p.value} days`;
  if (p.key === "LARGE_TICKET") return paiseToINR(p.value);
  return String(p.value);
}

function infoValue(p: AnalyzerPointer, f: AnalyzerFacts): string {
  const v: Record<string, string | number | null | undefined> = {
    SALARY_50K: f.salaryPaise == null ? null : paiseToINR(f.salaryPaise),
    STARS_3: f.stars,
    RECOMMENDED: f.recommendation,
    RISK_B: f.riskCategory,
    SCORE_700: f.score,
  };
  return v[p.key] == null ? "Not known" : String(v[p.key]);
}

function stateBadge(state: string | null) {
  if (state === "CONCERN") return <Badge variant="error" size="sm">Concern</Badge>;
  if (state === "OK") return <Badge variant="success" size="sm">OK</Badge>;
  if (state === "YES") return <Badge variant="neutral" size="sm">Yes</Badge>;
  if (state === "NO") return <Badge variant="neutral" size="sm">No</Badge>;
  return <Badge variant="warning" size="sm">Not known</Badge>;
}

export function AnalyzerTab({ customerId }: { customerId: number }) {
  const q = useQuery({
    queryKey: ["customer-analyzer", customerId],
    queryFn: () => customersApi.analyzer(customerId),
  });
  if (q.isLoading) return <Skeleton variant="line" rows={6} />;
  if (q.error) return <ErrorState error={q.error} onRetry={() => void q.refetch()} />;
  const v = q.data;
  if (!v) return null;
  return <AnalyzerBody v={v} />;
}

export function AnalyzerBody({ v }: { v: AnalyzerView }) {
  const b = v.benchmarks;
  const noResult = v.mode === "NO_DATA" || v.band == null;
  return (
    <div className="space-y-3">
      <ResultCard v={v} />
      {v.incomeUncorroborated && (
        <div className="rounded border border-warning-500 bg-warning-50 p-3 text-sm text-warning-800">
          <div className="font-semibold">Declared income not corroborated</div>
          <p className="mt-1">
            Salary of ₹50,000 or more, no EPFO confirmation of the declared employer, and a thin credit record. Among all
            first loans with this pattern, {rate(b.incomeUncorroborated.bad, b.incomeUncorroborated.loans)} went bad
            and {b.incomeUncorroborated.zeroPaid} paid nothing.
          </p>
        </div>
      )}
      {noResult ? (
        <EmptyState title="Nothing to compare" hint="This customer has no loan and no application, so there is nothing to compare." />
      ) : (
        <>
          <SummarySection v={v} />
          <PointersSection v={v} />
          {v.ownRecord && <OwnRecordSection v={v} />}
          <InfoSection v={v} />
          <Section title="What to do" icon={Sparkles}>
            <p className="text-sm text-ink">
              {v.mode === "OWN_RECORD" && v.band === "HIGH"
                ? "They have gone seriously overdue with us before. Clear that history before considering another loan."
                : GUIDANCE[v.band as string]}
            </p>
          </Section>
        </>
      )}
      <PortfolioSection v={v} />
    </div>
  );
}

function ResultCard({ v }: { v: AnalyzerView }) {
  const b = v.benchmarks;
  const style = BAND_STYLE[v.band ?? "NONE"];
  const sub =
    v.mode === "OWN_RECORD" ? "Based on their own repayment record"
    : v.mode === "FIRST_LOAN" ? `${v.concernCount} of 4 concerns`
    : "No loan or application on file";
  const similar =
    v.mode === "FIRST_LOAN" ? b.bands.find((x) => x.band === v.band)
    : v.mode === "OWN_RECORD" && v.ownRecord?.bad === 0 ? { loans: b.repeat.loans, bad: b.repeat.bad }
    : null;
  return (
    <div className={cn("rounded-lg bg-gradient-to-br p-4 text-white shadow", style.gradient)}>
      <div className="flex flex-wrap items-baseline gap-x-3 gap-y-1">
        <span className="font-serif text-2xl font-semibold">{style.name}</span>
        <span className="text-sm opacity-90">{sub}</span>
      </div>
      {similar && (
        <p className="mt-2 text-sm">
          {v.mode === "FIRST_LOAN" ? "Similar past borrowers" : "Repeat borrowers overall"}:{" "}
          {similar.loans === 0
            ? "no loans yet"
            : `${Math.round((similar.bad * 100) / similar.loans)}% went bad (${similar.bad} of ${similar.loans} loans)${similar.loans < 10 ? ", few loans" : ""}`}
        </p>
      )}
      {v.unknownCount > 0 && v.mode === "FIRST_LOAN" && (
        <p className="mt-1 text-sm">Provisional: {v.unknownCount} still not known</p>
      )}
      <p className="mt-3 text-[11px] opacity-90">{DISCLAIMER}</p>
    </div>
  );
}

function SummarySection({ v }: { v: AnalyzerView }) {
  const lines: string[] = [];
  const o = v.ownRecord;
  if (v.mode === "FIRST_LOAN") {
    lines.push(`This is a first loan. ${v.concernCount} of 4 signs are concerns.`);
  } else if (o) {
    lines.push(`${o.loansTaken} loan${o.loansTaken === 1 ? "" : "s"} taken; ${o.good} repaid within 5 days of the due date, ${o.bad} gone bad.`);
  }
  v.pointers.filter((p) => p.state === "CONCERN").forEach((p) =>
    lines.push(`${CONCERN_LABEL[p.key]?.label ?? p.key}: ${pointerValue(p, v.facts)}.`));
  const unknown = v.pointers.filter((p) => p.state === "UNKNOWN").map((p) => CONCERN_LABEL[p.key]?.label ?? p.key);
  if (unknown.length > 0) lines.push(`Not known yet: ${unknown.join(", ")}.`);
  if (o) {
    if (o.lastClosedDaysEarly != null) {
      lines.push(
        o.lastClosedDaysEarly > 0 ? `Paid ${o.lastClosedDaysEarly} days early last time.`
        : o.lastClosedDaysEarly === 0 ? "Paid on the due date last time."
        : `Paid ${-o.lastClosedDaysEarly} days late last time.`,
      );
    }
    lines.push(`${o.onTime} of ${o.resolved} resolved loans paid on time.`);
    if (o.openLoans > 0 && o.maxOpenDpd != null && o.maxOpenDpd > 0) {
      lines.push(`An open loan is ${o.maxOpenDpd} day${o.maxOpenDpd === 1 ? "" : "s"} overdue now.`);
    }
  }
  return (
    <Section title="Summary" icon={Sparkles}>
      <ul className="list-disc space-y-1 pl-5 text-sm text-ink">
        {lines.map((l) => <li key={l}>{l}</li>)}
      </ul>
    </Section>
  );
}

function PointersSection({ v }: { v: AnalyzerView }) {
  const [open, setOpen] = React.useState<string | null>(null);
  const bench = v.benchmarks;
  return (
    <Section title={v.mode === "OWN_RECORD" ? "Signs measured on first loans" : "Signs"} icon={Sparkles}>
      <ul className="divide-y divide-line">
        {v.pointers.map((p) => {
          const meta = CONCERN_LABEL[p.key];
          const exact = `With this sign: ${p.withBad} of ${p.withLoans} went bad. Without: ${p.withoutBad} of ${p.withoutLoans}.`;
          return (
            <li key={p.key} className="py-2">
              <button
                type="button"
                title={exact}
                aria-expanded={open === p.key}
                onClick={() => setOpen(open === p.key ? null : p.key)}
                className="grid w-full grid-cols-2 items-center gap-x-3 gap-y-1 text-left md:grid-cols-[1.4fr_1fr_6rem_2.6fr]"
              >
                <span className="text-sm font-medium text-ink">{meta?.label ?? p.key}</span>
                <span className="text-sm text-muted">{pointerValue(p, v.facts)}</span>
                <span>{stateBadge(p.state)}</span>
                <span className="col-span-2 text-xs text-muted md:col-span-1">
                  With: {rate(p.withBad, p.withLoans)} · Without: {rate(p.withoutBad, p.withoutLoans)}
                </span>
              </button>
              {open === p.key && (
                <p className="mt-2 rounded bg-neutral-50 p-2 text-xs text-muted">
                  {meta?.how} {p.withBad} of {p.withLoans} loans with this sign went bad, against {p.withoutBad} of{" "}
                  {p.withoutLoans} without. Measured on first loans disbursed since {formatDate(bench.recentStart)} and
                  due at least 6 days ago.
                </p>
              )}
            </li>
          );
        })}
      </ul>
    </Section>
  );
}

function OwnRecordSection({ v }: { v: AnalyzerView }) {
  const o = v.ownRecord;
  if (!o) return null;
  const r = v.benchmarks.repeatEarlyPayer;
  const rows: [string, string][] = [
    ["Loans taken", String(o.loansTaken)],
    ["Paid on time", String(o.onTime)],
    ["Paid within 5 days late", String(o.late1to5)],
    ["Went bad", `${o.bad}${o.zeroPaidBad > 0 ? ` (${o.zeroPaidBad} paid nothing)` : ""}`],
    ["Open loans", String(o.openLoans)],
    ["Worst days overdue now", o.maxOpenDpd == null ? "No open loan" : String(Math.max(0, o.maxOpenDpd))],
    ["Repaid vs lent", `${paiseToINR(o.totalCollectedPaise)} of ${paiseToINR(o.totalPrincipalPaise)}`],
  ];
  return (
    <Section title="Own record" icon={Sparkles}>
      <div className="grid grid-cols-2 gap-2 md:grid-cols-4">
        {rows.map(([k, val]) => (
          <div key={k} className="rounded border border-line bg-neutral-50 px-3 py-2">
            <div className="text-[10px] font-semibold uppercase tracking-wide text-muted">{k}</div>
            <div className="font-mono text-sm font-semibold text-ink">{val}</div>
          </div>
        ))}
      </div>
      <p className="mt-3 text-xs text-muted">
        Repeat borrowers overall: {rate(v.benchmarks.repeat.bad, v.benchmarks.repeat.loans)} went bad. Next loan after
        an early payer: {rate(r.earlyBad, r.earlyLoans)}; after anyone else: {rate(r.otherBad, r.otherLoans)}.
      </p>
    </Section>
  );
}

function InfoSection({ v }: { v: AnalyzerView }) {
  if (v.informational.length === 0) return null;
  return (
    <Section title="Checked but not relied on" icon={Sparkles}>
      <div className="staff-table-scroll">
        <table className="staff-data-table staff-table-fit" aria-label="Signs checked but not relied on">
          <thead>
            <tr><th>Sign</th><th>This customer</th><th>With</th><th>Without</th></tr>
          </thead>
          <tbody>
            {v.informational.map((p) => (
              <tr key={p.key} title={`With: ${p.withBad} of ${p.withLoans} went bad. Without: ${p.withoutBad} of ${p.withoutLoans}.`}>
                <td>{INFO_LABEL[p.key] ?? p.key}</td>
                <td>{infoValue(p, v.facts)} {stateBadge(p.state)}</td>
                <td>{rate(p.withBad, p.withLoans)}</td>
                <td>{rate(p.withoutBad, p.withoutLoans)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <p className="mt-2 text-xs text-muted">
        These did not separate good from bad borrowers in our results, so they add nothing to the band.
      </p>
    </Section>
  );
}

function PortfolioSection({ v }: { v: AnalyzerView }) {
  const b = v.benchmarks;
  const [open, setOpen] = React.useState<string | null>(null);
  const a = b.all;
  // The server sends null rupee totals to roles without the book-wide money view; counts stay.
  const showMoney = a.principalPaise != null;
  const kpis: { key: string; label: string; value: string; gradient: string; exact: string; how: string }[] = [
    {
      key: "loans", label: "Loans with a known result", value: String(a.loans), gradient: "from-navy-700 to-navy-900",
      exact: `${a.loans} loans from ${a.customers} customers`,
      how: "Loans due at least 6 days ago that were either repaid (within 5 days of the due date) or are more than 5 days overdue.",
    },
    {
      key: "bad", label: "Went bad", value: share(a.bad, a.loans), gradient: "from-red-500 to-red-700",
      exact: `${a.bad} of ${a.loans} loans`,
      how: "Bad means closed more than 5 days late, settled for less than owed, or still open more than 5 days past due.",
    },
    {
      key: "money", label: "Money that went bad", value: share(a.badPrincipalPaise, a.principalPaise), gradient: "from-orange-500 to-orange-700",
      exact: `${inr(a.badPrincipalPaise)} of ${inr(a.principalPaise)} lent`,
      how: "Principal of bad loans divided by principal of all loans with a known result.",
    },
    {
      key: "collected", label: "Collected vs paid out", value: share(a.collectedPaise, a.paidOutPaise), gradient: "from-sky-500 to-sky-700",
      exact: `${inr(a.collectedPaise)} collected, ${inr(a.paidOutPaise)} paid out`,
      how: "Verified payments received, against what we actually paid out (net disbursed plus GST on the fee).",
    },
    {
      key: "zero", label: "Bad loans that paid nothing", value: String(a.zeroPaidBad), gradient: "from-purple-500 to-purple-700",
      exact: `${a.zeroPaidBad} of ${a.bad} bad loans`,
      how: "Bad loans with no verified payment at all.",
    },
  ];
  const groups: AnalyzerGroup[] = [b.earlyFirst, b.recentFirst, b.repeat];
  const groupName: Record<string, string> = {
    EARLY_FIRST: "First loans before September",
    RECENT_FIRST: "First loans from September",
    REPEAT: "Repeat loans",
  };
  return (
    <Section title="Portfolio gist" icon={Sparkles}>
      <div className="grid grid-cols-2 gap-2 md:grid-cols-5">
        {kpis.filter((k) => showMoney || (k.key !== "money" && k.key !== "collected")).map((k) => (
          <button
            key={k.key}
            type="button"
            title={k.exact}
            aria-expanded={open === k.key}
            onClick={() => setOpen(open === k.key ? null : k.key)}
            className={cn("rounded-lg bg-gradient-to-br p-3 text-left text-white shadow-sm", k.gradient)}
          >
            <div className="text-[10px] font-semibold uppercase tracking-wide opacity-90">{k.label}</div>
            <div className="font-mono text-xl font-semibold">{k.value}</div>
          </button>
        ))}
      </div>
      {open && (
        <p className="mt-2 rounded bg-neutral-50 p-2 text-xs text-muted">
          {kpis.find((k) => k.key === open)?.exact}. {kpis.find((k) => k.key === open)?.how}
        </p>
      )}

      <div className="mt-3 grid grid-cols-2 gap-2 md:grid-cols-5">
        {b.patterns.map((p) => (
          <div key={p.pattern} className="rounded border border-line bg-neutral-50 px-3 py-2">
            <div className="text-[10px] font-semibold uppercase tracking-wide text-muted">{PATTERN_LABEL[p.pattern] ?? p.pattern}</div>
            <div className="font-mono text-sm font-semibold text-ink">{p.customers} customers</div>
          </div>
        ))}
      </div>

      <div className="staff-table-scroll mt-3">
        <table className="staff-data-table staff-table-fit" aria-label="Loan groups">
          <thead>
            <tr><th>Group</th><th>Loans</th><th>Bad</th>{showMoney && <><th>Share of money</th><th>Paid out</th><th>Collected</th><th>Net</th></>}</tr>
          </thead>
          <tbody>
            {groups.map((g) => (
              <tr key={g.key}>
                <td>{groupName[g.key] ?? g.key}</td>
                <td>{g.loans}{g.loans < 10 ? " (few loans)" : ""}</td>
                <td>{rate(g.bad, g.loans)}</td>
                {showMoney && (
                  <>
                    <td>{share(g.badPrincipalPaise, g.principalPaise)}</td>
                    <td>{inr(g.paidOutPaise)}</td>
                    <td>{inr(g.collectedPaise)}</td>
                    <td>{g.collectedPaise == null || g.paidOutPaise == null ? "—" : paiseToINR(g.collectedPaise - g.paidOutPaise)}</td>
                  </>
                )}
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <div className="staff-table-scroll mt-3">
        <table className="staff-data-table staff-table-fit" aria-label="Concerns per first loan">
          <thead>
            <tr><th>Concerns</th><th>Loans</th><th>Bad</th><th>Paid nothing</th></tr>
          </thead>
          <tbody>
            {b.pointTable.map((r) => (
              <tr key={r.points}>
                <td>{r.points}</td><td>{r.loans}</td><td>{rate(r.bad, r.loans)}</td><td>{r.zeroPaid}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {showMoney && (
        <p className="mt-3 text-xs text-muted">
          {b.breakEvenBps == null
            ? "Break-even: not enough good and bad loans to work it out."
            : `Break-even: the book makes money while no more than ${(b.breakEvenBps / 100).toFixed(0)}% of lent money goes bad.`}
        </p>
      )}
      <p className="mt-1 text-xs text-muted">
        As of {formatDate(v.asOf)}. Live from loans due at least 6 days ago. Loans we rejected are not included. A short
        history, so treat small groups with care.
      </p>
    </Section>
  );
}
