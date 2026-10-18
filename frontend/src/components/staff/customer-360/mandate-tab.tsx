"use client";

import * as React from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Loader2, ShieldOff } from "lucide-react";
import { EmptyState, Skeleton, toast } from "@/components/ui";
import { useCan, errMessage } from "@/components/staff/pipeline/hooks";
import { collectionsApi, featureFlagsApi, staffApi } from "@/lib/api/applications";
import { formatDateTime } from "@/lib/utils";

const STATUS_LABEL: Record<string, string> = {
  PENDING_AUTHORIZATION: "Awaiting borrower authorisation",
  ACTIVE: "Active",
  AUTHORIZATION_FAILED: "Authorisation failed",
  EXPIRED: "Expired",
  BANK_UNSUPPORTED: "Bank does not support online mandates",
};

const inr2 = (paise: number) =>
  new Intl.NumberFormat("en-IN", { style: "currency", currency: "INR", minimumFractionDigits: 2 }).format(paise / 100);

/** Rupee text ("1250.5") to integer paise, or null when it is not a positive amount with at most 2 decimals. */
export function parseRupeesToPaise(text: string): number | null {
  const t = text.trim();
  if (!/^\d+(\.\d{1,2})?$/.test(t)) return null;
  const paise = Math.round(parseFloat(t) * 100);
  return paise > 0 ? paise : null;
}

