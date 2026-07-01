"use client";

import Link from "next/link";
import { useQuery } from "@tanstack/react-query";
import { Wallet, ArrowRight, AlertTriangle } from "lucide-react";
import { borrowerApi, paiseToINR, type ApplicationView } from "@/lib/api/applications";
import { formatDate } from "@/lib/utils";

/**
 * The "one advance at a time" notice — shown wherever a borrower who already holds a LIVE advance
 * tries to borrow/apply again (the reborrow + amount surfaces). Surfaces the current loan's id,
 * outstanding and due date so it's unambiguous which advance they need to clear, with a direct
 * Repay CTA.
 */
export function ActiveLoanNotice({ app }: { app: ApplicationView }) {
  const overdue = app.status === "OVERDUE" || app.status === "DEFAULTED";
  const loanQuery = useQuery({
    queryKey: ["active-loan-notice", app.loanId],
    queryFn: () => borrowerApi.loan(app.loanId as number),
    enabled: app.loanId != null,
  });
  const loan = loanQuery.data;

  return (
    <div className="container max-w-content py-10">
      <div className="rounded border border-line bg-white p-8 text-center shadow-sm">
        <span
          className={`mx-auto mb-3 grid h-11 w-11 place-items-center rounded-full ${
            overdue ? "bg-error-50 text-error-600" : "bg-navy-tint text-navy"
          }`}
        >
          {overdue ? <AlertTriangle size={20} /> : <Wallet size={20} />}
        </span>
        <h1 className="text-2xl">You already have an active advance</h1>
        <p className="mb-2 text-muted">
          {overdue
            ? "Your current advance is overdue — clear it to avoid further penalty and protect your credit score, then you can borrow again."
            : "You can hold one advance at a time. Repay your current advance before borrowing again."}
        </p>
        {loan && (
          <p className="mb-5 text-sm text-ink">
            Loan #{loan.id} · <strong>{paiseToINR(loan.outstandingPaise)}</strong> outstanding
            {loan.dueDate ? <> · due {formatDate(loan.dueDate)}</> : null}
          </p>
        )}
        <Link href="/repay" className="btn btn-gold">
          {overdue ? "Pay now" : "Repay now"} <ArrowRight size={16} />
        </Link>
      </div>
    </div>
  );
}
