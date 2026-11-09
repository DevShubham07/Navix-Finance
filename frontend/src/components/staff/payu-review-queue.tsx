"use client";

import * as React from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { AlertTriangle, CheckCircle2, Info, Loader2 } from "lucide-react";
import { Dialog } from "@/components/ui/dialog";
import { EmptyState, ErrorState, Skeleton } from "@/components/ui";
import { staffApi, paiseToINR, type PayuReviewReason, type PayuReviewView } from "@/lib/api/applications";
import { formatApiError } from "@/lib/api/errors";
import { formatDate } from "@/lib/utils";

/** What each review reason means for Accounts, in plain language. */
const REASON_LABEL: Record<PayuReviewReason, string> = {
  LOAN_SETTLED: "Paid after the loan closed: refund needed",
  AMOUNT_MISMATCH: "Amount differs from what we asked for: not credited",
  NO_PROVIDER_ID: "PayU sent no payment reference: not credited",
  OVERPAID: "Paid more than was owed: credited, check for refund",
  FEE_MISMATCH: "Card fee differs from what we quoted: credited",
};

const KEY = ["staff-payu-review"];

/**
 * Accountant/Admin queue of PayU orders that need a person: money captured but not credited, a refund due,
 * or a figure that looks wrong. "Mark resolved" only records that someone dealt with it (with a note); it does
 * not credit or refund. Refunds are made in the PayU dashboard.
 */
export function PayuReviewQueue() {
  const qc = useQueryClient();
  const [target, setTarget] = React.useState<PayuReviewView | null>(null);
  const q = useQuery({ queryKey: KEY, queryFn: () => staffApi.payuReviewQueue(), refetchInterval: 15000 });
  const resolve = useMutation({
    mutationFn: (v: { o: PayuReviewView; note: string }) => staffApi.resolvePayuReview(v.o.orderId, v.note),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: KEY });
      setTarget(null);
    },
  });
  const rows = q.data ?? [];

  return (
    <section className="rounded border border-line bg-white p-5 shadow-sm">
      <div className="mb-3 flex items-center justify-between">
        <h3 className="font-serif text-base text-navy">PayU payments needing review</h3>
        <span className="text-xs text-muted">{rows.length} open</span>
      </div>
      <p className="mb-3 flex items-start gap-1.5 text-xs text-muted">
        <Info size={13} className="mt-0.5 flex-shrink-0" />
        Refunds are made in the PayU dashboard. Marking a payment resolved only records that you dealt with it; it
        does not credit the loan or refund the borrower.
      </p>

      {q.isLoading ? (
        <Skeleton variant="table" rows={3} cols={6} />
      ) : q.error ? (
        <ErrorState
          error={q.error}
          title="Couldn't load PayU payments needing review"
          onRetry={() => void q.refetch()}
          className="py-2"
        />
      ) : rows.length === 0 ? (
        <EmptyState
          title="No PayU payments need review."
          icon={<CheckCircle2 size={16} className="text-success-600" />}
          className="py-4"
        />
      ) : (
        <div className="staff-table-scroll">
          <table className="staff-data-table">
            <caption className="sr-only">PayU payments needing review</caption>
            <thead>
              <tr className="border-b border-line text-left text-xs uppercase tracking-wide text-muted">
                <th scope="col" className="py-2 pr-3 font-semibold">Customer</th>
                <th scope="col" className="py-2 pr-3 font-semibold">Loan</th>
                <th scope="col" className="num py-2 pr-3 font-semibold">Amount</th>
                <th scope="col" className="py-2 pr-3 font-semibold">PayU reference</th>
                <th scope="col" className="py-2 pr-3 font-semibold">Paid on</th>
                <th scope="col" className="py-2 pr-3 font-semibold">What happened</th>
                <th scope="col" className="py-2 font-semibold"><span className="sr-only">Actions</span></th>
              </tr>
            </thead>
            <tbody>
              {rows.map((o) => (
                <tr key={o.orderId} className="border-b border-line/60 align-top">
                  <td className="py-2.5 pr-3">
                    <span className="block font-semibold text-ink">{o.customerName || `Customer #${o.customerId}`}</span>
                    <span className="block text-xs text-muted">Customer #{o.customerId}</span>
                  </td>
                  <td className="py-2.5 pr-3 font-semibold text-ink">#{o.loanId}</td>
                  <td className="num py-2.5 pr-3">
                    {paiseToINR(o.amountPaise)}
                    {o.feePaise != null && o.feePaise > 0 && (
                      <span className="block text-xs text-muted">+ {paiseToINR(o.feePaise)} fee</span>
                    )}
                  </td>
                  <td className="py-2.5 pr-3 text-xs text-muted">
                    <span className="block">{o.mihpayid || "none"}</span>
                    <span className="block">{o.bankRefNum || o.txnid}</span>
                    {o.mode && <span className="block">{o.mode}</span>}
                  </td>
                  <td className="py-2.5 pr-3 text-muted">{o.paidOn ? formatDate(o.paidOn) : "—"}</td>
                  <td className="py-2.5 pr-3 text-sm text-ink">{REASON_LABEL[o.reviewReason] ?? o.reviewReason}</td>
                  <td className="py-2.5 text-right">
                    <button type="button" className="btn btn-sm btn-outline" onClick={() => setTarget(o)}>
                      Mark resolved
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      <ResolveDialog
        order={target}
        pending={resolve.isPending}
        error={resolve.error}
        onClose={() => {
          resolve.reset();
          setTarget(null);
        }}
        onSubmit={(note) => target && resolve.mutate({ o: target, note })}
      />
    </section>
  );
}

function ResolveDialog({
  order,
  pending,
  error,
  onClose,
  onSubmit,
}: {
  order: PayuReviewView | null;
  pending: boolean;
  error: unknown;
  onClose: () => void;
  onSubmit: (note: string) => void;
}) {
  const [note, setNote] = React.useState("");
  React.useEffect(() => {
    if (order) setNote("");
  }, [order]);

  return (
    <Dialog open={order != null} onClose={onClose} aria-label="Mark PayU payment resolved">
      <h3 className="mb-1 font-serif text-lg text-navy">Mark this payment resolved?</h3>
      <p className="mb-4 text-sm text-muted">
        {order ? `${paiseToINR(order.amountPaise)} on loan #${order.loanId}. ${REASON_LABEL[order.reviewReason]}.` : null}{" "}
        This only records your note; refunds are done in the PayU dashboard.
      </p>
      {error != null && (
        <div className="mb-3 flex items-start gap-2 rounded border border-error-100 bg-error-50 p-3 text-sm text-error-700">
          <AlertTriangle size={15} className="mt-0.5 flex-shrink-0" />
          {formatApiError(error, "Could not mark the payment resolved.")}
        </div>
      )}
      <label className="field">
        <span>What was done (required)</span>
        <textarea
          value={note}
          onChange={(e) => setNote(e.target.value)}
          rows={3}
          placeholder="e.g. Refunded in the PayU dashboard"
          className="w-full rounded border border-line px-3 py-2 text-sm"
        />
      </label>
      <div className="mt-4 flex justify-end gap-2">
        <button type="button" onClick={onClose} className="btn btn-sm btn-outline" disabled={pending}>
          Cancel
        </button>
        <button
          type="button"
          onClick={() => onSubmit(note.trim())}
          disabled={!note.trim() || pending}
          className="btn btn-sm btn-gold disabled:opacity-50"
        >
          {pending ? <Loader2 size={14} className="animate-spin" /> : "Mark resolved"}
        </button>
      </div>
    </Dialog>
  );
}
