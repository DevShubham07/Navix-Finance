"use client";

import * as React from "react";
import { paiseToINR, type MandateDebitRow } from "@/lib/api/applications";
import { formatDateTime } from "@/lib/utils";
import { DEBIT_STATUS, presentedBy } from "./labels";

/** Everything recorded about one debit attempt: who, when, what the bank said. Read-only. */
export function DebitDetail({ d }: { d: MandateDebitRow }) {
  const rows: [string, React.ReactNode][] = [
    ["Presented by", presentedBy(d)],
    ["How", d.trigger === "MANUAL" ? "Manual debit from the console" : "Scheduled sweep (DPD 1/3/5/9)"],
    ["Presented on", d.createdAt ? formatDateTime(d.createdAt) : "—"],
    ["Amount", <span key="a" className="font-mono">{paiseToINR(d.amountPaise)}</span>],
    ["Status", `${DEBIT_STATUS[d.status]?.label ?? d.status}${d.digioStatus ? ` · bank: ${d.digioStatus}` : ""}`],
    ["Settlement date", d.settlementDate ?? "—"],
    ["UTR", <span key="u" className="font-mono">{d.utr ?? "—"}</span>],
    ["DPD when presented", d.dpdAtAttempt ?? "—"],
    ["Attempt / times sent", `#${d.attemptNo} · sent ${d.presentCount}×`],
    ["Failure", d.failureCode || d.failureReason ? [d.failureCode, d.failureReason].filter(Boolean).join(" — ") : "—"],
    ["Loan", `#${d.loanId}`],
    ["UMRN", <span key="m" className="font-mono">{d.umrn ?? "—"}</span>],
    ["Digio transaction", <span key="t" className="font-mono">{d.digioTxnId ?? "—"}</span>],
    ["Last updated", d.updatedAt ? `${formatDateTime(d.updatedAt)}${d.updatedBy ? ` by ${d.updatedBy}` : ""}` : "—"],
  ];
  return (
    <div className="space-y-2 rounded-lg border border-line bg-grey-50 p-3">
      {d.overpayment && (
        <p className="text-xs font-semibold text-error-700">
          Refund owed — this debit collected money after the loan was already settled.
        </p>
      )}
      <dl className="grid grid-cols-1 gap-x-6 gap-y-1.5 text-xs sm:grid-cols-[10rem_1fr]">
        {rows.map(([k, v]) => (
          <React.Fragment key={k}>
            <dt className="text-muted">{k}</dt>
            <dd className="break-words font-medium text-navy">{v}</dd>
          </React.Fragment>
        ))}
      </dl>
    </div>
  );
}
