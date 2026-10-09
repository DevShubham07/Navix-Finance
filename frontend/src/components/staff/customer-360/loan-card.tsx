"use client";

/** Pieces shared by the Loan and Repayment tabs: selector, card header, financial summary strip. */

import * as React from "react";
import { useQuery } from "@tanstack/react-query";
import { ChevronDown, ChevronUp, ExternalLink, Download, IndianRupee, Loader2, Lock } from "lucide-react";
import { StatusBadge } from "@/components/ui";
import { Field, FieldGrid } from "@/components/ui/field-grid";
import type { TabCtx } from "@/components/staff/customer-360/types";
import { istCalendarToday } from "@/lib/customers/customer-360";
import { formatDate } from "@/lib/utils";
import {
  customersApi,
  openDocument,
  paiseToINR,
  staffApi,
  type DocumentView,
  type LoanView,
  type OutstandingView,
} from "@/lib/api/applications";

export function todayISO(now: Date = istCalendarToday()): string {
  const m = String(now.getMonth() + 1).padStart(2, "0");
  const d = String(now.getDate()).padStart(2, "0");
  return `${now.getFullYear()}-${m}-${d}`;
}

/** Which loan is on screen: the one on this application, else the newest. */
export function useSelectedLoanId(ctx: TabCtx): [number | null, (id: number) => void] {
  const [picked, setPicked] = React.useState<number | null>(null);
  const loans = ctx.detail.loans;
  const fallback = ctx.app?.loanId ?? loans[0]?.id ?? null;
  return [picked != null && loans.some((l) => l.id === picked) ? picked : fallback, setPicked];
}

/** The loan plus today's server-computed outstanding (payload first, then a fetch). */
export function useLoanData(ctx: TabCtx, loanId: number | null) {
  const loan = ctx.detail.loans.find((l) => l.id === loanId) ?? null;
  const fromPayload = loanId != null ? ctx.detail.outstandingByLoanId?.[String(loanId)] : undefined;
  const today = todayISO();
  const q = useQuery({
    queryKey: ["staff-loan-out", loanId, today],
    queryFn: () => staffApi.outstanding(loanId as number, today),
    enabled: loanId != null && !fromPayload,
  });
  const outstanding: OutstandingView | null = fromPayload ?? q.data ?? null;
  return { loan, outstanding };
}

export function LoanSelector({
  loans,
  value,
  onChange,
}: {
  loans: LoanView[];
  value: number | null;
  onChange: (id: number) => void;
}) {
  if (loans.length < 2) return null;
  return (
    <div className="flex flex-wrap gap-2" role="group" aria-label="Select loan">
      {loans.map((l) => (
        <button
          key={l.id}
          type="button"
          className={`cal-preset${l.id === value ? " on" : ""}`}
          aria-pressed={l.id === value}
          onClick={() => onChange(l.id)}
        >
          Loan #{l.id} · {humanStatus(l.status)}
        </button>
      ))}
    </div>
  );
}

const humanStatus = (s: string) => s.charAt(0) + s.slice(1).toLowerCase().replace(/_/g, " ");

function DocButton({ appId, doc, label, download }: { appId: number; doc: DocumentView | undefined; label: string; download?: boolean }) {
  const [busy, setBusy] = React.useState(false);
  const open = async () => {
    if (!doc) return;
    setBusy(true);
    try {
      if (doc.s3) {
        const { url } = await staffApi.documentUrl(appId, doc.id);
        window.open(url, "_blank", "noopener,noreferrer");
      } else {
        openDocument(await staffApi.document(appId, doc.id), download);
      }
    } finally {
      setBusy(false);
    }
  };
  const Icon = download ? Download : ExternalLink;
  return (
    <button type="button" className="btn btn-sm btn-outline disabled:opacity-50" disabled={!doc || busy} onClick={open}>
      {busy ? <Loader2 size={13} className="animate-spin" /> : <Icon size={13} />} {label}
    </button>
  );
}

