"use client";

import * as React from "react";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { Check, CheckCircle2, AlertTriangle, Clock, Loader2, UserRound, X } from "lucide-react";
import { Dialog } from "@/components/ui/dialog";
import { EmptyState, Select, Skeleton } from "@/components/ui";
import {
  staffApi,
  paiseToINR,
  REJECTION_REASON_LABEL,
  type PaymentMethodName,
  type PaymentView,
  type RejectionReasonCode,
} from "@/lib/api/applications";
import { formatApiError } from "@/lib/api/errors";
import { formatDate } from "@/lib/utils";
import { CustomerDetailDialog } from "@/components/staff/customer-detail-dialog";
import { PostVerifyBlockDialog, type PostVerifyTarget } from "@/components/staff/post-verify-block-dialog";
import { PaymentProofLink } from "@/components/ui/payment-proof-link";
import { PaginationBar } from "@/components/staff/pipeline/pagination";
import { type FilterColumn } from "@/components/staff/column-filter";
import { istDay } from "@/lib/staff/column-filter-match";
import { useRegisterQuery } from "@/lib/staff/register-query";
import { useClientRegister } from "@/lib/staff/use-client-register";
import { Th, thBag } from "@/lib/staff/register-th";
import { useStaffMe } from "@/components/staff/pipeline/hooks";

const REASON_OPTIONS = (Object.keys(REJECTION_REASON_LABEL) as RejectionReasonCode[]).map((v) => ({
  value: v,
  label: REJECTION_REASON_LABEL[v],
}));

/**
 * How a payment method reads.
 *
 * This used to be `p.method === "UPI" ? "UPI" : "Bank transfer"`, which renders BANK_TRANSFER and
 * NACH identically — two genuinely different things to an accountant matching proof: one is a
 * transfer the borrower made and can show a screenshot of, the other is a mandate the system
 * pulled. A checklist keyed on the display value would also have merged them into one tick.
 */
const METHOD_LABEL: Record<PaymentMethodName, string> = {
  UPI: "UPI",
  BANK_TRANSFER: "Bank transfer",
  NACH: "NACH auto-debit",
};

/** Sortable + filterable columns. Customer, loan id, reference and proof are identity or links. */
const SORT_KEYS = ["amountPaise", "method", "paidOn"] as const;

const COLUMN_LABEL: Record<string, string> = {
  amountPaise: "Amount",
  method: "Method",
  paidOn: "Paid on",
};

/**
 * Accountant maker-checker queue for repayments. Borrowers record manual UPI/bank
 * repayments (PENDING_VERIFICATION); verifying one here confirms the transfer
 * landed — it reduces the borrower's outstanding and closes the loan/application
 * at zero. Mirrors the "Accountant confirms manually" product rule.
 */
