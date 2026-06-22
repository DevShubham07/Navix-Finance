"use client";

import * as React from "react";
import Link from "next/link";
import {
  Wallet, CalendarClock, ArrowRight, AlertTriangle, CheckCircle2, Sparkles, FileClock, RotateCw,
} from "lucide-react";
import { Badge } from "@/components/ui";
import { useBorrowerJourney, type BorrowerStatus } from "@/lib/mock/borrower";
import { useMounted } from "@/hooks/use-mounted";
import { formatINR0, formatDate } from "@/lib/utils";
import { daysBetween } from "@/lib/calc/loan-math";

const PIPELINE_HREF: Partial<Record<BorrowerStatus, string>> = {
  APPLIED: "/loan/status",
  UNDER_REVIEW: "/loan/status",
  APPROVED: "/loan/apply",
  DOCS_SIGNED: "/loan/bank-verify",
  BANK_VERIFIED: "/loan/bank-verify",
  DISBURSING: "/loan/status",
};

export default function DashboardPage() {
  const mounted = useMounted();
  const j = useBorrowerJourney();

  if (!mounted) {
    return <div className="container py-10"><div className="h-72 rounded border border-line bg-white" /></div>;
  }

  const { status, loan, applicant } = j;
  const firstName = applicant.fullName?.split(" ")[0] || "there";
  const hasLoan = Boolean(loan) && (status === "ACTIVE" || status === "OVERDUE" || status === "REPAID");

  return (
    <div className="container py-10">
      <div className="mb-7 flex flex-wrap items-center justify-between gap-3">
        <div>
          <h1 className="mb-0">Hi, {firstName}</h1>
          <p className="mt-1 text-muted">Here&apos;s where your advance stands.</p>
        </div>
        <StatusChip status={status} />
      </div>

      <div className="grid gap-6 lg:grid-cols-[1fr_minmax(0,340px)]">
        <div>
          {hasLoan && loan ? (
            <LoanCard
              status={status}
              total={loan.costBreakdown.totalRepayable}
              outstanding={loan.outstanding}
              penalty={loan.penalty}
              principal={loan.principal}
              dueISO={loan.dueDateISO}
            />
          ) : status === "DECLINED" ? (
            <InfoCard
              icon={<AlertTriangle size={26} />}
              tone="error"
              title="Application not approved"
              body={j.declineReason ?? "Your application didn't meet current credit policy. You can reapply after 90 days."}
            />
          ) : PIPELINE_HREF[status] ? (
            <InfoCard
              icon={<FileClock size={26} />}
              tone="navy"
              title="Application in progress"
              body="Pick up where you left off to get your advance."
              cta={{ href: PIPELINE_HREF[status]!, label: "Continue" }}
            />
          ) : (
            <InfoCard
              icon={<Sparkles size={26} />}
              tone="navy"
              title="Get an instant salary advance"
              body="Draw up to 25% of your monthly salary, repaid in one instalment on your salary day."
              cta={{ href: "/signup/pan", label: "Start application" }}
            />
          )}
        </div>

        <aside className="flex flex-col gap-4">
          <div className="rounded border border-line bg-white p-5 shadow-sm">
            <div className="mb-1 flex items-center gap-2 text-sm font-semibold text-navy">
              <Wallet size={16} /> Eligible limit
            </div>
            <div className="font-serif text-2xl font-bold text-navy">
              {applicant.monthlySalary ? formatINR0(j.sanctionedLimit()) : "—"}
            </div>
            <p className="mt-1 text-xs text-muted">
              {applicant.monthlySalary ? "25% of your salary, after risk review" : "Add your salary to see your limit"}
            </p>
          </div>

          {status === "REPAID" && (
            <Link href="/reloan" className="btn btn-gold btn-block">
              <RotateCw size={16} /> Borrow again
            </Link>
          )}

          <div className="rounded border border-line bg-white p-5 shadow-sm">
            <div className="mb-2 text-sm font-semibold text-navy">Quick links</div>
            <ul className="text-sm">
              <li><Link href="/loan/status" className="-mx-2 block rounded px-2 py-2 text-ink hover:bg-grey-100 hover:text-navy">Application status</Link></li>
              <li><Link href="/repay" className="-mx-2 block rounded px-2 py-2 text-ink hover:bg-grey-100 hover:text-navy">Repay / prepay</Link></li>
              <li><Link href="/profile" className="-mx-2 block rounded px-2 py-2 text-ink hover:bg-grey-100 hover:text-navy">Profile &amp; KYC</Link></li>
            </ul>
          </div>
        </aside>
      </div>
    </div>
  );
}

