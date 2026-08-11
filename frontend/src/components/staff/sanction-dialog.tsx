"use client";

/**
 * "Accept lead" — the Credit Executive's FINAL credit decision (revamp.md Phase 2, decision 27).
 *
 * There is no Head counter-approval behind this: whatever is submitted here is the sanction the
 * borrower is offered and held to. So the dialog shows the full consequence of the numbers before
 * they're committed — a live repayment schedule computed with the same math the borrower will see.
 *
 * The bank account + IFSC are shown read-only: credit verifies them, but only the borrower may
 * change where the money lands (Phase 3's disbursal-account step, which penny-drops a change).
 */

import * as React from "react";
import { useMutation, useQuery } from "@tanstack/react-query";
import { Loader2 } from "lucide-react";
import { Dialog, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Input, Select } from "@/components/ui";
import { staffApi, type ApplicationView } from "@/lib/api/applications";
import { buildCostBreakdown, daysBetween, dueDateFromSalary } from "@/lib/calc/loan-math";
import { formatDate, formatINR } from "@/lib/utils";
import { useRefreshAfterAction, errMessage } from "@/components/staff/pipeline/hooks";

const MIN_AMOUNT = 1000;

export function SanctionDialog({
  app,
  open,
  onClose,
}: {
  app: ApplicationView;
  open: boolean;
  onClose: () => void;
}) {
  const refresh = useRefreshAfterAction();
  const [amount, setAmount] = React.useState("");
  const [salaryCreditDay, setSalaryCreditDay] = React.useState(String(app.salaryCreditDay ?? 30));
  const [remarks, setRemarks] = React.useState("");

  // The salary account credit verified at intake — read-only context for the decision.
  const profile = useQuery({
    queryKey: ["staff-profile", app.id],
    queryFn: () => staffApi.getProfile(app.id),
    enabled: open,
  });

  const m = useMutation({
    mutationFn: () =>
      staffApi.sanction(app.id, {
        sanctionedAmountPaise: Math.round(Number(amount) * 100),
        salaryCreditDay: Number(salaryCreditDay),
        remarks: remarks.trim() || undefined,
      }),
    onSuccess: () => {
      refresh(app.id);
      onClose();
    },
  });

  const rupees = Number(amount);
  const today = new Date();
  today.setHours(0, 0, 0, 0);
  const projectedDate = dueDateFromSalary({ disbursedOn: today, salaryDay: Number(salaryCreditDay) });
  const tenure = daysBetween(today, projectedDate);
  const amountOk = Number.isFinite(rupees) && rupees >= MIN_AMOUNT;
  const dateOk = Number(salaryCreditDay) >= 1 && Number(salaryCreditDay) <= 31 && tenure > 0;
  const preview = amountOk && dateOk ? buildCostBreakdown(rupees, tenure) : null;

  return (
    // !max-w / !w: globals.css's un-layered `.modal { max-width: 460px }` outranks plain utilities.
    // NB the app's root font-size is 12px, so 52rem here is 624px — not the 832px a 16px root
    // would give. Sized to hold the two-column amount/date row and the schedule grid without wrap.
    <Dialog open={open} onClose={onClose} className="!max-w-[52rem] !w-[min(52rem,94vw)]">
      <DialogHeader>
        <DialogTitle>Accept lead — sanction application #{app.id}</DialogTitle>
        <p className="text-sm text-muted">
          This decision is final and goes straight to disbursement. There is no second approval.
        </p>
      </DialogHeader>

      <div className="grid gap-4 sm:grid-cols-2">
        <Input
          label="Sanctioned amount (₹)"
          required
          inputMode="numeric"
          value={amount}
          onChange={(e) => setAmount(e.target.value.replace(/[^\d]/g, ""))}
          placeholder="25000"
          helperText="The ceiling the borrower may draw from — they may take less."
          error={amount && !amountOk ? `Minimum ₹${MIN_AMOUNT.toLocaleString("en-IN")}` : undefined}
        />
        <Select
          label="Salary credit day"
          required
          value={salaryCreditDay}
          onChange={(e) => setSalaryCreditDay(e.target.value)}
          helperText={dateOk ? `Projected ${formatDate(projectedDate)} — ${tenure} days from today. Final due date is set from actual disbursal.` : undefined}
          error={!dateOk ? "Select a day from 1 to 31" : undefined}
        >
          {Array.from({ length: 31 }, (_, i) => i + 1).map((day) => (
            <option key={day} value={day}>{day}</option>
          ))}
        </Select>
      </div>

      <div className="mt-4 rounded border border-line bg-ivory p-4">
        <p className="mb-2 text-xs font-semibold uppercase tracking-wide text-muted">Salary account</p>
        {profile.isLoading ? (
          <p className="text-sm text-muted">Loading…</p>
        ) : (
          <dl className="grid gap-x-6 gap-y-1 text-[10.4px] sm:grid-cols-3">
            <Kv label="Bank" value={profile.data?.salaryBank} />
            <Kv label="Account number" value={profile.data?.salaryAccountNumber} />
            <Kv label="IFSC" value={profile.data?.salaryIfsc} />
          </dl>
        )}
      </div>

      {preview && (
        <div className="mt-4 rounded border border-line p-4">
          <p className="mb-2 text-xs font-semibold uppercase tracking-wide text-muted">
            Repayment schedule
          </p>
          <dl className="grid gap-x-6 gap-y-1 text-[10.4px] sm:grid-cols-2">
            <Kv label="Principal" value={formatINR(preview.principal)} />
            <Kv label="Processing fee (excl. GST)" value={formatINR(preview.processingFee)} />
            <Kv label="GST on fee" value={formatINR(preview.gstOnFee)} />
            <Kv label="Interest (1%/day)" value={formatINR(preview.interest)} />
            <Kv label="Borrower receives" value={formatINR(preview.netDisbursed)} emphasis />
            <Kv label="Total repayable" value={formatINR(preview.totalRepayable)} emphasis />
          </dl>
        </div>
      )}

      <div className="mt-4">
        <Input
          label="Remarks"
          value={remarks}
          onChange={(e) => setRemarks(e.target.value)}
          placeholder="What you verified, and anything the next person should know"
          helperText="Staff-only — never shown to the borrower."
        />
      </div>

      {m.error ? <p className="mt-2 text-sm text-error-700">{errMessage(m.error)}</p> : null}

      <DialogFooter>
        <button onClick={onClose} className="btn btn-outline" disabled={m.isPending}>
          Cancel
        </button>
        <button
          onClick={() => m.mutate()}
          disabled={m.isPending || !preview}
          className="btn btn-gold disabled:opacity-50"
        >
          {m.isPending ? <Loader2 size={14} className="animate-spin" /> : null} Sanction
        </button>
      </DialogFooter>
    </Dialog>
  );
}

function Kv({ label, value, emphasis }: { label: string; value?: string | null; emphasis?: boolean }) {
  return (
    <div className="flex justify-between gap-4 sm:block">
      <dt className="text-muted">{label}</dt>
      <dd className={emphasis ? "font-semibold text-navy" : "text-ink"}>{value || "—"}</dd>
    </div>
  );
}
