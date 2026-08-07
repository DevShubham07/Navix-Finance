"use client";

import * as React from "react";
import { useRouter } from "next/navigation";
import { Loader2 } from "lucide-react";
import { WizardActions } from "@/components/borrower/wizard-actions";
import { Reassurance } from "@/components/borrower/reassurance";
import { LoanCostBreakdown } from "@/components/borrower/loan-cost-breakdown";
import { offerApi } from "@/lib/api/applications";
import { buildCostBreakdown, MIN_LOAN_AMOUNT } from "@/lib/calc/loan-math";
import { useOffer, completeOfferStep, nextOfferRoute } from "@/lib/offer";
import { formatINR0, formatDate } from "@/lib/utils";
import { formatApiError } from "@/lib/api/errors";

const STEP = 500;

/**
 * Screen 1 of the offer journey: how much of the sanctioned ceiling to draw.
 *
 * <p>Deliberately not the old `AmountChooser`: that one derives its own limit from salary and its own
 * due date from the salary day, and Phase 2 moved both decisions to the Credit Executive. Here the
 * ceiling and the repayment date are given, so the only variable is the drawdown.
 */
export default function LoanAmountPage() {
  const router = useRouter();
  const { appId, app, isLoading } = useOffer();
  const [amount, setAmount] = React.useState<number | null>(null);
  const [busy, setBusy] = React.useState(false);
  const [error, setError] = React.useState<string>();

  const ceiling = Math.floor((app?.sanctionedAmountPaise ?? 0) / 100);
  const min = Math.min(MIN_LOAN_AMOUNT, ceiling);
  const repaymentDate = app?.approvedRepaymentDate ?? null;

  // Default to the full sanctioned amount, or whatever they picked last time they were here.
  React.useEffect(() => {
    if (amount != null || !app) return;
    const alreadyChosen = app.amountRequestedPaise ? Math.floor(app.amountRequestedPaise / 100) : null;
    setAmount(alreadyChosen ?? ceiling);
  }, [app, amount, ceiling]);

  const tenureDays = React.useMemo(() => {
    if (!repaymentDate) return 30;
    const days = Math.round(
      (new Date(`${repaymentDate}T00:00:00`).getTime() - Date.now()) / 864e5,
    );
    return Math.max(1, days);
  }, [repaymentDate]);

  const value = amount ?? ceiling;
  const breakdown = React.useMemo(() => buildCostBreakdown(value, tenureDays), [value, tenureDays]);
  const pct = ceiling > min ? ((value - min) / (ceiling - min)) * 100 : 100;
  const presets = [0.25, 0.5, 0.75, 1].map((f) => ({
    label: `${Math.round(f * 100)}%`,
    value: Math.max(min, Math.round((ceiling * f) / STEP) * STEP),
  }));

  const submit = async () => {
    if (appId == null) return;
    setBusy(true);
    setError(undefined);
    try {
      await offerApi.chooseAmount(appId, Math.round(value * 100));
      await completeOfferStep(appId, "OFFER_AMOUNT", router, nextOfferRoute("amount"));
    } catch (err) {
      setError(formatApiError(err, "Could not save your amount — please try again."));
      setBusy(false);
    }
  };

  if (isLoading || !app) {
    return (
      <p className="flex items-center gap-2 text-sm text-muted">
        <Loader2 size={16} className="animate-spin" /> Loading your offer…
      </p>
    );
  }

  return (
    <div>
      <div className="form-card">
        <p className="lead mb-1">
          You are eligible for up to <strong className="text-navy">{formatINR0(ceiling)}</strong>,
          approved by our credit team.
        </p>
        <p className="mb-6 text-sm text-muted">
          Take all of it or less — you only pay interest on what you draw.
        </p>

        <div className="rounded border border-line bg-white p-6 shadow-sm">
          <div className="flex items-baseline justify-between">
            <span className="text-sm font-semibold text-ink">Loan amount</span>
            <span className="font-serif text-2xl font-bold text-navy">{formatINR0(value)}</span>
          </div>
          <input
            type="range"
            min={min}
            max={ceiling}
            step={STEP}
            value={value}
            aria-label="Loan amount"
            onChange={(e) => setAmount(Number(e.target.value))}
            className="mt-4 w-full"
            style={{
              background: `linear-gradient(to right, var(--gold) ${pct}%, var(--grey-200) ${pct}%)`,
            }}
          />
          <div className="mt-1 flex justify-between text-xs text-muted">
            <span>{formatINR0(min)}</span>
            <span>Approved {formatINR0(ceiling)}</span>
          </div>
          <div className="mt-4 grid grid-cols-4 gap-2 max-[360px]:grid-cols-2">
            {presets.map((p) => (
              <button
                key={p.label}
                type="button"
                onClick={() => setAmount(p.value)}
                aria-pressed={value === p.value}
                className={
                  value === p.value
                    ? "btn btn-sm btn-navy"
                    : "btn btn-sm btn-outline"
                }
              >
                {p.label}
              </button>
            ))}
          </div>
        </div>

        <div className="mt-6">
          <LoanCostBreakdown breakdown={breakdown} />
        </div>

        {repaymentDate ? (
          <p className="mt-4 text-sm text-muted">
            Repayable in one instalment on{" "}
            <strong className="text-navy">{formatDate(repaymentDate)}</strong> — the date our credit
            team set for you.
          </p>
        ) : null}

        {error ? <p className="mt-3 text-sm text-error-600">{error}</p> : null}
      </div>

      <WizardActions onContinue={submit} loading={busy} disabled={busy || value < min} />
      <Reassurance />
    </div>
  );
}
