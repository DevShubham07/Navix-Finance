"use client";

import * as React from "react";
import { useQuery } from "@tanstack/react-query";
import { Loader2, X } from "lucide-react";
import { Dialog, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { LoanBreakdown } from "@/components/staff/loan-breakdown";
import { EventTimeline } from "@/components/staff/event-timeline";
import {
  staffApi,
  paiseToINR,
  type LoanView,
  type PaymentView,
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

  // !max-w-2xl: globals.css's un-layered `.modal { max-width: 460px }` outranks
  // plain utilities in the cascade, so the width needs the important modifier.
  return (
    <Dialog open onClose={onClose} className="!max-w-2xl">
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
          <LoanBreakdown loan={loan} outstanding={out} />
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
              <EventTimeline events={events} />
            )}
          </section>
        )}
      </div>
    </Dialog>
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