export function LoanCardHeader({
  ctx,
  loan,
  collapsed,
  onToggle,
}: {
  ctx: TabCtx;
  loan: LoanView;
  collapsed: boolean;
  onToggle: () => void;
}) {
  const appId = ctx.detail.applications.find((a) => a.loanId === loan.id)?.id ?? ctx.applicationId;
  const groupsQ = useQuery({
    queryKey: ["customer-documents", ctx.customerId],
    queryFn: () => customersApi.documents(ctx.customerId),
  });
  const docs = groupsQ.data?.find((g) => g.applicationId === appId)?.documents ?? [];
  const find = (t: string) => docs.find((d) => d.docType === t);
  return (
    <div className="flex flex-wrap items-center gap-2 border-b border-line pb-3">
      <Lock size={14} className="text-muted" />
      <h3 className="text-[11.2px] font-bold text-navy">Current loan details</h3>
      <div className="ml-auto flex flex-wrap items-center gap-2">
        <StatusBadge kind="application" value={loan.status} />
        {appId != null && (
          <>
            <DocButton appId={appId} doc={find("SANCTION_LETTER")} label="Sanction letter" />
            <DocButton appId={appId} doc={find("SIGNED_AGREEMENT")} label="Signed agreement" download />
          </>
        )}
        <button type="button" className="btn btn-sm btn-outline btn-icon" onClick={onToggle} aria-expanded={!collapsed} aria-label={collapsed ? "Expand loan card" : "Collapse loan card"}>
          {collapsed ? <ChevronDown size={14} /> : <ChevronUp size={14} />}
        </button>
      </div>
    </div>
  );
}

export function FinancialSummaryStrip({ loan, outstanding }: { loan: LoanView; outstanding: OutstandingView | null }) {
  const closed = loan.status === "CLOSED";
  const overdue = loan.status === "OVERDUE";
  const due = closed ? 0 : (outstanding?.outstandingPaise ?? loan.outstandingPaise);
  const money = (p: number | undefined) => (p == null ? null : paiseToINR(p));
  const bad = overdue || loan.status === "DEFAULTED" || loan.status === "WRITTEN_OFF";
  const statusTone = closed ? "text-success-700" : bad ? "text-error-700" : "text-info-700";
  const penaltyDays = outstanding?.penaltyDays ?? 0;
  return (
    <div className="rounded-xl border border-gold-soft bg-gold-50 px-4 py-3">
      <div className="mb-2 flex items-center gap-1.5">
        <IndianRupee size={13} className="text-gold" />
        <span className="text-[9.6px] font-bold uppercase tracking-wide text-gold-dark">
          Financial summary ({formatDate(istCalendarToday())}) · 1% per day
        </span>
        <span className={`ml-auto text-[9.6px] font-bold uppercase ${statusTone}`}>{humanStatus(loan.status)}</span>
      </div>
      <FieldGrid cols={4} className="[&>div>div:nth-child(2)]:text-[14.4px] [&>div>div:nth-child(2)]:font-bold">
        <Field
          label="Amount due today"
          keyLabel
          tone={closed ? "success" : "navy"}
          caption={closed && loan.closedOn ? `Closed on ${formatDate(loan.closedOn)}` : undefined}
        >
          {paiseToINR(due)}
        </Field>
        {closed && <Field label="Paid (verified)" tone="success">{money(outstanding?.verifiedPaise)}</Field>}
        <Field
          label="Interest accrued"
          keyLabel
          tone="warning"
          caption={outstanding?.interestDays != null ? `${outstanding.interestDays} ${outstanding.interestDays === 1 ? "day" : "days"} × 1%` : undefined}
        >
          {money(outstanding?.interestPaise)}
        </Field>
        <Field
          label="Late penalty"
          tone="error"
          caption={penaltyDays > 0 ? `${penaltyDays} ${penaltyDays === 1 ? "day" : "days"} × 2%` : "starts after the grace day"}
        >
          {money(outstanding?.penaltyPaise)}
        </Field>
        {!closed && <Field label="Interest rate" caption="simple, on principal">1% / day</Field>}
      </FieldGrid>
      <p className="mt-2 text-[8.8px] text-muted">
        <span className="font-bold text-warning-800">Note:</span> Penalty is 2%/day of principal, starts the day after the
        grace day and is capped at 30 days.
      </p>
    </div>
  );
}
