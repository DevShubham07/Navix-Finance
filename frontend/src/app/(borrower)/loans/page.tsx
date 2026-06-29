"use client";

import * as React from "react";
import Link from "next/link";
import { useQuery } from "@tanstack/react-query";
import { ArrowRight, FileText, CalendarClock } from "lucide-react";
import { Badge } from "@/components/ui";
import {
  borrowerApi,
  paiseToINR,
  statusLabel,
  type ApplicationStatus,
  type ApplicationView,
} from "@/lib/api/applications";
import { formatDate } from "@/lib/utils";

/** Colour an application status: active=green, bad=red, terminal-neutral=grey, in-flight=blue. */
function statusVariant(status: ApplicationStatus): React.ComponentProps<typeof Badge>["variant"] {
  switch (status) {
    case "ACTIVE":
      return "success";
    case "CLOSED":
    case "CANCELLED":
      return "neutral";
    case "OVERDUE":
    case "DEFAULTED":
    case "WRITTEN_OFF":
    case "REJECTED":
    case "KYC_REJECTED":
    case "DISBURSEMENT_FAILED":
      return "error";
    default:
      return "info";
  }
}

const ACTIVE_STATUSES = new Set<ApplicationStatus>(["ACTIVE", "OVERDUE"]);
const PAST_STATUSES = new Set<ApplicationStatus>([
  "CLOSED",
  "DEFAULTED",
  "WRITTEN_OFF",
  "REJECTED",
  "KYC_REJECTED",
  "CANCELLED",
]);

export default function LoansPage() {
  const q = useQuery({ queryKey: ["my-apps"], queryFn: borrowerApi.myApplications });

  const apps = q.data ?? [];
  const active = apps.filter((a) => ACTIVE_STATUSES.has(a.status));
  const past = apps.filter((a) => PAST_STATUSES.has(a.status));
  const inProgress = apps.filter((a) => !ACTIVE_STATUSES.has(a.status) && !PAST_STATUSES.has(a.status));

  return (
    <div className="container max-w-content py-10">
      <div className="mb-7">
        <h1 className="mb-0">Your loans</h1>
        <p className="mt-1 text-muted">Every advance you&apos;ve applied for, with its current status.</p>
      </div>

      {q.isLoading ? (
        <div className="space-y-4">
          {[0, 1].map((i) => (
            <div key={i} className="h-32 animate-pulse rounded border border-line bg-white" />
          ))}
        </div>
      ) : q.error ? (
        <div className="rounded border border-error-100 bg-error-50 p-5 text-sm text-error-700">
          Could not load your loans. Please refresh and try again.
        </div>
      ) : apps.length === 0 ? (
        <EmptyState />
      ) : (
        <div className="space-y-8">
          <Section title="Active advances" apps={active} emptyHint="No active advance right now." />
          <Section title="In progress" apps={inProgress} />
          <Section title="Past loans" apps={past} />
        </div>
      )}
    </div>
  );
}

function Section({ title, apps, emptyHint }: { title: string; apps: ApplicationView[]; emptyHint?: string }) {
  if (apps.length === 0 && !emptyHint) return null;
  return (
    <section>
      <h2 className="mb-3 font-serif text-lg font-semibold text-navy">
        {title} <span className="text-sm font-normal text-muted">({apps.length})</span>
      </h2>
      {apps.length === 0 ? (
        <p className="rounded border border-dashed border-line bg-white px-5 py-4 text-sm text-muted">{emptyHint}</p>
      ) : (
        <div className="space-y-4">
          {apps.map((app) => (
            <LoanApplicationCard key={app.id} app={app} />
          ))}
        </div>
      )}
    </section>
  );
}

function LoanApplicationCard({ app }: { app: ApplicationView }) {
  return (
    <div className="rounded border border-line bg-white p-5 shadow-sm">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div className="min-w-0">
          <div className="font-serif text-lg font-semibold text-navy">Application #{app.id}</div>
          <div className="mt-0.5 text-sm text-muted">
            Requested {paiseToINR(app.amountRequestedPaise)}
            {app.purpose ? ` · ${app.purpose}` : ""}
          </div>
        </div>
        <Badge variant={statusVariant(app.status)}>{statusLabel(app.status)}</Badge>
      </div>

      {app.loanId != null && <LoanDetails loanId={app.loanId} />}
    </div>
  );
}

/** The minted loan's economics — fetched per application that reached disbursal. */
function LoanDetails({ loanId }: { loanId: number }) {
  const q = useQuery({
    queryKey: ["my-loan", loanId],
    queryFn: () => borrowerApi.loan(loanId),
  });

  if (q.isLoading) return <div className="mt-4 h-20 animate-pulse rounded bg-grey-100" />;
  if (q.error || !q.data) return null;
  const loan = q.data;

  return (
    <div className="mt-4 border-t border-grey-200 pt-4">
      <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
        <Stat label="Principal" value={paiseToINR(loan.principalPaise)} />
        <Stat label="Net disbursed" value={paiseToINR(loan.netDisbursedPaise)} />
        <Stat label="Total repayable" value={paiseToINR(loan.totalRepayablePaise)} />
        <Stat label="Outstanding" value={paiseToINR(loan.outstandingPaise)} />
        <div>
          <div className="flex items-center gap-1 text-xs text-muted">
            <CalendarClock size={13} /> Due date
          </div>
          <div className="font-serif text-base font-semibold text-navy">
            {loan.dueDate ? formatDate(loan.dueDate) : "—"}
          </div>
          <div className="text-[11px] text-muted">due on your salary day</div>
        </div>
        <Stat label="Disbursed on" value={loan.disbursedOn ? formatDate(loan.disbursedOn) : "—"} />
        <Stat label="Loan #" value={`#${loan.id}`} />
        <div>
          <div className="text-xs text-muted">Loan status</div>
          <Badge variant={statusVariant(loan.status as ApplicationStatus)}>{loan.status}</Badge>
        </div>
      </div>
      <div className="mt-3 text-[11px] text-muted">
        Upfront deductions: processing fee {paiseToINR(loan.processingFeePaise)} · GST {paiseToINR(loan.gstPaise)}
      </div>
    </div>
  );
}

function Stat({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <div className="text-xs text-muted">{label}</div>
      <div className="font-serif text-base font-semibold text-navy">{value}</div>
    </div>
  );
}

function EmptyState() {
  return (
    <div className="rounded border border-line bg-white p-10 text-center shadow-sm">
      <span className="mx-auto mb-3 grid h-14 w-14 place-items-center rounded-full bg-navy-tint text-navy">
        <FileText size={26} />
      </span>
      <h2 className="text-xl">No loans yet</h2>
      <p className="mb-4 text-muted">When you apply for an advance it&apos;ll show up here with live status.</p>
      <Link href="/signup/mobile-otp" className="btn btn-gold">
        Apply now <ArrowRight size={16} />
      </Link>
    </div>
  );
}