export function RepaymentVerifyQueue() {
  const [openCustomerId, setOpenCustomerId] = React.useState<number | null>(null);
  const [rejectTarget, setRejectTarget] = React.useState<PaymentView | null>(null);
  // Set by a successful verify: the "should they borrow again?" prompt (PostVerifyBlockDialog).
  const [postVerify, setPostVerify] = React.useState<PostVerifyTarget | null>(null);
  const qc = useQueryClient();
  const q = useQuery({
    queryKey: ["staff-pending-repayments"],
    queryFn: () => staffApi.pendingRepayments(),
    refetchInterval: 8000,
  });
  // Verifying or rejecting a payment moves money on two other surfaces, so invalidate them here
  // rather than letting them notice on their next poll: `["staff-queue"]` as a PREFIX (it covers
  // ACTIVE/OVERDUE/CLOSED with their range + query suffixes) feeds the applications console's
  // "Awaiting repayment" panel, which a verification can empty by closing the loan, and
  // `["staff-transactions"]` is the accountant's ledger, where the payment becomes a row.
  const invalidateAfterDecision = () => {
    qc.invalidateQueries({ queryKey: ["staff-pending-repayments"] });
    qc.invalidateQueries({ queryKey: ["staff-queue"] });
    qc.invalidateQueries({ queryKey: ["staff-transactions"] });
  };
  const verify = useMutation({
    mutationFn: (p: PaymentView) => staffApi.verifyRepayment(p.loanId, p.id),
    onSuccess: (_result, p) => {
      invalidateAfterDecision();
      if (p.customerId != null) setPostVerify({ customerId: p.customerId, customerName: p.customerName });
    },
  });
  const reject = useMutation({
    mutationFn: (vars: { p: PaymentView; reason: RejectionReasonCode; note: string }) =>
      staffApi.rejectRepayment(vars.p.loanId, vars.p.id, {
        reason: vars.reason,
        note: vars.note.trim() || undefined,
      }),
    onSuccess: () => {
      invalidateAfterDecision();
      setRejectTarget(null);
    },
  });
  const actionError = verify.error ?? reject.error;
  const busy = (p: PaymentView) =>
    (verify.isPending && verify.variables?.id === p.id) ||
    (reject.isPending && reject.variables?.p.id === p.id);

  const rows = React.useMemo(() => q.data ?? [], [q.data]);

  // `url: false` — this panel is one of six registers on `/staff/applications`, so it keeps its
  // remembered view and leaves the address bar to the page.
  const rq = useRegisterQuery({
    registerId: "repayment-verify",
    staffId: useStaffMe().data?.id,
    sortKeys: SORT_KEYS,
    filterKeys: SORT_KEYS,
    defaults: { sort: { key: "paidOn", dir: "desc" }, size: 10 },
    url: false,
  });

  const filterColumns = React.useMemo<Array<FilterColumn<PaymentView>>>(
    () => [
      // Rupees: the bound is typed beside a rupee column.
      { key: "amountPaise", kind: "num", value: (p) => paiseToINR(p.amountPaise), num: (p) => p.amountPaise / 100 },
      { key: "method", value: (p) => METHOD_LABEL[p.method] ?? p.method, raw: (p) => p.method },
      { key: "paidOn", kind: "date", value: (p) => (p.paidOn ? formatDate(p.paidOn) : "—"), date: (p) => istDay(p.paidOn) },
    ],
    [],
  );

  const cr = useClientRegister(rows, filterColumns, rq);
  const { pageRows, total, pageCount } = cr;
  const th = thBag(rq, cr, filterColumns, COLUMN_LABEL);
  const page = rq.state.page;
  const pageSize = rq.state.size;

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
        <Skeleton variant="table" rows={5} cols={9} />
      ) : rows.length === 0 ? (
        <EmptyState
          title="No repayments awaiting verification."
          icon={<CheckCircle2 size={16} className="text-success-600" />}
          className="py-4"
        />
      ) : (
        <div className="staff-table-scroll">
          <table className="staff-data-table">
            <caption className="sr-only">Repayments awaiting verification</caption>
            <thead>
              <tr className="border-b border-line text-left text-xs uppercase tracking-wide text-muted">
                <th scope="col" className="py-2 pr-3 font-semibold">S.No.</th>
                <th scope="col" className="py-2 pr-3 font-semibold">Customer</th>
                <th scope="col" className="py-2 pr-3 font-semibold">Loan</th>
                <Th th={th} col="amountPaise" className="num py-2 pr-3 font-semibold" />
                <Th th={th} col="method" className="py-2 pr-3 font-semibold" />
                <th scope="col" className="py-2 pr-3 font-semibold">Reference</th>
                <th scope="col" className="py-2 pr-3 font-semibold">Proof</th>
                <Th th={th} col="paidOn" className="py-2 pr-3 font-semibold" />
                <th scope="col" className="py-2 font-semibold">
                  <span className="sr-only">Actions</span>
                </th>
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
                  <td className="num py-2.5 pr-3">
                    {paiseToINR(p.amountPaise)}
                    {p.partial && (
                      <span className="ml-1 rounded-full bg-gold-50 px-1.5 py-0.5 text-xs text-gold-dark">partial</span>
                    )}
                  </td>
                  <td className="py-2.5 pr-3">{METHOD_LABEL[p.method] ?? p.method}</td>
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
                      {/* Icon buttons, like the application queues' row actions (AppRow): the word
                          lives in aria-label + title, and the label names the row's loan so a
                          screen reader hears which payment it acts on, not "Verify" ×N. */}
                      <button
                        type="button"
                        onClick={() => verify.mutate(p)}
                        disabled={busy(p)}
                        className="btn btn-gold btn-sm btn-icon"
                        aria-label={
                          verify.isPending && verify.variables?.id === p.id
                            ? `Verifying payment on loan #${p.loanId}`
                            : `Verify payment of ${paiseToINR(p.amountPaise)} on loan #${p.loanId}`
                        }
                        title="Verify payment"
                      >
                        {verify.isPending && verify.variables?.id === p.id ? (
                          <Loader2 size={14} className="animate-spin" />
                        ) : (
                          <Check size={14} />
                        )}
                      </button>
                      <button
                        type="button"
                        onClick={() => setRejectTarget(p)}
                        disabled={busy(p)}
                        className="btn btn-sm btn-icon border-error-600 bg-error-600 text-white hover:bg-error-700 disabled:opacity-50"
                        aria-label={
                          reject.isPending && reject.variables?.p.id === p.id
                            ? `Rejecting payment on loan #${p.loanId}`
                            : `Reject payment of ${paiseToINR(p.amountPaise)} on loan #${p.loanId}`
                        }
                        title="Reject payment"
                      >
                        {reject.isPending && reject.variables?.p.id === p.id ? (
                          <Loader2 size={14} className="animate-spin" />
                        ) : (
                          <X size={14} />
                        )}
                      </button>
                    </div>
                  </td>
                </tr>
              ))}
              {total === 0 ? (
                // In-table, so the headers stay and the filter that emptied the queue can be cleared.
                <EmptyState inTable={9} title="No repayments match the column filters." />
              ) : null}
            </tbody>
          </table>
          <PaginationBar
            page={page}
            pageCount={pageCount}
            setPage={rq.setPage}
            total={total}
            pageSize={pageSize}
            setPageSize={rq.setSize}
          />
        </div>
      )}

      <p className="mt-3 flex items-center gap-1.5 text-xs text-muted">
        <Clock size={13} /> Verifying confirms the transfer landed; it reduces the borrower&apos;s balance and
        closes the loan at zero. Reject if the proof doesn&apos;t match — the balance is left unchanged.
      </p>
      <CustomerDetailDialog customerId={openCustomerId} onClose={() => setOpenCustomerId(null)} />
      <PostVerifyBlockDialog target={postVerify} onClose={() => setPostVerify(null)} onBlocked={invalidateAfterDecision} />
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
export function RejectDialog({
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
