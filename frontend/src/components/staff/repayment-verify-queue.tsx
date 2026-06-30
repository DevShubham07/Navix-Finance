"use client";

import * as React from "react";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { CheckCircle2, AlertTriangle, Clock, X } from "lucide-react";
import { staffApi, paiseToINR, ApplicationApiError, type PaymentView } from "@/lib/api/applications";
import { formatDate } from "@/lib/utils";

/**
 * Accountant maker-checker queue for repayments. Borrowers record manual UPI/bank
 * repayments (PENDING_VERIFICATION); verifying one here confirms the transfer
 * landed — it reduces the borrower's outstanding and closes the loan/application
 * at zero. Mirrors the "Accountant confirms manually" product rule.
 */
export function RepaymentVerifyQueue() {
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
    mutationFn: (p: PaymentView) => staffApi.rejectRepayment(p.loanId, p.id),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["staff-pending-repayments"] }),
  });
  const actionError = verify.error ?? reject.error;
  const busy = (p: PaymentView) =>
    (verify.isPending && verify.variables?.id === p.id) ||
    (reject.isPending && reject.variables?.id === p.id);

  const rows = q.data ?? [];

  return (
    <section className="rounded border border-line bg-white p-5 shadow-sm">
      <div className="mb-3 flex items-center justify-between">
        <h3 className="font-serif text-base text-navy">Repayments to verify</h3>
        <span className="text-xs text-muted">{rows.length} pending</span>
      </div>

      {actionError && (
        <div className="mb-3 flex items-start gap-2 rounded border border-error-100 bg-error-50 p-3 text-sm text-error-700">
          <AlertTriangle size={15} className="mt-0.5 flex-shrink-0" />
          {actionError instanceof ApplicationApiError
            ? `${actionError.message} (${actionError.code})`
            : "Could not update the payment."}
        </div>
      )}

      {q.isLoading ? (
        <div className="h-20 animate-pulse rounded bg-grey-100" />
      ) : rows.length === 0 ? (
        <p className="flex items-center gap-2 py-4 text-sm text-muted">
          <CheckCircle2 size={16} className="text-success-600" /> No repayments awaiting verification.
        </p>
      ) : (
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-line text-left text-xs uppercase tracking-wide text-muted">
                <th className="py-2 pr-3 font-semibold">Loan</th>
                <th className="py-2 pr-3 font-semibold">Amount</th>
                <th className="py-2 pr-3 font-semibold">Method</th>
                <th className="py-2 pr-3 font-semibold">Reference</th>
                <th className="py-2 pr-3 font-semibold">Paid on</th>
                <th className="py-2 font-semibold" />
              </tr>
            </thead>
            <tbody>
              {rows.map((p) => (
                <tr key={p.id} className="border-b border-line/60">
                  <td className="py-2.5 pr-3 font-semibold text-ink">#{p.loanId}</td>
                  <td className="py-2.5 pr-3">
                    {paiseToINR(p.amountPaise)}
                    {p.partial && (
                      <span className="ml-1 rounded-full bg-gold-50 px-1.5 py-0.5 text-xs text-gold-dark">partial</span>
                    )}
                  </td>
                  <td className="py-2.5 pr-3">{p.method === "UPI" ? "UPI" : "Bank transfer"}</td>
                  <td className="py-2.5 pr-3 text-muted">{p.txnRef || "—"}</td>
                  <td className="py-2.5 pr-3 text-muted">{p.paidOn ? formatDate(p.paidOn) : "—"}</td>
                  <td className="py-2.5 text-right">
                    <div className="flex items-center justify-end gap-2">
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
                        onClick={() => reject.mutate(p)}
                        disabled={busy(p)}
                        className="btn btn-sm border-error-600 bg-error-600 text-white hover:bg-error-700 disabled:opacity-50"
                      >
                        <X size={15} />{" "}
                        {reject.isPending && reject.variables?.id === p.id ? "Rejecting…" : "Reject"}
                      </button>
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      <p className="mt-3 flex items-center gap-1.5 text-xs text-muted">
        <Clock size={13} /> Verifying confirms the transfer landed; it reduces the borrower&apos;s balance and
        closes the loan at zero. Reject if the proof doesn&apos;t match — the balance is left unchanged.
      </p>
    </section>
  );
}
