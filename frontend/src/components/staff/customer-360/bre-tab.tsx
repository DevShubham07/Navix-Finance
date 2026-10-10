"use client";

/**
 * BRE — Business Rule Engine. Nine credit-policy rules evaluated from data already on the file
 * (lib/customers/bre/rules.ts). Flags only: nothing on this tab rejects, blocks or changes a loan.
 */

import * as React from "react";
import {
  BadgeCheck, Briefcase, CheckCircle2, CircleDashed, Eye, Fingerprint, Gauge, IdCard, IndianRupee, Landmark,
  ListChecks, Loader2, UserCheck, Users, XCircle, type LucideIcon,
} from "lucide-react";
import { Badge, EmptyState, ErrorState, Skeleton, toast } from "@/components/ui";
import { Field, FieldGrid } from "@/components/ui/field-grid";
import { Figure, GoalProgress } from "@/components/kit";
import { Section, docTypeLabel, viewDocument } from "@/components/staff/detail-parts";
import { formatDpdDays } from "@/components/staff/credit/tradeline-table";
import { CrifAnalysisPanel } from "@/components/staff/customer-360/crif-analysis-panel";
import type { TabCtx } from "@/components/staff/customer-360/types";
import type { DocumentView } from "@/lib/api/applications";
import type { BreCheck, BreCompare, BreOutcome, BreRule } from "@/lib/customers/bre/rules";
import { useBreData } from "@/lib/customers/bre/use-bre-data";
import { cn, formatDate } from "@/lib/utils";

const OUTCOME: Record<BreOutcome, {
  label: string;
  badge: "success" | "error" | "warning" | "neutral";
  tone: "success" | "error" | "warning" | "neutral";
  icon: LucideIcon;
  text: string;
}> = {
  PASS: { label: "Validated", badge: "success", tone: "success", icon: CheckCircle2, text: "text-success-700" },
  FLAG: { label: "Flagged", badge: "error", tone: "error", icon: XCircle, text: "text-error-700" },
  MANUAL: { label: "Manual check", badge: "warning", tone: "warning", icon: Eye, text: "text-warning-800" },
  UNKNOWN: { label: "No data", badge: "neutral", tone: "neutral", icon: CircleDashed, text: "text-muted" },
};

const RULE_ICON: Record<string, LucideIcon> = {
  "credit-score": Gauge,
  "personal-loan": Landmark,
  ownership: Users,
  nbfc: Landmark,
  occupation: Briefcase,
  "aadhaar-pan": IdCard,
  "uan-employer": UserCheck,
  "aadhaar-match": Fingerprint,
  "salary-match": IndianRupee,
};

const ORDER: BreOutcome[] = ["PASS", "FLAG", "MANUAL", "UNKNOWN"];
const ruleAnchor = (id: number) => `bre-rule-${id}`;

function OutcomeBadge({ outcome, className }: { outcome: BreOutcome; className?: string }) {
  const o = OUTCOME[outcome];
  return <Badge variant={o.badge} size="sm" className={className}>{o.label}</Badge>;
}

type Docs = { applicationId: number; doc: DocumentView }[];

export function BreTab({ detail, customerId, applicationId, onTabChange }: TabCtx) {
  const data = useBreData(detail, customerId, applicationId);
  const [busy, setBusy] = React.useState<string | null>(null);
  const openDoc = async (appId: number, doc: DocumentView) => {
    const key = `${appId}-${doc.id}`;
    if (busy) return;
    setBusy(key);
    try {
      await viewDocument(appId, doc);
    } catch {
      toast.error("Could not open the document");
    } finally {
      setBusy(null);
    }
  };

  if (applicationId == null) {
    return <EmptyState title="No application yet" hint="Business rules run once the customer has an application." />;
  }
  if (data.isLoading) return <Skeleton variant="line" rows={6} />;
  if (data.error) return <ErrorState error={data.error} onRetry={data.refetch} />;
  const result = data.result;
  if (!result) return null;

  return (
    <div className="space-y-3">
      <Summary rules={result.rules} counts={result.counts} reportLine={reportLine(data)} />
      {result.rules.map((rule) => (
        <RuleSection
          key={rule.key}
          rule={rule}
          documents={data.documents}
          busy={busy}
          onOpen={openDoc}
          onTabChange={onTabChange}
          extra={rule.key === "occupation" ? (
            <div className="mt-3 rounded border border-line bg-neutral-50 p-3">
              <div className="mb-2 text-[9.6px] font-bold uppercase tracking-wide text-muted">CRIF report analysis</div>
              <CrifAnalysisPanel report={data.report} analysis={result.analysis} />
            </div>
          ) : null}
        />
      ))}
    </div>
  );
}

