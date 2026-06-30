"use client";

import * as React from "react";
import Link from "next/link";
import { useQuery } from "@tanstack/react-query";
import { Wallet, CheckCircle2, AlertTriangle } from "lucide-react";
import { Badge, Dialog, DialogHeader, DialogTitle } from "@/components/ui";
import { InfoRow } from "@/components/borrower/summary";
import {
  borrowerApi,
  paiseToINR,
  type PaymentView,
} from "@/lib/api/applications";
import { formatDate } from "@/lib/utils";

const todayISO = () => new Date().toISOString().slice(0, 10);

/** Colour a loan/application status: active=green, bad=red, terminal-neutral=grey, in-flight=blue. */
function statusVariant(status: string): React.ComponentProps<typeof Badge>["variant"] {
  switch (status) {
    case "ACTIVE":
      return "success";
    case "CLOSED":
    case "REPAID":
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

/**
 * A reusable popup showing one loan's full economics — what was sent, the upfront deductions, the
 * cost (interest + late penalty), what's been paid (with dates) and what's still owed. Self-contained:
 * it fetches the loan, its prepayment/penalty-aware outstanding (itemized), and its payments whenever
 * a `loanId` is open. Wired into the dashboard card, the applications rows, `/loans` and
 * `/transactions` — each just drives `loanId`/`open`.
 */
export function LoanDetailsDialog({
  loanId,
  open,
  onClose,
}: {
  loanId: number | null;
  open: boolean;
  onClose: () => void;
}) {
  const enabled = open && loanId != null;

  const loanQuery = useQuery({
    queryKey: ["my-loan", loanId],
    queryFn: () => borrowerApi.loan(loanId as number),
    enabled,
  });
  const outQuery = useQuery({
    queryKey: ["loan-outstanding", loanId],
    queryFn: () => borrowerApi.outstanding(loanId as number, todayISO()),
    enabled,
  });
  const payQuery = useQuery({
    queryKey: ["my-loan-payments", loanId],
    queryFn: () => borrowerApi.repayments(loanId as number),
    enabled,
  });

  const loan = loanQuery.data;
  const out = outQuery.data;
  const payments = payQuery.data ?? [];

  // Scheduled interest reconciles with the on-salary-day total (principal + interest = total),
  // unlike the prepayment-aware accrued figure which would not add up beside it.
  const scheduledInterestPaise = loan ? Math.max(0, loan.totalRepayablePaise - loan.principalPaise) : 0;
  const paidPaise =
    out?.verifiedPaise ??
    payments.filter((p) => p.status === "VERIFIED").reduce((s, p) => s + p.amountPaise, 0);
  const owedPaise = out?.outstandingPaise ?? loan?.outstandingPaise ?? 0;
  const settlementPaise = out?.settledAmountPaise ?? null;
  const isSettlement = settlementPaise != null;
  // A closed/zero-balance loan is settled — show "fully repaid", never a live accrued penalty
  // (which is computed from the due date vs today and would otherwise show on a paid-off loan).
  const settled =
    loan != null && (loan.status === "CLOSED" || loan.status === "REPAID" || owedPaise <= 0);
  const overdue = !settled && !isSettlement && loan != null && owedPaise > loan.totalRepayablePaise;
  // Late penalty only matters while still owing and overdue; it's the part of the balance above
  // the on-time total.
  const penaltyPaise = overdue
    ? out?.penaltyPaise ?? Math.max(0, owedPaise - (loan?.totalRepayablePaise ?? 0))
    : 0;
  const prepaySavingPaise =
    !settled && !overdue && !isSettlement && loan != null
      ? Math.max(0, loan.outstandingPaise - owedPaise)
      : 0;

  return (
    <Dialog open={open} onClose={onClose} className="!max-w-xl">
      <DialogHeader>
        <DialogTitle>Loan details {loanId != null ? `· #${loanId}` : ""}</DialogTitle>
        {loan && (
          <Badge variant={statusVariant(loan.status)} className="self-start">
            {loan.status}
          </Badge>
        )}
      </DialogHeader>

      {loanQuery.isLoading ? (
        <div className="h-48 animate-pulse rounded bg-grey-100" />
      ) : loanQuery.error || !loan ? (
        <p className="rounded border border-error-100 bg-error-50 p-4 text-sm text-error-700">
          Could not load this loan. Please close and try again.
        </p>
      ) : (
        <div className="space-y-5">
          {/* Disbursal — what was sent, and the upfront deductions. */}
          <section>
            <h4 className="mb-1 text-xs font-semibold uppercase tracking-wide text-muted">Disbursal</h4>
            <InfoRow label="Principal (sanctioned)" value={paiseToINR(loan.principalPaise)} />
            <InfoRow label="Processing fee (10%)" value={`− ${paiseToINR(loan.processingFeePaise)}`} />
            <InfoRow label="GST (18% on fee)" value={`− ${paiseToINR(loan.gstPaise)}`} />
            <InfoRow
              label="Amount disbursed to you"
              value={<span className="text-navy">{paiseToINR(loan.netDisbursedPaise)}</span>}
            />
          </section>

          {/* Cost — scheduled interest building to the total repayable on salary day. */}
          <section>
            <h4 className="mb-1 text-xs font-semibold uppercase tracking-wide text-muted">Cost</h4>
            <InfoRow label="Interest (1%/day over tenure)" value={paiseToINR(scheduledInterestPaise)} />
            {overdue && (
              <InfoRow
                label="Late penalty (2%/day, cap 30d)"
                value={<span className="text-error-600">{paiseToINR(penaltyPaise)}</span>}
              />
            )}
            <InfoRow label="Total repayable (on salary day)" value={paiseToINR(loan.totalRepayablePaise)} />
          </section>

          {/* Status — dates, what's paid, and the live balance. */}
          <section>
            <h4 className="mb-1 text-xs font-semibold uppercase tracking-wide text-muted">Repayment</h4>
            <InfoRow label="Disbursed on" value={loan.disbursedOn ? formatDate(loan.disbursedOn) : "—"} />
            <InfoRow label="Due date" value={loan.dueDate ? formatDate(loan.dueDate) : "—"} />
            <InfoRow label="Paid so far" value={paiseToINR(paidPaise)} />
            {settled ? (
              <div className="mt-2 flex items-center gap-2 rounded bg-success-50 px-4 py-3 text-sm font-semibold text-success-700">
                <CheckCircle2 size={16} className="flex-shrink-0" /> Fully repaid — loan closed
              </div>
            ) : (
              <>
                <div className="mt-2 flex items-baseline justify-between rounded bg-navy px-4 py-3 text-white">
                  <span className="text-sm font-semibold text-white/90">
                    {isSettlement ? "Settlement — full & final" : overdue ? "Pay now (incl. penalty)" : "Outstanding (pay today)"}
                  </span>
                  <span className="font-serif text-2xl font-bold text-gold">{paiseToINR(owedPaise)}</span>
                </div>
                {isSettlement && (
                  <p className="mt-2 flex items-start gap-2 text-xs text-navy">
                    <CheckCircle2 size={14} className="mt-0.5 flex-shrink-0" />
                    Our collections team approved a full &amp; final settlement of {paiseToINR(settlementPaise)}.
                  </p>
                )}
                {overdue && (
                  <p className="mt-2 flex items-start gap-2 text-xs text-error-600">
                    <AlertTriangle size={14} className="mt-0.5 flex-shrink-0" /> A late penalty is accruing —
                    clear it soon to stop the clock.
                  </p>
                )}
                {prepaySavingPaise > 0 && (
                  <p className="mt-2 flex items-start gap-2 text-xs text-success-700">
                    <CheckCircle2 size={14} className="mt-0.5 flex-shrink-0" />
                    Pay early &amp; save {paiseToINR(prepaySavingPaise)} — interest is charged only to the day you pay.
                  </p>
                )}
              </>
            )}
          </section>

          {/* Payment history with dates + verification status. */}
          {payments.length > 0 && (
            <section>
              <h4 className="mb-2 text-xs font-semibold uppercase tracking-wide text-muted">Payments</h4>
              <ul className="space-y-2">
                {payments.map((p) => (
                  <PaymentRow key={p.id} p={p} />
                ))}
              </ul>
            </section>
          )}

          {(loan.status === "ACTIVE" || loan.status === "OVERDUE") && (
            <Link href="/repay" className="btn btn-gold btn-block" onClick={onClose}>
              <Wallet size={16} /> Repay / prepay
            </Link>
          )}
        </div>
      )}
    </Dialog>
  );
}

function PaymentRow({ p }: { p: PaymentView }) {
  const map: Record<PaymentView["status"], { label: string; cls: string }> = {
    PENDING_VERIFICATION: { label: "Pending verification", cls: "bg-gold-50 text-gold-dark" },
    VERIFIED: { label: "Verified", cls: "bg-success-50 text-success-700" },
    REJECTED: { label: "Rejected", cls: "bg-error-50 text-error-700" },
  };
  const s = map[p.status];
  return (
    <li className="flex items-center justify-between gap-3 text-sm">
      <span className="flex items-center gap-2">
        <span className="font-semibold text-ink">{paiseToINR(p.amountPaise)}</span>
        <span className="text-xs text-muted">
          {p.method === "UPI" ? "UPI" : "Bank"}
          {p.txnRef ? ` · ${p.txnRef}` : ""}
          {p.paidOn ? ` · ${formatDate(p.paidOn)}` : ""}
        </span>
      </span>
      <span className={`rounded-full px-2 py-0.5 text-xs font-semibold ${s.cls}`}>{s.label}</span>
    </li>
  );
}
