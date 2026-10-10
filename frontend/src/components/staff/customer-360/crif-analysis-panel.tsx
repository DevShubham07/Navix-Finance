"use client";

/**
 * "CRIF report analysis" — what the bureau report says about the borrower's work and credit mix.
 * Stands in for the bank statement analyser until that API is live: shown in the Banking tab, the
 * Third-party "Bank Statement Analysis" row and BRE rule 5, from one component so they cannot drift.
 */

import * as React from "react";
import { FileSearch } from "lucide-react";
import { Badge, EmptyState, Skeleton } from "@/components/ui";
import { Field, FieldGrid } from "@/components/ui/field-grid";
import { Section } from "@/components/staff/detail-parts";
import { formatDpdDays } from "@/components/staff/credit/tradeline-table";
import type { CustomerDetail } from "@/lib/api/applications";
import type { BureauReport } from "@/lib/customers/bre/bureau-report";
import { LOOKBACK_MONTHS } from "@/lib/customers/bre/bureau-report";
import type { ProfileAnalysis } from "@/lib/customers/bre/profile-analysis";
import { useProfileAnalysis } from "@/lib/customers/bre/use-bre-data";
import { formatDate } from "@/lib/utils";

const SUB = "mb-1.5 mt-3 text-[9.6px] font-bold uppercase tracking-wide text-muted";

const bureauLabel = (r: BureauReport) => `${r.kind === "CRIF" ? "CRIF" : "Experian"} · ${r.source === "RAW" ? "full report" : "parsed tradelines"}`;

export function CrifAnalysisPanel({ report, analysis }: { report: BureauReport | null; analysis: ProfileAnalysis }) {
  if (!report) {
    return (
      <EmptyState
        icon={<FileSearch size={22} />}
        title="No bureau report to analyse"
        hint="The analysis appears once the bureau pull returns a report."
        className="py-4"
      />
    );
  }
  const mix = analysis.mix;
  const hits = analysis.hits.filter((h) => !h.source.startsWith("Declared"));
  return (
    <div className="text-[10.4px] text-black">
      <FieldGrid cols={4} className="max-sm:grid-cols-2">
        <Field label="Source" keyLabel>{bureauLabel(report)}</Field>
        <Field label="Report date">{report.reportDate ? formatDate(report.reportDate) : null}</Field>
        <Field label="Score" mono>{report.score}</Field>
        <Field
          label="Employment (bureau)"
          tone={analysis.bureauEmployment === "SELF_EMPLOYED" ? "error" : analysis.bureauEmployment === "SALARIED" ? "success" : "muted"}
        >
          {analysis.bureauEmployment === "SELF_EMPLOYED" ? "Self-employed" : analysis.bureauEmployment === "SALARIED" ? "Salaried" : "Not reported"}
        </Field>
      </FieldGrid>

      <div className={SUB}>Occupation and income reported</div>
      {report.occupations.length + report.incomes.length === 0 ? (
        <p className="text-muted">The bureau reports no occupation or income for this borrower.</p>
      ) : (
        <ul className="space-y-1">
          {report.occupations.map((o, i) => (
            <li key={`o-${i}`} className="flex flex-wrap items-center gap-1.5">
              <Badge variant="neutral" size="sm">{o.value}</Badge>
              <span className="text-muted">{o.where}{o.reportedOn ? ` · ${formatDate(o.reportedOn)}` : ""}</span>
            </li>
          ))}
          {report.incomes.map((o, i) => (
            <li key={`i-${i}`} className="flex flex-wrap items-center gap-1.5">
              <Badge variant="neutral" size="sm">Income ₹{o.value}</Badge>
              <span className="text-muted">{o.where}</span>
            </li>
          ))}
        </ul>
      )}

      {mix && (
        <>
          <div className={SUB}>Credit mix · last {LOOKBACK_MONTHS} months</div>
          <FieldGrid cols={4} className="max-sm:grid-cols-2">
            <Field label="Accounts" mono caption={`${mix.active} active · ${mix.closed} closed`}>{mix.total}</Field>
            <Field label="Personal loans" mono caption={`${mix.personalLoans} all time`}>{mix.personalLoansInWindow}</Field>
            <Field label="NBFC loans" mono caption={`${mix.nbfcLoans} all time`}>{mix.nbfcLoansInWindow}</Field>
            <Field label="Worst DPD" mono tone={(mix.worstDpdInWindow ?? 0) > 0 ? "warning" : "ink"}>{mix.worstDpdInWindow == null ? null : formatDpdDays(mix.worstDpdInWindow)}</Field>
            <Field label="Credit cards" mono>{mix.creditCards}</Field>
            <Field label="Guarantor / joint" mono tone={mix.nonIndividual > 0 ? "warning" : "ink"}>{report.source === "RAW" ? mix.nonIndividual : null}</Field>
          </FieldGrid>
        </>
      )}

      <div className={SUB}>Profession screen · self-employed, army, lawyer, MHA</div>
      {hits.length === 0 ? (
        <p className="text-success-700">No restricted profession found in {analysis.sources.length} source(s).</p>
      ) : (
        <ul className="space-y-1">
          {hits.map((h, i) => (
            <li key={i} className="flex flex-wrap items-center gap-1.5">
              <Badge variant="error" size="sm">{h.label}</Badge>
              <span>{h.source}: <span className="font-mono">“{h.value}”</span></span>
            </li>
          ))}
        </ul>
      )}
      <details className="mt-2">
        <summary className="cursor-pointer text-[10px] text-muted">Sources scanned ({analysis.sources.length})</summary>
        <ul className="mt-1 space-y-0.5">
          {analysis.sources.map((s, i) => (
            <li key={i}><span className="text-muted">{s.source}:</span> {s.value}</li>
          ))}
        </ul>
      </details>
    </div>
  );
}

/** Self-fetching Section for the Banking tab and the Third-party bank row. */
export function CrifAnalysisSection({ detail, applicationId }: { detail: CustomerDetail; applicationId: number | null }) {
  const a = useProfileAnalysis(detail, applicationId);
  return (
    <Section
      title="CRIF report analysis"
      icon={FileSearch}
      pill={<Badge variant="neutral" size="sm" className="ml-1 normal-case tracking-normal">Bank statement analyser not live</Badge>}
    >
      {applicationId == null ? (
        <EmptyState title="No application yet" className="py-4" />
      ) : a.isLoading ? (
        <Skeleton variant="line" rows={3} />
      ) : (
        <CrifAnalysisPanel report={a.report} analysis={a.analysis} />
      )}
    </Section>
  );
}
