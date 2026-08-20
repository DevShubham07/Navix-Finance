"use client";

import * as React from "react";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { CheckCircle2, AlertTriangle, Clock, UserRound, X } from "lucide-react";
import { Dialog } from "@/components/ui/dialog";
import { Select } from "@/components/ui";
import {
  staffApi,
  paiseToINR,
  REJECTION_REASON_LABEL,
  type PaymentView,
  type RejectionReasonCode,
} from "@/lib/api/applications";
import { formatApiError } from "@/lib/api/errors";
import { formatDate } from "@/lib/utils";
import { CustomerDetailDialog } from "@/components/staff/customer-detail-dialog";
import { PaymentProofLink } from "@/components/ui/payment-proof-link";
import { usePagination, PaginationBar } from "@/components/staff/pipeline/pagination";

const REASON_OPTIONS = (Object.keys(REJECTION_REASON_LABEL) as RejectionReasonCode[]).map((v) => ({
  value: v,
  label: REJECTION_REASON_LABEL[v],
}));

/**
 * Accountant maker-checker queue for repayments. Borrowers record manual UPI/bank
 * repayments (PENDING_VERIFICATION); verifying one here confirms the transfer
 * landed — it reduces the borrower's outstanding and closes the loan/application
 * at zero. Mirrors the "Accountant confirms manually" product rule.
 */
