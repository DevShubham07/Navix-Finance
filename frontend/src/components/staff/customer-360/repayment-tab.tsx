"use client";

/** Repayment tab (design §3.5): the ledger, key facts, payments with Verify/Reject, record form. */

import * as React from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Check, ChevronDown, ChevronUp, Loader2, Plus, Sparkles, X } from "lucide-react";
import { Badge, EmptyState, StatusBadge } from "@/components/ui";
import { Field, FieldGrid } from "@/components/ui/field-grid";
import { PaymentProofLink } from "@/components/ui/payment-proof-link";
import { Section } from "@/components/staff/detail-parts";
import { AdminLogPaymentButton } from "@/components/staff/admin-log-payment";
import { RejectDialog } from "@/components/staff/repayment-verify-queue";
import {
  FinancialSummaryStrip,
  LoanCardHeader,
  LoanSelector,
  useLoanData,
  useSelectedLoanId,
} from "@/components/staff/customer-360/loan-card";
import type { TabCtx } from "@/components/staff/customer-360/types";
import { buildLedger, type LedgerRow } from "@/lib/calc/loan-ledger";
import { daysBetween } from "@/lib/calc/loan-math";
import { hasPermission } from "@/lib/auth/rbac";
import { useStaffSession } from "@/lib/auth/staff-session";
import { formatApiError } from "@/lib/api/errors";
import { isoDayToLocalDate, istCalendarToday } from "@/lib/customers/customer-360";
import { formatDate } from "@/lib/utils";
import {
  paiseToINR,
  staffApi,
  type LoanView,
  type PaymentView,
  REJECTION_REASON_LABEL,
  type RejectionReasonCode,
} from "@/lib/api/applications";

const inr = (p: number | null | undefined) => (p == null ? null : paiseToINR(p));
const METHOD_LABEL: Record<string, string> = { UPI: "UPI", BANK_TRANSFER: "Bank transfer", NACH: "NACH" };
const TH = "py-2 pr-3 text-[9.2px] font-semibold uppercase tracking-wide";

export function RepaymentTab(ctx: TabCtx) {
  const [loanId, setLoanId] = useSelectedLoanId(ctx);
  const [collapsed, setCollapsed] = React.useState(false);
  const { loan, outstanding } = useLoanData(ctx, loanId);
  if (loan == null) return <EmptyState title="No loan on this application yet — nothing to repay." />;
  return (
    <div className="space-y-4">
      <Section title="Repayment">
        <div className="space-y-3">
          <LoanSelector loans={ctx.detail.loans} value={loan.id} onChange={setLoanId} />
          <LoanCardHeader ctx={ctx} loan={loan} collapsed={collapsed} onToggle={() => setCollapsed((c) => !c)} />
          {!collapsed && <FinancialSummaryStrip loan={loan} outstanding={outstanding} />}
        </div>
      </Section>
      <PaymentsPanel ctx={ctx} loan={loan} />
    </div>
  );
}

