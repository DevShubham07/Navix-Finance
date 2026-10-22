"use client";

import * as React from "react";
import { ChevronDown, ChevronRight } from "lucide-react";
import { Badge } from "@/components/ui";
import { paiseToINR, type MandateDebitRow } from "@/lib/api/applications";
import { formatDateTime } from "@/lib/utils";
import { DEBIT_STATUS, presentedBy } from "./labels";
import { DebitDetail } from "./debit-detail";

/**
 * Debit attempts as rows; a row opens its full detail in place. {@code showCustomer} adds the customer
 * columns for the cross-book Debits tab (a mandate card already names its customer).
 */
export function DebitTable({
  rows,
  showCustomer = false,
  onOpenActions,
}: {
  rows: MandateDebitRow[];
  showCustomer?: boolean;
  /** Opens the customer's Mandate tab, where Check status / Cancel debit live. */
  onOpenActions?: (d: MandateDebitRow) => void;
}) {
  const [open, setOpen] = React.useState<number | null>(null);
  const cols = showCustomer ? 9 : 7;
  return (
    <div className="overflow-x-auto rounded-lg border border-line bg-white">
      <table className="w-full text-xs">
        <thead>
          <tr className="border-b border-line bg-grey-50 text-left text-muted">
            <th className="w-6 px-2 py-2" />
            <th className="px-3 py-2 font-medium">Presented</th>
            {showCustomer && <th className="px-3 py-2 font-medium">Customer</th>}
            {showCustomer && <th className="px-3 py-2 font-medium">UMRN</th>}
            <th className="px-3 py-2 font-medium">Amount</th>
            <th className="px-3 py-2 font-medium">Status</th>
            <th className="px-3 py-2 font-medium">By</th>
            <th className="px-3 py-2 font-medium">Settlement</th>
            <th className="px-3 py-2 font-medium">UTR</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((d) => {
            const on = open === d.id;
            const st = DEBIT_STATUS[d.status];
            return (
              <React.Fragment key={d.id}>
                <tr
                  className="cursor-pointer border-b border-line/60 hover:bg-grey-50"
                  onClick={() => setOpen(on ? null : d.id)}
                  aria-expanded={on}
                >
                  <td className="px-2 py-2 text-muted">{on ? <ChevronDown size={14} /> : <ChevronRight size={14} />}</td>
                  <td className="whitespace-nowrap px-3 py-2">{d.createdAt ? formatDateTime(d.createdAt) : "—"}</td>
                  {showCustomer && (
                    <td className="px-3 py-2">
                      <div className="font-medium text-navy">{d.customerName ?? `Customer #${d.customerId}`}</div>
                      <div className="text-muted">{d.mobile ?? ""}</div>
                    </td>
                  )}
                  {showCustomer && <td className="px-3 py-2 font-mono">{d.umrn ?? "—"}</td>}
                  <td className="px-3 py-2 font-mono font-semibold text-navy">{paiseToINR(d.amountPaise)}</td>
                  <td className="px-3 py-2">
                    <Badge size="sm" variant={st?.tone ?? "neutral"}>{st?.label ?? d.status}</Badge>
                    {d.overpayment && <span className="ml-1 font-semibold text-error-700">· refund owed</span>}
                  </td>
                  <td className="px-3 py-2">{presentedBy(d)}</td>
                  <td className="px-3 py-2">{d.settlementDate ?? "—"}</td>
                  <td className="px-3 py-2 font-mono">{d.utr ?? "—"}</td>
                </tr>
                {on && (
                  <tr className="border-b border-line/60">
                    <td colSpan={cols} className="px-3 py-3">
                      <DebitDetail d={d} />
                      {onOpenActions && d.applicationId != null && (
                        <button type="button" className="btn btn-sm mt-2" onClick={() => onOpenActions(d)}>
                          Open mandate actions (check status, cancel debit…)
                        </button>
                      )}
                    </td>
                  </tr>
                )}
              </React.Fragment>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}
