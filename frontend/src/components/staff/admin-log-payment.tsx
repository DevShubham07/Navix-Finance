"use client";

/**
 * ADMIN-only "log a payment that already happened".
 *
 * Money moves in the real world before it moves in the ledger: a borrower walks into a branch, a
 * bank line is reconciled two days later, a transfer never gets uploaded. Until now the only way in
 * was the borrower's own repay screen, which always stamps today — so a payment made last Tuesday
 * was recorded as today's, and the borrower silently paid interest for the days in between.
 *
 * The date drives the arithmetic, so the dialog shows the real figures **as of the chosen date**
 * before anything is written: pick the day, see what was owed that day, pay exactly that. Recorded
 * and verified in one step (ADMIN already holds the verify right), which is what lets the loan close
 * — and the borrower's LOAN_CLOSED notification fire — the moment it clears.
 */

import * as React from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Loader2, HandCoins } from "lucide-react";
import { Dialog, DialogHeader, DialogTitle, Input, Select } from "@/components/ui";
import { LoanBreakdown } from "@/components/staff/loan-breakdown";
import { errMessage, useStaffMe } from "@/components/staff/pipeline/hooks";
import {
  staffApi,
  storageApi,
  paiseToINR,
  rupeesToPaise,
  type LoanView,
  type PaymentMethodName,
} from "@/lib/api/applications";

/** Loan states where money can still be taken. A closed loan has nothing left to log against. */
const OPEN_FOR_PAYMENT = new Set(["ACTIVE", "OVERDUE", "IN_COLLECTIONS", "DISBURSED", "DEFAULTED"]);

const METHODS: PaymentMethodName[] = ["UPI", "BANK_TRANSFER"];

function todayISO(): string {
  return new Date().toISOString().slice(0, 10);
}

export function AdminLogPaymentButton({
  loanId,
  loanStatus,
  compact,
}: {
  loanId: number | null | undefined;
  loanStatus?: string | null;
  compact?: boolean;
}) {
  const role = useStaffMe().data?.role;
  const [open, setOpen] = React.useState(false);

  // Not an oversight that this is ADMIN-only: recording and verifying in one step collapses the
  // maker-checker every other payment goes through, so it stays with the one role that already
  // bypasses those checks. The backend enforces the same rule.
  if (role !== "ADMIN" || loanId == null) return null;
  if (loanStatus != null && !OPEN_FOR_PAYMENT.has(loanStatus)) return null;

  return (
    <>
      <button
        onClick={() => setOpen(true)}
        className={compact ? "btn btn-sm btn-outline" : "btn btn-outline"}
        title="Record a payment the borrower has already made"
      >
        <HandCoins size={14} /> Log payment
      </button>
      {open && <AdminLogPaymentDialog loanId={loanId} onClose={() => setOpen(false)} />}
    </>
  );
}

