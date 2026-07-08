"use client";

/**
 * The customer's loan history (current + past), loaded on demand so a queue of N rows doesn't fan
 * out N borrower-history fetches. Open to every staff role (the customers roll-up is). Keyed on the
 * customer id so a staffer inspecting an application sees the borrower's amount/due-date context.
 * Moved verbatim from the former `live-pipeline.tsx` god-file — logic unchanged.
 */

import * as React from "react";
import Link from "next/link";
import { useQuery } from "@tanstack/react-query";
import { Loader2, Banknote, ArrowRight } from "lucide-react";
import { customersApi, paiseToINR, type LoanView } from "@/lib/api/applications";
import { formatDate } from "@/lib/utils";
import { LoanDetailDialog } from "@/components/staff/loan-detail-dialog";
import { errMessage, OPEN_LOAN_STATUSES } from "@/components/staff/pipeline/hooks";

export function LoanHistory({ customerId }: { customerId: number }) {
  const [load, setLoad] = React.useState(false);
  const q = useQuery({
    queryKey: ["customer-loans", customerId],
    queryFn: () => customersApi.get(customerId),
    enabled: load,
    retry: false,
  });
  const [selected, setSelected] = React.useState<{ loan: LoanView; applicationId: number | null } | null>(null);

  if (!load) {
    return (
      <button onClick={() => setLoad(true)} className="btn btn-sm btn-outline">
        <Banknote size={14} /> Show loan history
      </button>
    );
  }

  const c = q.data;
  const current = c?.loans.find((l) => OPEN_LOAN_STATUSES.has(l.status)) ?? null;
  const past = c ? c.loans.filter((l) => l !== current) : [];
  const appIdFor = (loanId: number) => c?.applications.find((a) => a.loanId === loanId)?.id ?? null;
  const open = (loan: LoanView) => setSelected({ loan, applicationId: appIdFor(loan.id) });

  return (
    <div className="w-full rounded border border-line bg-white p-5 shadow-sm">
      <div className="mb-3 flex items-center gap-2 font-serif text-base font-semibold text-navy">
        <Banknote size={16} /> Loan history
        {q.isFetching && <Loader2 size={14} className="animate-spin text-muted" />}
        <Link href={`/staff/customers/${customerId}`} className="ml-auto inline-flex items-center gap-1 text-xs font-normal text-navy hover:underline">
          Full profile <ArrowRight size={12} />
        </Link>
      </div>

      {q.isLoading ? (
        <p className="text-sm text-muted">Loading…</p>
      ) : q.error ? (
        <p className="text-sm text-error-700">{errMessage(q.error)}</p>
      ) : !c || c.loans.length === 0 ? (
        <p className="text-sm text-muted">No loans yet for this customer.</p>
      ) : (
        <div className="space-y-3 text-sm">
          {current && (
            <button
              type="button"
              onClick={() => open(current)}
              className="w-full rounded border border-navy/20 bg-navy-tint/40 p-3 text-left transition hover:border-navy hover:bg-navy-tint/70"
            >
              <div className="mb-1 text-xs font-semibold uppercase tracking-wide text-navy">Current loan · view details</div>
              <div className="flex flex-wrap items-center justify-between gap-2">
                <span className="text-ink">Loan #{current.id} · {paiseToINR(current.principalPaise)}</span>
                <span className="rounded-full bg-navy-tint px-2 py-0.5 text-xs font-semibold text-navy">{current.status}</span>
              </div>
              <div className="mt-0.5 text-xs text-muted">
                net {paiseToINR(current.netDisbursedPaise)} · disbursed {current.disbursedOn ? formatDate(current.disbursedOn) : "—"} · due {current.dueDate ? formatDate(current.dueDate) : "—"} · outstanding {paiseToINR(current.outstandingPaise)}
              </div>
            </button>
          )}
          <div>
            <div className="mb-1 text-xs font-semibold uppercase tracking-wide text-muted">Past loans ({past.length})</div>
            {past.length === 0 ? (
              <p className="text-xs text-muted">None.</p>
            ) : (
              <ul className="divide-y divide-line">
                {past.map((l: LoanView) => (
                  <li key={l.id}>
                    <button
                      type="button"
                      onClick={() => open(l)}
                      className="w-full rounded py-1.5 text-left transition hover:bg-grey-50"
                    >
                      <div className="flex flex-wrap items-center justify-between gap-2">
                        <span className="text-ink underline-offset-2 hover:underline">Loan #{l.id} · {paiseToINR(l.principalPaise)}</span>
                        <span className="rounded-full bg-grey-100 px-2 py-0.5 text-xs font-semibold text-muted">{l.status}</span>
                      </div>
                      <div className="mt-0.5 text-xs text-muted">
                        net {paiseToINR(l.netDisbursedPaise)} · disbursed {l.disbursedOn ? formatDate(l.disbursedOn) : "—"} · due {l.dueDate ? formatDate(l.dueDate) : "—"} · outstanding {paiseToINR(l.outstandingPaise)}
                      </div>
                    </button>
                  </li>
                ))}
              </ul>
            )}
          </div>
        </div>
      )}

      {selected && (
        <LoanDetailDialog
          loan={selected.loan}
          applicationId={selected.applicationId}
          onClose={() => setSelected(null)}
        />
      )}
    </div>
  );
}