function PaymentsPanel({ ctx, loan }: { ctx: TabCtx; loan: LoanView }) {
  const qc = useQueryClient();
  const role = useStaffSession().session?.role;
  const canVerify = role != null && hasPermission(role, "loan:activate");
  const canRecord = role != null && hasPermission(role, "customer:manage");
  const [addOpen, setAddOpen] = React.useState(false);
  const [rejectTarget, setRejectTarget] = React.useState<PaymentView | null>(null);
  const { outstanding } = useLoanData(ctx, loan.id);
  const closed = loan.status === "CLOSED";
  const settled = outstanding?.settledAmountPaise ?? null;

  const paymentsQ = useQuery({
    queryKey: ["staff-repayments", loan.id],
    queryFn: () => staffApi.repayments(loan.id),
  });
  const payments = paymentsQ.data ?? ctx.detail.payments.filter((p) => p.loanId === loan.id);
  const ledger = buildLedger({ loan, outstanding, payments, today: istCalendarToday() });
  const total = ledger.rows.find((r) => r.key === "total")!;

  const done = () => {
    qc.invalidateQueries({ queryKey: ["staff-repayments"] });
    qc.invalidateQueries({ queryKey: ["staff-loan-out"] });
    qc.invalidateQueries({ queryKey: ["staff-pending-repayments"] });
    qc.invalidateQueries({ queryKey: ["staff-queue"] });
    qc.invalidateQueries({ queryKey: ["staff-transactions"] });
    ctx.onChanged();
  };
  const verify = useMutation({ mutationFn: (p: PaymentView) => staffApi.verifyRepayment(loan.id, p.id), onSuccess: done });
  const reject = useMutation({
    mutationFn: (v: { p: PaymentView; reason: RejectionReasonCode; note: string }) =>
      staffApi.rejectRepayment(loan.id, v.p.id, { reason: v.reason, note: v.note.trim() || undefined }),
    onSuccess: () => {
      done();
      setRejectTarget(null);
    },
  });
  const actionError = verify.error ?? reject.error;

  const app = ctx.detail.applications.find((a) => a.loanId === loan.id) ?? ctx.app;
  const due = isoDayToLocalDate(loan.dueDate);
  const disbursed = isoDayToLocalDate(loan.disbursedOn);

  return (
    <>
      <Section title="How it adds up">
        <div className="staff-table-scroll">
          <table className="staff-data-table staff-table-fit">
            <thead>
              <tr>
                <th className={TH}>Amount type</th>
                <th className={`num ${TH}`}>Payable (at term)</th>
                <th className={`num ${TH}`}>Due today</th>
                <th className={`num ${TH}`}>Received (verified)</th>
                <th className={`num ${TH}`}>Outstanding</th>
              </tr>
            </thead>
            <tbody>
              {ledger.rows.map((r) => (
                <LedgerTr key={r.key} row={r} overdue={ledger.isOverdue} closed={closed} settled={settled != null} />
              ))}
            </tbody>
          </table>
        </div>
        <p className="mt-1 text-[8.8px] text-muted">
          {settled != null
            ? "Payable = if held to the due date · Due today = interest to today · Outstanding below is the approved settlement figure."
            : "Payable = if held to the due date · Due today = interest to today · Outstanding = due today − received."}
        </p>
        {settled != null && (
          <p className="mt-1 text-[10.4px] text-ink">
            <span className="font-bold">Settlement (approved):</span> {paiseToINR(settled)} full and final.
          </p>
        )}
        <FieldGrid cols={3} className="mt-3">
          <Field label="Days since disbursal">{ledger.daysSinceDisbursal != null ? `${ledger.daysSinceDisbursal} days` : null}</Field>
          <Field label="Days to due date" tone={ledger.isOverdue ? "error" : "ink"}>
            {ledger.daysToDue == null ? null : ledger.isOverdue ? `${ledger.daysOverdue} days overdue` : `${ledger.daysToDue} days`}
          </Field>
          <Field label="Grace day" caption="no penalty">{ledger.graceDay ? formatDate(ledger.graceDay) : null}</Field>
        </FieldGrid>
        {loan.status === "ACTIVE" && !ledger.isOverdue && settled == null && (
          <div className="mt-3 flex items-start gap-2 rounded bg-success-50 px-3 py-2 text-[10.4px] text-success-700">
            <Sparkles size={14} className="mt-0.5 shrink-0" />
            <p>
              <span className="font-bold">Pay early &amp; save</span> — Paying today costs {paiseToINR(total.outstandingPaise)};
              waiting until the due date costs {paiseToINR(Math.max(0, total.payablePaise - total.receivedPaise))} — interest
              stops on the day it is paid.
            </p>
          </div>
        )}
      </Section>

      <FieldGrid cols={7} className="border-y border-line py-2.5">
        <Field label="Loan number">#{loan.id}</Field>
        <Field label="Max eligible">{inr(app?.eligibleLimitPaise)}</Field>
        <Field label="Requested">{inr(app?.amountRequestedPaise)}</Field>
        <Field label="Sanctioned on">{app?.sanctionedAt ? formatDate(app.sanctionedAt) : null}</Field>
        <Field label="Disbursed on">{loan.disbursedOn ? formatDate(loan.disbursedOn) : null}</Field>
        <Field label="Repayment date" tone={ledger.isOverdue ? "error" : "ink"} caption="salary day">
          {due ? formatDate(due) : null}
        </Field>
        <Field label="Tenure">{due && disbursed ? `${daysBetween(disbursed, due)} days` : null}</Field>
      </FieldGrid>

      <Section title="Payments" pill={<Badge variant="info">{payments.length}</Badge>}>
        {actionError && (
          <p className="mb-2 text-xs text-error-700">{formatApiError(actionError, "Could not update the payment.")}</p>
        )}
        {payments.length === 0 ? (
          <EmptyState title="No payments recorded on this loan." className="py-4" />
        ) : (
          <div className="staff-table-scroll">
            <table className="staff-data-table staff-table-fit">
              <thead>
                <tr>
                  <th className={TH}>S.No</th>
                  <th className={`num ${TH}`}>Amount</th>
                  <th className={TH}>Mode</th>
                  <th className={TH}>Txn id</th>
                  <th className={TH}>Proof</th>
                  <th className={TH}>Paid on</th>
                  <th className={TH}>Status</th>
                  <th className={TH}><span className="sr-only">Actions</span></th>
                </tr>
              </thead>
              <tbody>
                {payments.map((p, i) => {
                  const busy =
                    (verify.isPending && verify.variables?.id === p.id) ||
                    (reject.isPending && reject.variables?.p.id === p.id);
                  return (
                    <tr key={p.id}>
                      <td className="text-muted">{i + 1}</td>
                      <td className="num font-bold text-ink">{paiseToINR(p.amountPaise)}</td>
                      <td><Badge variant="neutral">{METHOD_LABEL[p.method] ?? p.method}</Badge></td>
                      <td className="font-mono text-xs">{p.txnRef || "—"}</td>
                      <td>
                        <PaymentProofLink url={p.proofUrl} className="text-xs" />
                        {!p.proofUrl && <span className="text-xs text-muted">—</span>}
                      </td>
                      <td className="text-muted">{p.paidOn ? formatDate(p.paidOn) : "—"}</td>
                      <td>
                        <StatusBadge kind="payment" value={p.status} />
                        {p.partial && <Badge variant="neutral" className="ml-1">Partial</Badge>}
                        {p.status === "REJECTED" && (
                          <p className="mt-0.5 text-[8.8px] text-error-700">
                            {p.rejectionReason ? REJECTION_REASON_LABEL[p.rejectionReason] : "Rejected"}
                            {p.rejectionNote ? ` — ${p.rejectionNote}` : ""}
                          </p>
                        )}
                      </td>
                      <td>
                        {p.status === "PENDING_VERIFICATION" && canVerify && (
                          <div className="flex gap-1.5">
                            <button type="button" className="btn btn-sm btn-gold" disabled={busy} onClick={() => verify.mutate(p)}>
                              {busy && verify.isPending ? <Loader2 size={13} className="animate-spin" /> : <Check size={13} />} Verify
                            </button>
                            <button
                              type="button"
                              className="btn btn-sm border-error-600 bg-error-600 text-white hover:bg-error-700 disabled:opacity-50"
                              disabled={busy}
                              onClick={() => setRejectTarget(p)}
                            >
                              <X size={13} /> Reject
                            </button>
                          </div>
                        )}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
      </Section>

      {!closed && canRecord && (
        <Section
          title="Add new payment"
          icon={Plus}
          action={
            <button
              type="button"
              className="btn btn-sm btn-outline btn-icon"
              aria-expanded={addOpen}
              aria-label="Toggle add payment"
              onClick={() => setAddOpen((o) => !o)}
            >
              {addOpen ? <ChevronUp size={14} /> : <ChevronDown size={14} />}
            </button>
          }
        >
          {addOpen && (
            <div className="space-y-2">
              <AdminLogPaymentButton loanId={loan.id} loanStatus={loan.status} />
              <p className="text-[8.8px] text-muted">
                Recorded payments still need Accountant verification before they reduce the outstanding. (A payment
                logged here by an admin is verified in the same step.)
              </p>
            </div>
          )}
        </Section>
      )}

      <RejectDialog
        payment={rejectTarget}
        pending={reject.isPending}
        onClose={() => setRejectTarget(null)}
        onSubmit={(reason, note) => rejectTarget && reject.mutate({ p: rejectTarget, reason, note })}
      />
    </>
  );
}

function LedgerTr({
  row,
  overdue,
  closed,
  settled,
}: {
  row: LedgerRow;
  overdue: boolean;
  closed: boolean;
  settled: boolean;
}) {
  const total = row.key === "total";
  const tone =
    row.key === "interest" ? "text-warning-800" : row.key === "penalty" && row.dueTodayPaise > 0 ? "text-error-700" : "text-ink";
  const base = total ? "text-success-700" : tone;
  const dueTone = overdue ? "text-error-700" : base;
  const days = (n?: number) => (n != null && n > 0 ? <span className="text-[8.8px] text-muted"> ({n} {n === 1 ? "day" : "days"})</span> : null);
  const outTone = total ? (closed ? "font-bold text-success-700" : "font-bold text-navy") : closed ? "text-success-700" : tone;
  return (
    <tr className={total ? "bg-success-50 font-bold" : undefined}>
      <td className={base}>{row.label}</td>
      <td className={`num ${base}`}>
        {paiseToINR(row.payablePaise)}
        {row.key === "interest" && days(row.days)}
      </td>
      <td className={`num ${dueTone}`}>
        {paiseToINR(row.dueTodayPaise)}
        {(row.key === "interest" || row.key === "penalty") && days(row.dueDays)}
      </td>
      <td className="num text-success-700">{paiseToINR(row.receivedPaise)}</td>
      <td className={`num ${outTone}`}>{settled && !total ? "—" : paiseToINR(row.outstandingPaise)}</td>
    </tr>
  );
}
