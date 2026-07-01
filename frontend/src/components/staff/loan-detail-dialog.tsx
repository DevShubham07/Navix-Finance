"use client";

import * as React from "react";
import { useQuery } from "@tanstack/react-query";
import { Loader2, X } from "lucide-react";
import { Dialog, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import {
  staffApi,
  paiseToINR,
  type LoanView,
  type PaymentView,
  type EventView,
} from "@/lib/api/applications";
import { formatDate } from "@/lib/utils";

const todayISO = () => new Date().toISOString().slice(0, 10);

/**
 * A single loan's full detail, in a popup: the cost breakdown (principal → interest/penalty →
 * outstanding), the repayment ledger ("how they paid", with verify status + UTRs), and the
 * maker-checker status timeline. Reused on the /staff/applications console (LoanHistory) and the
 * Customers detail page. All data + endpoints already exist — this is read-only.
 */
export function LoanDetailDialog({
  loan,
  applicationId,
  onClose,
}: {
  loan: LoanView;
  applicationId: number | null;
  onClose: () => void;
}) {
  const outQuery = useQuery({
    queryKey: ["loan-detail-out", loan.id],
    queryFn: () => staffApi.outstanding(loan.id, todayISO()),
  });
  const payQuery = useQuery({
    queryKey: ["loan-detail-pay", loan.id],
    queryFn: () => staffApi.repayments(loan.id),
  });
  const evQuery = useQuery({
    queryKey: ["loan-detail-events", applicationId],
    queryFn: () => staffApi.events(applicationId as number),
    enabled: applicationId != null,
  });

  const out = outQuery.data;
  const payments = payQuery.data ?? [];
  const events = evQuery.data ?? [];

  return (
    <Dialog open onClose={onClose} className="max-w-2xl">
      <DialogHeader>
        <div className="flex items-center justify-between">
          <DialogTitle>Loan #{loan.id}</DialogTitle>
          <button onClick={onClose} aria-label="Close" className="text-muted hover:text-ink">
            <X size={18} />
          </button>
        </div>
        <div className="text-sm text-muted">
          {loan.status} · disbursed {loan.disbursedOn ? formatDate(loan.disbursedOn) : "—"} · due{" "}
          {loan.dueDate ? formatDate(loan.dueDate) : "—"}
        </div>
      </DialogHeader>

      <div className="max-h-[70vh] space-y-6 overflow-y-auto pr-1 text-sm">
        <section>
          <h4 className="mb-2 font-semibold text-ink">Cost breakdown</h4>
          <dl className="grid grid-cols-2 gap-x-6 gap-y-1">
            <Row label="Principal" value={paiseToINR(loan.principalPaise)} />
            <Row label="Processing fee" value={paiseToINR(loan.processingFeePaise)} />
            <Row label="GST" value={paiseToINR(loan.gstPaise)} />
            <Row label="Net disbursed" value={paiseToINR(loan.netDisbursedPaise)} />
            <Row label="Total repayable" value={paiseToINR(loan.totalRepayablePaise)} />
            {out && <Row label="Interest accrued" value={paiseToINR(out.interestPaise ?? 0)} />}
            {out && (out.penaltyPaise ?? 0) > 0 && (
              <Row label="Late penalty" value={paiseToINR(out.penaltyPaise ?? 0)} />
            )}
            {out && <Row label="Paid (verified)" value={paiseToINR(out.verifiedPaise ?? 0)} />}
            <Row
              label="Outstanding"
              value={paiseToINR(out ? out.outstandingPaise : loan.outstandingPaise)}
              strong
            />
            {out?.settledAmountPaise != null && (
              <Row label="Settlement (full & final)" value={paiseToINR(out.settledAmountPaise)} />
            )}
          </dl>
        </section>

        <section>
          <h4 className="mb-2 flex items-center gap-2 font-semibold text-ink">
            How they paid
            {payQuery.isLoading && <Loader2 size={12} className="animate-spin text-muted" />}
          </h4>
          {payments.length === 0 ? (
            <p className="text-muted">No repayments recorded yet.</p>
          ) : (
            <ul className="divide-y divide-line rounded border border-line">
              {payments.map((p) => (
                <PaymentLi key={p.id} p={p} />
              ))}
            </ul>
          )}
        </section>

        {applicationId != null && (
          <section>
            <h4 className="mb-2 flex items-center gap-2 font-semibold text-ink">
              Status timeline
              {evQuery.isLoading && <Loader2 size={12} className="animate-spin text-muted" />}
            </h4>
            {events.length === 0 ? (
              <p className="text-muted">No events recorded.</p>
            ) : (
              <ol className="space-y-2">
                {events.map((e) => (
                  <EventLi key={e.id} e={e} />
                ))}
              </ol>
            )}
          </section>
        )}
      </div>
    </Dialog>
  );
}

function Row({ label, value, strong }: { label: string; value: string; strong?: boolean }) {
  return (
    <div className="flex items-center justify-between">
      <dt className="text-muted">{label}</dt>
      <dd className={strong ? "font-semibold text-navy" : "text-ink"}>{value}</dd>
    </div>
  );
}

function PaymentLi({ p }: { p: PaymentView }) {
  const tone =
    p.status === "VERIFIED"
      ? "bg-success-50 text-success-700"
      : p.status === "REJECTED"
        ? "bg-error-50 text-error-700"
        : "bg-gold-50 text-gold-dark";
  return (
    <li className="flex items-center justify-between gap-2 px-3 py-2">
      <span>
        <span className="font-semibold text-ink">{paiseToINR(p.amountPaise)}</span>{" "}
        <span className="text-xs text-muted">
          {p.method === "BANK_TRANSFER" ? "Bank" : p.method}
          {p.txnRef ? ` · ${p.txnRef}` : ""}
          {p.paidOn ? ` · ${formatDate(p.paidOn)}` : ""}
          {p.partial ? " · partial" : ""}
        </span>
      </span>
      <span className={`rounded-full px-2 py-0.5 text-xs font-semibold capitalize ${tone}`}>
        {p.status.replace(/_/g, " ").toLowerCase()}
      </span>
    </li>
  );
}

function EventLi({ e }: { e: EventView }) {
  return (
    <li className="border-l-2 border-line pl-3">
      <div className="text-ink">
        <strong>{e.action ?? e.toStatus ?? "—"}</strong>
        {e.toStatus ? <span className="text-muted"> → {e.toStatus}</span> : null}
      </div>
      <div className="text-xs text-muted">
        {e.actorRole ?? "system"} · {formatDate(e.at)}
        {e.notes ? ` · ${e.notes}` : ""}
      </div>
    </li>
  );
}