function reportLine({ report, brief }: ReturnType<typeof useBreData>): string {
  if (!report) {
    return brief?.bureauState === "NO_RECORD" ? "Bureau: no record found for this borrower." : "Bureau: no report on file yet.";
  }
  const name = report.kind === "CRIF" ? "CRIF" : "Experian";
  const when = report.reportDate ? ` dated ${formatDate(report.reportDate)}` : brief?.generatedAt ? ` pulled ${formatDate(brief.generatedAt)}` : "";
  const from = ` (application #${report.applicationId})`;
  return report.source === "RAW"
    ? `Bureau: ${name} report${when}${from}.`
    : `Bureau: ${name} tradelines${when}${from} — the full report is not stored for this file, so ownership cannot be read.`;
}

function Summary({ rules, counts, reportLine }: { rules: BreRule[]; counts: Record<BreOutcome, number>; reportLine: string }) {
  const jump = (id: number) => document.getElementById(ruleAnchor(id))?.scrollIntoView({ behavior: "smooth", block: "start" });
  return (
    <Section
      title="Business rule engine"
      icon={ListChecks}
      tone={counts.FLAG > 0 ? "error" : counts.PASS === rules.length ? "success" : "neutral"}
      pill={<Badge variant={counts.FLAG > 0 ? "error" : "neutral"} size="sm" className="ml-1 normal-case tracking-normal">{counts.PASS}/{rules.length} validated</Badge>}
    >
      <div className="grid grid-cols-2 gap-2 md:grid-cols-4">
        {ORDER.map((o) => {
          const Icon = OUTCOME[o].icon;
          return (
            <div key={o} className="rounded border border-line bg-neutral-50 px-3 py-2">
              <div className={cn("flex items-center gap-1 text-[8.8px] font-semibold uppercase tracking-wide", OUTCOME[o].text)}>
                <Icon size={12} /> {OUTCOME[o].label}
              </div>
              <Figure size="sm" className={OUTCOME[o].text}>{counts[o]}</Figure>
            </div>
          );
        })}
      </div>
      <GoalProgress
        tone="light"
        className="mt-3"
        ratio={rules.length === 0 ? 0 : counts.PASS / rules.length}
        label="Rules validated"
        trailing={`${counts.PASS} of ${rules.length}${counts.FLAG > 0 ? ` · ${counts.FLAG} flagged` : ""}`}
      />
      <div className="staff-table-scroll mt-3">
        <table className="staff-data-table staff-table-fit" aria-label="Business rules overview">
          <thead>
            <tr>
              <th>#</th>
              <th>Rule</th>
              <th>Result</th>
              <th>Finding</th>
            </tr>
          </thead>
          <tbody>
            {rules.map((r) => (
              <tr key={r.key} className={r.outcome === "FLAG" ? "bg-error-50" : undefined}>
                <td className="font-mono">{r.id}</td>
                <td className="whitespace-normal">
                  <button type="button" className="text-left font-semibold text-navy hover:underline" onClick={() => jump(r.id)}>{r.title}</button>
                </td>
                <td><OutcomeBadge outcome={r.outcome} /></td>
                <td className="whitespace-normal text-muted">{r.summary}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <p className="mt-2 text-[10.4px] text-muted">
        Flags only — these rules never reject or block a loan. {reportLine} The bank statement analyser is not live yet,
        so rule 5 reads the CRIF report and rule 9 compares what is on file.
      </p>
    </Section>
  );
}

function RuleSection({
  rule, documents, busy, onOpen, onTabChange, extra,
}: {
  rule: BreRule;
  documents: Docs;
  busy: string | null;
  onOpen: (appId: number, doc: DocumentView) => void;
  onTabChange?: (key: string) => void;
  extra?: React.ReactNode;
}) {
  const o = OUTCOME[rule.outcome];
  const Icon = o.icon;
  return (
    <div id={ruleAnchor(rule.id)} className="scroll-mt-2">
      <Section
        title={`${rule.id}. ${rule.title}`}
        icon={RULE_ICON[rule.key] ?? BadgeCheck}
        tone={o.tone}
        pill={<OutcomeBadge outcome={rule.outcome} className="ml-1 normal-case tracking-normal" />}
        action={rule.sourceTab && onTabChange ? (
          <button type="button" className="btn btn-sm btn-outline" onClick={() => onTabChange(rule.sourceTab as string)}>View source</button>
        ) : undefined}
      >
        <p className="text-[10.4px] text-muted">{rule.policy}</p>
        <p className={cn("mt-1 flex items-start gap-1.5 text-[10.4px] font-semibold", o.text)}>
          <Icon size={13} className="mt-px shrink-0" /> {rule.summary}
        </p>
        <ul className="mt-2 space-y-1.5">
          {rule.checks.map((c, i) => <CheckRow key={i} check={c} />)}
        </ul>
        {rule.compare && <Compare items={rule.compare} documents={documents} busy={busy} onOpen={onOpen} />}
        <FieldGrid cols={4} className="mt-3 max-sm:grid-cols-2">
          {rule.evidence.map((e) => (
            <Field key={e.label} label={e.label} mono={e.mono}>{e.value}</Field>
          ))}
        </FieldGrid>
        {rule.accounts && rule.accounts.length > 0 && <Accounts rule={rule} />}
        {extra}
      </Section>
    </div>
  );
}

function CheckRow({ check: c }: { check: BreCheck }) {
  const o = OUTCOME[c.outcome];
  const Icon = o.icon;
  return (
    <li className="flex items-start gap-2 text-[10.4px]">
      <Icon size={14} className={cn("mt-px shrink-0", o.text)} aria-hidden />
      <div className="min-w-0 flex-1">
        <span className="font-semibold text-black">{c.label}</span>
        {c.detail && <span className="text-muted"> — {c.detail}</span>}
        {c.informational && <span className="ml-1 text-[8.8px] uppercase tracking-wide text-muted">(for information)</span>}
      </div>
      <OutcomeBadge outcome={c.outcome} />
    </li>
  );
}

function Compare({ items, documents, busy, onOpen }: { items: BreCompare[]; documents: Docs; busy: string | null; onOpen: (appId: number, doc: DocumentView) => void }) {
  return (
    <div className="mt-3 grid gap-2 md:grid-cols-3">
      {items.map((c) => {
        const docs = c.docTypes ? documents.filter((d) => c.docTypes?.includes(d.doc.docType.toUpperCase())) : [];
        return (
          <div key={c.label} className="min-w-0 rounded border border-line bg-neutral-50 px-3 py-2">
            <div className="flex items-center justify-between gap-2">
              <span className="text-[8.8px] font-semibold uppercase tracking-wide text-info-500">{c.label}</span>
              <OutcomeBadge outcome={c.outcome} />
            </div>
            <div className="mt-1 break-words font-mono text-[10.4px] tabular-nums text-black">{c.value ?? "—"}</div>
            {c.note && <div className="text-[8.8px] text-muted">{c.note}</div>}
            {docs.length > 0 && (
              <div className="mt-1.5 flex flex-wrap gap-1">
                {docs.map(({ applicationId, doc }) => {
                  const key = `${applicationId}-${doc.id}`;
                  return (
                    <button
                      key={key}
                      type="button"
                      onClick={() => onOpen(applicationId, doc)}
                      disabled={busy != null}
                      className="inline-flex items-center gap-1 rounded border border-line bg-white px-2 py-0.5 text-[8.8px] font-semibold text-navy hover:bg-navy-tint disabled:opacity-50"
                    >
                      {busy === key ? <Loader2 size={11} className="animate-spin" /> : <Eye size={11} />}
                      {docTypeLabel(doc.docType)}
                    </button>
                  );
                })}
              </div>
            )}
          </div>
        );
      })}
    </div>
  );
}

function Accounts({ rule }: { rule: BreRule }) {
  return (
    <div className="staff-table-scroll mt-3">
      <table className="staff-data-table staff-table-fit" aria-label={`Rule ${rule.id} accounts`}>
        <thead>
          <tr>
            <th>Lender</th>
            <th>Type</th>
            <th>Status</th>
            <th>Opened</th>
            <th>Closed</th>
            <th>Ownership</th>
            <th>Lender type</th>
            <th>Worst DPD (window)</th>
          </tr>
        </thead>
        <tbody>
          {(rule.accounts ?? []).map((a, i) => (
            <tr key={i} className={a.flagged ? "bg-error-50" : undefined}>
              <td className="whitespace-normal">{a.lender ?? "—"}</td>
              <td className="whitespace-normal">{a.accountType}</td>
              <td>{a.status}</td>
              <td>{a.openedOn ? formatDate(a.openedOn) : "—"}</td>
              <td>{a.closedOn ? formatDate(a.closedOn) : "—"}</td>
              <td>{a.ownership ?? "—"}</td>
              <td className="font-mono">{a.lenderType ?? "—"}</td>
              <td className="font-mono">{formatDpdDays(a.worstDpd)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