/** Latest eMandate attempt. Only the last four of the account is ever shown; the auth link never is. */
export function MandateTab({
  applicationId,
  loanId = null,
  status,
  onChanged,
}: {
  applicationId: number | null;
  loanId?: number | null;
  status: string | null;
  onChanged?: () => void;
}) {
  const can = useCan();
  const qc = useQueryClient();
  const [notes, setNotes] = React.useState("");
  const q = useQuery({
    queryKey: ["staff-mandate", applicationId],
    queryFn: () => staffApi.mandate(applicationId as number),
    enabled: applicationId != null,
  });
  const override = useMutation({
    mutationFn: () => staffApi.mandateOverride(applicationId as number, notes.trim()),
    onSuccess: () => {
      toast.success("Mandate overridden; moved to disbursement");
      setNotes("");
      qc.invalidateQueries({ queryKey: ["staff-mandate", applicationId] });
      onChanged?.();
    },
  });

  // The case (and so the live outstanding + DPD) is only readable by staff allowed to work it; an executive
  // on someone else's case gets CASE_NOT_ASSIGNED here and simply sees no debit action.
  // The server reads digio-debit with defaultWhenMissing=FALSE, so the UI must too: require an explicit
  // true. Without this the box rendered whatever the flag said, and an officer filled in an amount, hit
  // confirm and got DEBIT_DISABLED — a disabled feature that looks exactly like a bug. Same query key as
  // the shell's, so react-query serves it from cache rather than refetching per tab.
  const { data: flags } = useQuery({ queryKey: ["feature-flags"], queryFn: () => featureFlagsApi.get() });
  const debitEnabled = flags?.["digio-debit"] === true;
  const mayDebit = can("mandate:debit") && loanId != null;
  const caseQ = useQuery({
    queryKey: ["staff-debit-case", loanId],
    queryFn: () => collectionsApi.caseByLoan(loanId as number),
    enabled: mayDebit && q.data?.status === "ACTIVE",
    retry: false,
  });
  // History is read whenever the loan exists, independent of the case and of the mandate being ACTIVE:
  // the outcome of real money has to stay visible after the loan closes or the flag is switched off.
  const historyQ = useQuery({
    queryKey: ["staff-debit-history", loanId],
    queryFn: () => collectionsApi.debits(loanId as number),
    enabled: mayDebit,
    retry: false,
  });
  const [amountText, setAmountText] = React.useState("");
  const [confirming, setConfirming] = React.useState(false);
  const debit = useMutation({
    mutationFn: (paise: number) => collectionsApi.debit(loanId as number, paise),
    onSuccess: (r) => {
      toast.success(`Debit of ${inr2(r.amountPaise)} requested`);
      setConfirming(false);
      qc.invalidateQueries({ queryKey: ["staff-debit-case", loanId] });
      qc.invalidateQueries({ queryKey: ["staff-debit-history", loanId] });
      onChanged?.();
    },
  });

  if (applicationId == null) {
    return <EmptyState icon={<ShieldOff size={28} />} title="No application yet." hint="A mandate is registered after sanction." />;
  }
  if (q.isLoading) return <Skeleton className="h-24 w-full" />;
  if (q.isError) return <p className="text-sm text-error-700">{errMessage(q.error)}</p>;
  const m = q.data;
  if (!m) {
    return <EmptyState icon={<ShieldOff size={28} />} title="No mandate started." hint="The borrower registers it in the offer journey." />;
  }

  const rows: [string, string][] = [
    ["Status", (STATUS_LABEL[m.status] ?? m.status) + (m.carriedOver ? " (carried over from an earlier advance)" : "")],
    ["UMRN", m.umrn ?? "—"],
    ["Bank", m.bankName ?? "—"],
    ["Account", m.accountLast4 ? `•••• ${m.accountLast4}` : "—"],
    ["Authorised at", m.authenticatedAt ? formatDateTime(m.authenticatedAt) : "—"],
    ["Attempt", String(m.attemptNo)],
  ];
  if (m.rejectReason) rows.push(["Reject reason", m.rejectReason]);

  const dc = caseQ.data;
  const outstanding = dc?.loan?.outstandingPaise ?? null;
  const showDebit = debitEnabled && mayDebit && m.status === "ACTIVE" && !!m.accountLast4 && dc != null && outstanding != null && outstanding > 0 && dc.dpd >= 1;
  const amountPaise = parseRupeesToPaise(amountText);
  const amountError =
    amountText.trim() === "" ? null
    : amountPaise == null ? "Enter an amount in rupees, up to 2 decimals."
    : outstanding != null && amountPaise > outstanding ? `It cannot be more than the outstanding ${inr2(outstanding)}.`
    : null;

  return (
    <div className="space-y-4">
      <dl className="grid max-w-xl grid-cols-[10rem_1fr] gap-y-2 text-sm">
        {rows.map(([k, v]) => (
          <React.Fragment key={k}>
            <dt className="text-muted">{k}</dt>
            <dd className="font-medium text-navy">{v}</dd>
          </React.Fragment>
        ))}
      </dl>

      {showDebit && (
        <div className="max-w-md space-y-2 rounded border border-line bg-grey-50 p-3">
          <p className="text-xs text-muted">
            Debit the borrower&apos;s bank account now under this mandate. This takes real money and cannot be
            undone. Outstanding today: <strong>{inr2(outstanding as number)}</strong>. The amount can be lowered, never raised.
          </p>
          {!confirming ? (
            <>
              <label className="block text-xs text-muted" htmlFor="debit-amount">Amount to debit (₹)</label>
              <input
                id="debit-amount"
                inputMode="decimal"
                value={amountText}
                onChange={(e) => setAmountText(e.target.value)}
                placeholder={((outstanding as number) / 100).toFixed(2)}
                className="w-full rounded border border-line px-2 py-1.5 text-sm"
              />
              {amountError && <p className="text-xs text-error-700">{amountError}</p>}
              <button
                type="button"
                onClick={() => {
                  if (amountText.trim() === "") setAmountText(((outstanding as number) / 100).toFixed(2));
                  setConfirming(true);
                }}
                disabled={!!amountError}
                className="btn btn-sm btn-navy disabled:opacity-50"
              >
                Review debit
              </button>
            </>
          ) : (
            <>
              <p className="text-sm text-navy" role="alert">
                Debit <strong>{inr2(amountPaise ?? (outstanding as number))}</strong> from the account ending{" "}
                <strong>•••• {m.accountLast4}</strong>?
              </p>
              <div className="flex gap-2">
                <button
                  type="button"
                  onClick={() => debit.mutate(amountPaise ?? (outstanding as number))}
                  disabled={debit.isPending}
                  className="btn btn-sm btn-navy disabled:opacity-50"
                >
                  {debit.isPending && <Loader2 size={14} className="animate-spin" />} Confirm and debit
                </button>
                <button type="button" onClick={() => setConfirming(false)} disabled={debit.isPending} className="btn btn-sm">
                  Cancel
                </button>
              </div>
            </>
          )}
          {debit.isError && <p className="text-xs text-error-700">{errMessage(debit.error)}</p>}
        </div>
      )}

      {mayDebit && (historyQ.data?.length ?? 0) > 0 && (
        <div className="max-w-2xl space-y-2">
          <h4 className="text-sm font-semibold text-navy">Debit attempts</h4>
          <table className="w-full text-xs">
            <thead>
              <tr className="border-b border-line text-left text-muted">
                <th className="py-1.5 pr-3 font-medium">#</th>
                <th className="py-1.5 pr-3 font-medium">Status</th>
                <th className="py-1.5 pr-3 font-medium">Amount</th>
                <th className="py-1.5 pr-3 font-medium">Settlement</th>
                <th className="py-1.5 pr-3 font-medium">UTR</th>
                <th className="py-1.5 font-medium">Trigger</th>
              </tr>
            </thead>
            <tbody>
              {historyQ.data?.map((d) => (
                <tr key={d.debitId} className="border-b border-line/60 last:border-0">
                  <td className="py-1.5 pr-3">{d.attemptNo}</td>
                  <td className="py-1.5 pr-3">
                    {d.status}
                    {/* Real money taken from a borrower who had already repaid — a refund is owed. */}
                    {d.overpayment && <span className="ml-1 font-semibold text-error-700">· refund owed</span>}
                    {d.status === "FAILED" && !d.representable && (
                      <span className="ml-1 text-muted">· not retryable</span>
                    )}
                  </td>
                  <td className="py-1.5 pr-3 font-mono">{inr2(d.amountPaise)}</td>
                  <td className="py-1.5 pr-3">{d.settlementDate ?? "—"}</td>
                  <td className="py-1.5 pr-3 font-mono">{d.utr ?? "—"}</td>
                  <td className="py-1.5">{d.trigger ?? "—"}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {m.overridable && status === "SANCTIONED" && can("mandate:override") && (
        <div className="max-w-md space-y-2 rounded border border-line bg-grey-50 p-3">
          <p className="text-xs text-muted">
            This bank cannot register an online mandate, so the borrower cannot proceed by retrying. Overriding
            sends the file to disbursement without a mandate. This action is logged.
          </p>
          <textarea
            aria-label="Reason for overriding the mandate"
            value={notes}
            onChange={(e) => setNotes(e.target.value)}
            placeholder="Why you're overriding (required, goes in the audit trail)"
            rows={2}
            className="w-full rounded border border-line px-2 py-1.5 text-sm"
          />
          <button
            type="button"
            onClick={() => override.mutate()}
            disabled={override.isPending || !notes.trim()}
            className="btn btn-sm btn-navy disabled:opacity-50"
          >
            {override.isPending && <Loader2 size={14} className="animate-spin" />} Override mandate
          </button>
          {override.isError && <p className="text-xs text-error-700">{errMessage(override.error)}</p>}
        </div>
      )}
    </div>
  );
}