export function RepaymentVerifyQueue() {
  const [openCustomerId, setOpenCustomerId] = React.useState<number | null>(null);
  const [rejectTarget, setRejectTarget] = React.useState<PaymentView | null>(null);
  const qc = useQueryClient();
  const q = useQuery({
    queryKey: ["staff-pending-repayments"],
    queryFn: () => staffApi.pendingRepayments(),
    refetchInterval: 8000,
  });
  const verify = useMutation({
    mutationFn: (p: PaymentView) => staffApi.verifyRepayment(p.loanId, p.id),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["staff-pending-repayments"] }),
  });
  const reject = useMutation({
    mutationFn: (vars: { p: PaymentView; reason: RejectionReasonCode; note: string }) =>
      staffApi.rejectRepayment(vars.p.loanId, vars.p.id, {
        reason: vars.reason,
        note: vars.note.trim() || undefined,
      }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["staff-pending-repayments"] });
      setRejectTarget(null);
    },
  });
  const actionError = verify.error ?? reject.error;
  const busy = (p: PaymentView) =>
    (verify.isPending && verify.variables?.id === p.id) ||
    (reject.isPending && reject.variables?.p.id === p.id);

  const rows = q.data ?? [];
  const { pageRows, page, setPage, pageSize, setPageSize, pageCount, total } = usePagination(rows);

  return (
    <section className="rounded border border-line bg-white p-5 shadow-sm">
      <div className="mb-3 flex items-center justify-between">
        <h3 className="font-serif text-base text-navy">Repayments to verify</h3>
        <span className="text-xs text-muted">{rows.length} pending</span>
      </div>

      {actionError && (
        <div className="mb-3 flex items-start gap-2 rounded border border-error-100 bg-error-50 p-3 text-sm text-error-700">
          <AlertTriangle size={15} className="mt-0.5 flex-shrink-0" />
          {formatApiError(actionError, "Could not update the payment.")}
        </div>
      )}

      {q.isLoading ? (
        <div className="h-20 animate-pulse rounded bg-grey-100" />
      ) : rows.length === 0 ? (
        <p className="flex items-center gap-2 py-4 text-sm text-muted">
          <CheckCircle2 size={16} className="text-success-600" /> No repayments awaiting verification.
        </p>
      ) : (
        <div className="staff-table-scroll">
          <table className="staff-data-table">
            <thead>
              <tr className="border-b border-line text-left text-xs uppercase tracking-wide text-muted">
                <th className="py-2 pr-3 font-semibold">S.No.</th>
                <th className="py-2 pr-3 font-semibold">Customer</th>
                <th className="py-2 pr-3 font-semibold">Loan</th>
                <th className="py-2 pr-3 font-semibold">Amount</th>
                <th className="py-2 pr-3 font-semibold">Method</th>
                <th className="py-2 pr-3 font-semibold">Reference</th>
                <th className="py-2 pr-3 font-semibold">Proof</th>
                <th className="py-2 pr-3 font-semibold">Paid on</th>
                <th className="py-2 font-semibold" />
              </tr>
            </thead>
            <tbody>
              {pageRows.map((p, i) => (
                <tr key={p.id} className="border-b border-line/60">
                  <td className="py-2.5 pr-3 text-muted">{(page - 1) * pageSize + i + 1}</td>
                  <td className="py-2.5 pr-3">
                    {p.customerId != null ? (
                      <button type="button" onClick={() => setOpenCustomerId(p.customerId ?? null)} className="text-left hover:underline" title="Open full customer details">
                        <span className="block font-semibold text-ink">{p.customerName || `Customer #${p.customerId}`}</span>
                        <span className="block text-xs text-muted">Customer #{p.customerId}</span>
                      </button>
                    ) : <span className="text-muted">Customer details unavailable</span>}
                  </td>
                  <td className="py-2.5 pr-3 font-semibold text-ink">#{p.loanId}</td>
                  <td className="py-2.5 pr-3">
                    {paiseToINR(p.amountPaise)}
                    {p.partial && (
                      <span className="ml-1 rounded-full bg-gold-50 px-1.5 py-0.5 text-xs text-gold-dark">partial</span>
                    )}
                  </td>
                  <td className="py-2.5 pr-3">{p.method === "UPI" ? "UPI" : "Bank transfer"}</td>
                  <td className="py-2.5 pr-3 text-muted">{p.txnRef || "—"}</td>
                  <td className="py-2.5 pr-3">
                    <PaymentProofLink url={p.proofUrl} className="text-xs" />
                    {!p.proofUrl && <span className="text-xs text-muted">—</span>}
                  </td>
                  <td className="py-2.5 pr-3 text-muted">{p.paidOn ? formatDate(p.paidOn) : "—"}</td>
                  <td className="py-2.5 text-right">
                    <div className="flex items-center justify-end gap-2">
                      {p.customerId != null && (
                        <button type="button" onClick={() => setOpenCustomerId(p.customerId ?? null)} className="btn btn-sm btn-outline" title="Open full customer details">
                          <UserRound size={15} /> Customer
                        </button>
                      )}
                      <button
                        type="button"
                        onClick={() => verify.mutate(p)}
                        disabled={busy(p)}
                        className="btn btn-gold btn-sm"
                      >
                        <CheckCircle2 size={15} />{" "}
                        {verify.isPending && verify.variables?.id === p.id ? "Verifying…" : "Verify"}
                      </button>
                      <button
                        type="button"
                        onClick={() => setRejectTarget(p)}
                        disabled={busy(p)}
                        className="btn btn-sm border-error-600 bg-error-600 text-white hover:bg-error-700 disabled:opacity-50"
                      >
                        <X size={15} />{" "}
                        {reject.isPending && reject.variables?.p.id === p.id ? "Rejecting…" : "Reject"}
                      </button>
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          <PaginationBar
            page={page}
            pageCount={pageCount}
            setPage={setPage}
            total={total}
            pageSize={pageSize}
            setPageSize={setPageSize}
          />
        </div>
      )}

      <p className="mt-3 flex items-center gap-1.5 text-xs text-muted">
        <Clock size={13} /> Verifying confirms the transfer landed; it reduces the borrower&apos;s balance and
        closes the loan at zero. Reject if the proof doesn&apos;t match — the balance is left unchanged.
      </p>
      <CustomerDetailDialog customerId={openCustomerId} onClose={() => setOpenCustomerId(null)} />
      <RejectDialog
        payment={rejectTarget}
        pending={reject.isPending}
        onClose={() => setRejectTarget(null)}
        onSubmit={(reason, note) => rejectTarget && reject.mutate({ p: rejectTarget, reason, note })}
      />
    </section>
  );
}

/** The reason picklist + optional note the accountant fills in before a reject goes through. */
function RejectDialog({
  payment,
  pending,
  onClose,
  onSubmit,
}: {
  payment: PaymentView | null;
  pending: boolean;
  onClose: () => void;
  onSubmit: (reason: RejectionReasonCode, note: string) => void;
}) {
  const [reason, setReason] = React.useState<RejectionReasonCode | "">("");
  const [note, setNote] = React.useState("");

  React.useEffect(() => {
    if (payment) {
      setReason("");
      setNote("");
    }
  }, [payment]);

  return (
    <Dialog open={payment != null} onClose={onClose} aria-label="Reject payment">
      <h3 className="mb-1 font-serif text-lg text-navy">Reject this payment?</h3>
      <p className="mb-4 text-sm text-muted">
        {payment
          ? `${paiseToINR(payment.amountPaise)} on loan #${payment.loanId}${payment.customerName ? ` · ${payment.customerName}` : ""}. The borrower's balance stays unchanged.`
          : null}
      </p>
      <Select
        label="Reason"
        required
        value={reason}
        onChange={(e) => setReason(e.target.value as RejectionReasonCode | "")}
        options={[{ value: "", label: "Select a reason…" }, ...REASON_OPTIONS]}
      />
      <label className="field">
        <span>Note (optional)</span>
        <textarea
          value={note}
          onChange={(e) => setNote(e.target.value)}
          rows={3}
          placeholder="Any extra detail for the borrower/record…"
          className="w-full rounded border border-line px-3 py-2 text-sm"
        />
      </label>
      <div className="mt-4 flex justify-end gap-2">
        <button type="button" onClick={onClose} className="btn btn-sm btn-outline" disabled={pending}>
          Cancel
        </button>
        <button
          type="button"
          onClick={() => reason && onSubmit(reason, note)}
          disabled={!reason || pending}
          className="btn btn-sm border-error-600 bg-error-600 text-white hover:bg-error-700 disabled:opacity-50"
        >
          {pending ? "Rejecting…" : "Reject payment"}
        </button>
      </div>
    </Dialog>
  );
}
