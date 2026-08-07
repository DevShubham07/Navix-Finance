"use client";

import * as React from "react";
import { useRouter } from "next/navigation";
import { useQuery } from "@tanstack/react-query";
import { CalendarClock, Loader2, Wallet } from "lucide-react";
import { WizardActions } from "@/components/borrower/wizard-actions";
import { Reassurance } from "@/components/borrower/reassurance";
import { offerApi } from "@/lib/api/applications";
import { useOffer, completeOfferStep, nextOfferRoute, prevOfferRoute } from "@/lib/offer";
import { formatINR0, formatDate } from "@/lib/utils";

/**
 * Screen 5: the loan summary the borrower sees before committing.
 *
 * <p>Every figure comes from the server (`/offer/summary`), computed with the same `LoanMath` the
 * disbursed loan will use — not recomputed here. A summary that disagreed with the ledger by a rupee
 * would be a summary the borrower was misled by, and the two must be the same arithmetic.
 *
 * <p>GST is folded into the fee's caption rather than given its own line (revamp.md decision 36);
 * "You will receive" is the highlighted figure because it is the one the borrower actually plans on.
 */
export default function LoanSummaryPage() {
  const router = useRouter();
  const { appId } = useOffer();
  const [busy, setBusy] = React.useState(false);

  const q = useQuery({
    queryKey: ["offer-summary", appId],
    queryFn: () => offerApi.summary(appId as number),
    enabled: appId != null,
  });

  const s = q.data;

  const submit = async () => {
    if (appId == null) return;
    setBusy(true);
    await completeOfferStep(appId, "OFFER_SUMMARY", router, nextOfferRoute("summary"));
  };

  if (q.isLoading || !s) {
    return (
      <p className="flex items-center gap-2 text-sm text-muted">
        <Loader2 size={16} className="animate-spin" /> Preparing your summary…
      </p>
    );
  }

  const rupees = (paise: number) => formatINR0(Math.round(paise / 100));

  return (
    <div>
      <div className="form-card">
        <p className="lead mb-6">Here&apos;s everything about your loan, before you confirm.</p>

        <dl className="rounded border border-line bg-white shadow-sm">
          <Row label="Loan Amount" value={rupees(s.principalPaise)} />
          <Row label="Interest Rate" value="1% per day" />
          <Row label="Tenure" value={`${s.tenureDays} days`} />
          <Row
            label="Processing Fee"
            hint="excluding GST"
            value={`− ${rupees(s.processingFeePaise)}`}
          />
          <Row
            label="Repayment"
            hint={s.repaymentDate ? `on ${formatDate(s.repaymentDate)}` : undefined}
            value={rupees(s.totalRepayablePaise)}
          />
        </dl>

        <div className="mt-4 flex items-baseline justify-between rounded bg-navy px-5 py-5 text-white">
          <span className="flex items-center gap-2 text-sm font-semibold text-white/90">
            <Wallet size={16} /> You will receive
          </span>
          <span className="font-serif text-3xl font-bold text-gold">
            {rupees(s.netDisbursedPaise)}
          </span>
        </div>

        <div className="mt-4 flex items-start gap-3 rounded border border-line bg-navy-tint p-4">
          <CalendarClock size={18} className="mt-0.5 flex-shrink-0 text-navy" />
          <div>
            <p className="text-xs font-semibold uppercase tracking-wide text-muted">
              Expected disbursal date
            </p>
            <p className="font-serif text-lg font-bold text-navy">
              {formatDate(s.expectedDisbursalDate)}
            </p>
          </div>
        </div>

        <p className="mt-4 text-xs text-muted">
          {/* principalPaise is what the borrower CHOSE to draw; the sanctioned figure is
              ceilingPaise. Calling the drawdown "sanctioned" misreports the offer whenever they
              take less than the ceiling — which the amount screen actively encourages. */}
          The processing fee and GST are deducted upfront, which is why you receive{" "}
          {rupees(s.netDisbursedPaise)} of the {rupees(s.principalPaise)} you are borrowing. Pay
          early and you pay less interest — it is charged only to the day you repay.
        </p>
      </div>

      <WizardActions
        backHref={prevOfferRoute("summary")}
        continueLabel="Looks right, continue"
        onContinue={submit}
        loading={busy}
        disabled={busy}
      />
      <Reassurance />
    </div>
  );
}

function Row({ label, value, hint }: { label: string; value: string; hint?: string }) {
  return (
    <div className="flex items-baseline justify-between border-b border-grey-200 px-5 py-3 last:border-0">
      <dt className="text-sm text-muted">
        {label}
        {hint ? <span className="block text-xs text-muted/80">{hint}</span> : null}
      </dt>
      <dd className="font-serif font-semibold text-ink">{value}</dd>
    </div>
  );
}
