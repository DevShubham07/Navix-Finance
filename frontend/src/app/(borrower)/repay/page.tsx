"use client";

import * as React from "react";
import Link from "next/link";
import { Wallet, Smartphone, Landmark, CheckCircle2, ArrowRight, AlertTriangle } from "lucide-react";
import { Input } from "@/components/ui";
import { InfoRow } from "@/components/borrower/summary";
import { useBorrowerJourney } from "@/lib/mock/borrower";
import { useMounted } from "@/hooks/use-mounted";
import { formatINR0 } from "@/lib/utils";

type Method = "UPI" | "BANK_TRANSFER";

export default function RepayPage() {
  const mounted = useMounted();
  const j = useBorrowerJourney();
  const [method, setMethod] = React.useState<Method>("UPI");
  const [mode, setMode] = React.useState<"full" | "custom">("full");
  const [custom, setCustom] = React.useState("");
  const [txnRef, setTxnRef] = React.useState("");
  const [done, setDone] = React.useState<null | "partial" | "full">(null);

  if (!mounted) {
    return <div className="container max-w-content py-10"><div className="h-72 rounded border border-line bg-white" /></div>;
  }

  const loan = j.loan;
  if (!loan || (j.status !== "ACTIVE" && j.status !== "OVERDUE" && j.status !== "REPAID")) {
    return (
      <div className="container max-w-content py-10">
        <div className="rounded border border-line bg-white p-8 text-center shadow-sm">
          <h1 className="text-2xl">No active loan</h1>
          <p className="mb-4 text-muted">You don&apos;t have anything to repay right now.</p>
          <Link href="/dashboard" className="btn btn-gold">Go to dashboard <ArrowRight size={16} /></Link>
        </div>
      </div>
    );
  }

  if (j.status === "REPAID" || loan.outstanding === 0) {
    return (
      <div className="container max-w-content py-12">
        <div className="mx-auto max-w-md rounded-lg border border-success-100 bg-success-50/50 p-10 text-center shadow-md">
          <span className="mx-auto mb-4 grid h-16 w-16 place-items-center rounded-full bg-success-50 text-success-600">
            <CheckCircle2 size={34} />
          </span>
          <h1 className="text-2xl">Loan fully repaid</h1>
          <p className="mb-6 text-muted">Thank you — your advance is closed and in good standing.</p>
          <Link href="/dashboard" className="btn btn-gold btn-block">Back to dashboard <ArrowRight size={16} /></Link>
        </div>
      </div>
    );
  }

  const amount = mode === "full" ? loan.outstanding : Math.min(Number(custom.replace(/\D/g, "")) || 0, loan.outstanding);
  const canPay = amount > 0 && txnRef.trim().length >= 4;

  const pay = () => {
    if (!canPay) return;
    const willClose = amount >= loan.outstanding;
    j.repay(amount, method, txnRef.trim());
    setDone(willClose ? "full" : "partial");
  };

  if (done) {
    return (
      <div className="container max-w-content py-12">
        <div className="mx-auto max-w-md rounded-lg border border-success-100 bg-white p-10 text-center shadow-md">
          <span className="mx-auto mb-4 grid h-16 w-16 place-items-center rounded-full bg-success-50 text-success-600">
            <CheckCircle2 size={34} />
          </span>
          <h1 className="text-2xl">{done === "full" ? "Payment received — loan closed" : "Payment recorded"}</h1>
          <p className="mb-6 text-muted">
            {done === "full"
              ? "Your advance is fully repaid. A closure confirmation is on its way to your email."
              : `We've recorded ${formatINR0(amount)}. Remaining balance: ${formatINR0(j.loan?.outstanding ?? 0)}.`}
          </p>
          <Link href="/dashboard" className="btn btn-gold btn-block">Back to dashboard <ArrowRight size={16} /></Link>
        </div>
      </div>
    );
  }

  const b = loan.costBreakdown;
  return (
    <div className="container max-w-content py-10">
      <h1 className="mb-1">Repay your advance</h1>
      <p className="mb-7 text-muted">Single repayment on your salary day — or prepay any amount, anytime, no penalty.</p>

      <div className="grid gap-6 lg:grid-cols-2">
        <div className="rounded border border-line bg-white p-6 shadow-sm">
          <h3 className="mb-2 font-serif text-base text-navy">What you owe</h3>
          <InfoRow label="Principal" value={formatINR0(b.principal)} />
          <InfoRow label={`Interest (1%/day × ${b.tenureDays}d)`} value={formatINR0(b.interest)} />
          {loan.penalty > 0 && <InfoRow label="Late penalty (2%/day, cap 30d)" value={<span className="text-error-600">{formatINR0(loan.penalty)}</span>} />}
          <div className="mt-2 flex items-baseline justify-between rounded bg-navy px-4 py-3 text-white">
            <span className="text-sm font-semibold text-white/90">Total outstanding</span>
            <span className="font-serif text-2xl font-bold text-gold">{formatINR0(loan.outstanding)}</span>
          </div>
          {loan.penalty > 0 && (
            <p className="mt-3 flex items-start gap-2 text-xs text-error-600">
              <AlertTriangle size={14} className="mt-0.5 flex-shrink-0" /> Penalty keeps accruing daily until paid. Clear it soon to stop the clock.
            </p>
          )}
        </div>

        <div className="rounded border border-line bg-white p-6 shadow-sm">
          <h3 className="mb-3 font-serif text-base text-navy">How much to pay</h3>
          <div className="mb-4 grid grid-cols-2 gap-2">
            <button
              type="button"
              onClick={() => setMode("full")}
              className={`rounded border px-3 py-2.5 text-sm font-semibold transition ${mode === "full" ? "border-navy bg-navy-tint text-navy" : "border-line text-muted hover:border-navy"}`}
            >
              Pay in full
            </button>
            <button
              type="button"
              onClick={() => setMode("custom")}
              className={`rounded border px-3 py-2.5 text-sm font-semibold transition ${mode === "custom" ? "border-navy bg-navy-tint text-navy" : "border-line text-muted hover:border-navy"}`}
            >
              Part payment
            </button>
          </div>

          {mode === "custom" && (
            <Input
              label="Amount"
              inputMode="numeric"
              value={custom}
              onChange={(e) => setCustom(e.target.value.replace(/\D/g, ""))}
              placeholder={String(loan.outstanding)}
              leftIcon={<span className="font-serif text-muted">₹</span>}
              helperText={`Up to ${formatINR0(loan.outstanding)}`}
            />
          )}

          <div className="mb-2 mt-1 text-sm font-semibold text-ink">Payment method</div>
          <div className="mb-4 grid grid-cols-2 gap-2">
            <button
              type="button"
              onClick={() => setMethod("UPI")}
              className={`flex items-center justify-center gap-2 rounded border px-3 py-2.5 text-sm font-semibold transition ${method === "UPI" ? "border-navy bg-navy-tint text-navy" : "border-line text-muted hover:border-navy"}`}
            >
              <Smartphone size={16} /> UPI
            </button>
            <button
              type="button"
              onClick={() => setMethod("BANK_TRANSFER")}
              className={`flex items-center justify-center gap-2 rounded border px-3 py-2.5 text-sm font-semibold transition ${method === "BANK_TRANSFER" ? "border-navy bg-navy-tint text-navy" : "border-line text-muted hover:border-navy"}`}
            >
              <Landmark size={16} /> Bank transfer
            </button>
          </div>

          <div className="mb-4 rounded bg-grey-100 p-3 text-xs text-muted">
            {method === "UPI" ? (
              <>Pay to <strong className="text-ink">navix.collections@hdfcbank</strong>, then paste the UPI reference below.</>
            ) : (
              <>NEFT/IMPS to <strong className="text-ink">A/C 5010 0099 8877</strong> · IFSC <strong className="text-ink">HDFC0000123</strong>, then paste the UTR below.</>
            )}
          </div>

          <Input
            label={method === "UPI" ? "UPI reference / txn ID" : "UTR number"}
            value={txnRef}
            onChange={(e) => setTxnRef(e.target.value)}
            placeholder={method === "UPI" ? "e.g. 4287… " : "e.g. HDFCN…"}
            helperText="We verify your payment against this reference"
          />

          <button onClick={pay} disabled={!canPay} className="btn btn-gold btn-block mt-2">
            <Wallet size={16} /> Pay {formatINR0(amount)}
          </button>
        </div>
      </div>
    </div>
  );
}