function StatusChip({ status }: { status: BorrowerStatus }) {
  const map: Record<BorrowerStatus, { label: string; variant: React.ComponentProps<typeof Badge>["variant"] }> = {
    NEW: { label: "Not started", variant: "neutral" },
    APPLIED: { label: "Applied", variant: "info" },
    UNDER_REVIEW: { label: "Under review", variant: "warning" },
    APPROVED: { label: "Approved", variant: "success" },
    DOCS_SIGNED: { label: "Docs signed", variant: "info" },
    BANK_VERIFIED: { label: "Bank verified", variant: "info" },
    DISBURSING: { label: "Disbursing", variant: "warning" },
    ACTIVE: { label: "Active loan", variant: "success" },
    REPAID: { label: "Repaid", variant: "neutral" },
    DECLINED: { label: "Declined", variant: "error" },
    OVERDUE: { label: "Overdue", variant: "error" },
  };
  const m = map[status];
  return <Badge variant={m.variant}>{m.label}</Badge>;
}

function LoanCard({
  status, total, outstanding, penalty, principal, dueISO,
}: {
  status: BorrowerStatus;
  total: number; outstanding: number; penalty: number; principal: number; dueISO: string;
}) {
  const repaid = Math.max(0, total - outstanding);
  const pct = total > 0 ? Math.min(100, Math.round((repaid / total) * 100)) : 0;
  const due = new Date(dueISO);
  const days = daysBetween(new Date(), due);
  const overdue = status === "OVERDUE" || days < 0;

  if (status === "REPAID") {
    return (
      <div className="rounded border border-success-100 bg-success-50/50 p-7 shadow-sm">
        <span className="mb-3 grid h-14 w-14 place-items-center rounded-full bg-success-50 text-success-600">
          <CheckCircle2 size={28} />
        </span>
        <h2 className="text-2xl">Loan fully repaid</h2>
        <p className="mb-4 text-muted">Your {formatINR0(principal)} advance is closed and in good standing. Ready when you need it again.</p>
        <Link href="/reloan" className="btn btn-gold">Borrow again <ArrowRight size={16} /></Link>
      </div>
    );
  }

  return (
    <div className="overflow-hidden rounded border border-line bg-white shadow-sm">
      <div className={`flex items-center justify-between gap-3 px-6 py-4 text-white ${overdue ? "bg-error-600" : "bg-navy"}`}>
        <span className="font-serif text-base font-semibold">{overdue ? "Repayment overdue" : "Active advance"}</span>
        <span className="text-sm text-white/80">{overdue ? `${Math.abs(days)} days past due` : `Due in ${days} days`}</span>
      </div>
      <div className="p-6">
        <div className="text-sm text-muted">Total outstanding</div>
        <div className="font-serif text-3xl font-bold text-navy sm:text-4xl">{formatINR0(outstanding)}</div>
        {penalty > 0 && (
          <div className="mt-1 flex items-center gap-1.5 text-sm font-semibold text-error-600">
            <AlertTriangle size={14} /> Includes {formatINR0(penalty)} late penalty (2%/day, cap 30d)
          </div>
        )}

        <div className="mt-5 h-2 w-full overflow-hidden rounded-full bg-grey-200">
          <div className="h-full rounded-full bg-success-600 transition-all" style={{ width: `${pct}%` }} />
        </div>
        <div className="mt-1.5 flex justify-between text-xs text-muted">
          <span>{formatINR0(repaid)} repaid</span>
          <span>{formatINR0(total)} total</span>
        </div>

        <div className="mt-5 flex flex-wrap items-center gap-3 border-t border-grey-200 pt-5 text-sm text-muted">
          <span className="flex items-center gap-1.5"><CalendarClock size={15} /> Due {formatDate(due)}</span>
        </div>

        <Link href="/repay" className="btn btn-gold btn-block mt-5">
          <Wallet size={16} /> {overdue ? "Pay now" : "Repay / prepay"}
        </Link>
      </div>
    </div>
  );
}

function InfoCard({
  icon, title, body, cta, tone,
}: {
  icon: React.ReactNode; title: string; body: string;
  cta?: { href: string; label: string }; tone: "navy" | "error";
}) {
  return (
    <div className={`rounded border p-7 shadow-sm ${tone === "error" ? "border-error-100 bg-error-50/40" : "border-line bg-white"}`}>
      <span className={`mb-3 grid h-14 w-14 place-items-center rounded-full ${tone === "error" ? "bg-error-50 text-error-600" : "bg-navy-tint text-navy"}`}>
        {icon}
      </span>
      <h2 className="text-2xl">{title}</h2>
      <p className="mb-4 text-muted">{body}</p>
      {cta && <Link href={cta.href} className="btn btn-gold">{cta.label} <ArrowRight size={16} /></Link>}
    </div>
  );
}