function AdminLogPaymentDialog({ loanId, onClose }: { loanId: number; onClose: () => void }) {
  const qc = useQueryClient();
  const [paidOn, setPaidOn] = React.useState(todayISO());
  const [amount, setAmount] = React.useState("");
  const [method, setMethod] = React.useState<PaymentMethodName>("UPI");
  const [txnRef, setTxnRef] = React.useState("");
  const [proof, setProof] = React.useState<File | null>(null);
  const [touchedAmount, setTouchedAmount] = React.useState(false);

  const loanQ = useQuery({ queryKey: ["staff-loan", loanId], queryFn: () => staffApi.loan(loanId) });
  const loan: LoanView | undefined = loanQ.data;

  // The whole point of the dialog: what was owed ON the chosen date, not today. Refetched on every
  // date change so the operator is never paying against a stale figure.
  const outQ = useQuery({
    queryKey: ["staff-outstanding-asof", loanId, paidOn],
    queryFn: () => staffApi.outstanding(loanId, paidOn),
    enabled: !!paidOn,
  });
  const dueOnDate = outQ.data?.outstandingPaise ?? null;

  // Prefill with the settling amount, but stop the moment the operator types their own — a payment
  // that was short is exactly the case this has to record faithfully.
  React.useEffect(() => {
    if (!touchedAmount && dueOnDate != null) {
      setAmount(String(Math.round(dueOnDate / 100)));
    }
  }, [dueOnDate, touchedAmount]);

  const record = useMutation({
    mutationFn: async () => {
      // Optional for admin (the backend mandates proof only for borrowers), but uploaded the same
      // way as the borrower's repay screen: presign → direct browser→S3 PUT, key sent as proofUrl.
      let proofUrl: string | undefined;
      if (proof) {
        const up = await storageApi.presignUpload({
          category: "REPAYMENT_PROOF",
          filename: proof.name,
          contentType: proof.type || "application/octet-stream",
        });
        await storageApi.putToPresignedUrl(up.url, proof);
        proofUrl = up.key;
      }
      return staffApi.adminRecordRepayment(loanId, {
        amountPaise: rupeesToPaise(Number(amount)),
        method,
        paidOn,
        txnRef: txnRef.trim() || undefined,
        proofUrl,
      });
    },
    onSuccess: () => {
      for (const key of [
        ["staff-loan", loanId],
        ["staff-loans"],
        ["staff-outstanding-asof", loanId],
        ["staff-queue"],
        ["staff-dashboard-queue"],
        ["collections-worklist"],
        ["collections-cases"],
        ["customers"],
      ]) {
        qc.invalidateQueries({ queryKey: key });
      }
      onClose();
    },
  });

  const amountPaise = Number(amount) > 0 ? rupeesToPaise(Number(amount)) : 0;
  const settles = dueOnDate != null && amountPaise >= dueOnDate;
  const canSubmit = amountPaise > 0 && !!paidOn && !record.isPending;

  return (
    <Dialog open onClose={onClose} className="max-w-2xl" aria-label="Log a payment">
      <DialogHeader>
        <DialogTitle>Log a payment · Loan #{loanId}</DialogTitle>
        <p className="text-sm text-muted">
          For money already received. Recorded and verified in one step — no accountant queue.
        </p>
      </DialogHeader>

      <div className="grid gap-4 md:grid-cols-2">
        <div>
          <Input
            label="Date the borrower paid"
            type="date"
            value={paidOn}
            max={todayISO()}
            min={loan?.disbursedOn ?? undefined}
            onChange={(e) => {
              setPaidOn(e.target.value);
              setTouchedAmount(false);
            }}
          />
          <Input
            label="Amount (₹)"
            type="number"
            min={1}
            value={amount}
            onChange={(e) => {
              setTouchedAmount(true);
              setAmount(e.target.value);
            }}
          />
          <Select
            label="Method"
            value={method}
            onChange={(e) => setMethod(e.target.value as PaymentMethodName)}
          >
            {METHODS.map((m) => (
              <option key={m} value={m}>
                {m === "UPI" ? "UPI" : "Bank transfer"}
              </option>
            ))}
          </Select>
          <Input
            label="Transaction reference (optional)"
            value={txnRef}
            onChange={(e) => setTxnRef(e.target.value)}
          />
          <Input
            label="Payment screenshot (optional)"
            type="file"
            accept="image/*,.pdf"
            onChange={(e) => setProof(e.target.files?.[0] ?? null)}
          />
        </div>

        <div className="rounded border border-line bg-ivory p-4 text-sm">
          <p className="mb-2 font-semibold text-navy">If they paid on {paidOn}</p>
          {outQ.isLoading ? (
            <div className="h-24 animate-pulse rounded bg-white" />
          ) : outQ.error ? (
            <p className="text-error-700">{errMessage(outQ.error)}</p>
          ) : loan && outQ.data ? (
            <>
              <LoanBreakdown loan={loan} outstanding={outQ.data} />
              <p className="mt-3 text-xs text-muted">
                {settles
                  ? "This settles the loan — it closes on this date and the borrower is notified."
                  : dueOnDate != null
                    ? `Part payment — ${paiseToINR(dueOnDate - amountPaise)} would remain, and interest keeps running.`
                    : null}
              </p>
            </>
          ) : null}
        </div>
      </div>

      {record.error && (
        <p className="mt-3 text-sm text-error-700">{errMessage(record.error)}</p>
      )}

      <div className="mt-5 flex justify-end gap-2">
        <button onClick={onClose} className="btn btn-outline">
          Cancel
        </button>
        <button
          onClick={() => record.mutate()}
          disabled={!canSubmit}
          className="btn btn-primary disabled:opacity-50"
        >
          {record.isPending && <Loader2 size={14} className="animate-spin" />} Record payment
        </button>
      </div>
    </Dialog>
  );
}
