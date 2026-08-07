"use client";

import * as React from "react";
import { useRouter } from "next/navigation";
import { ArrowRight, Banknote, CheckCircle2, PartyPopper } from "lucide-react";
import { Confetti } from "@/components/borrower/confetti";
import { Reassurance } from "@/components/borrower/reassurance";
import { useOffer, completeOfferStep, nextOfferRoute } from "@/lib/offer";
import { formatINR0 } from "@/lib/utils";

/**
 * Screen 10: the celebration. One step left — the borrower still has to say where the money goes,
 * which is what the Sanctioned → Disbursal indicator below is telling them.
 *
 * <p>No Back: the signature behind them is a legal act, and offering a way to "undo" it here would
 * misrepresent what going back does (nothing — the signature stands).
 */
export default function SanctionedPage() {
  const router = useRouter();
  const { app } = useOffer();
  const [busy, setBusy] = React.useState(false);

  const amount = app?.amountRequestedPaise ?? app?.sanctionedAmountPaise ?? null;

  const submit = async () => {
    if (app == null) return;
    setBusy(true);
    await completeOfferStep(app.id, "OFFER_SANCTIONED", router, nextOfferRoute("sanctioned"));
  };

  return (
    <div>
      <Confetti />
      <div className="form-card text-center">
        <span className="mx-auto mb-4 grid h-16 w-16 place-items-center rounded-full bg-success-50 text-success-600">
          <PartyPopper size={34} />
        </span>
        <h1 className="text-2xl">Your loan has been SANCTIONED!</h1>
        <p className="mx-auto mb-6 max-w-md text-muted">One more step to go.</p>

        {amount != null ? (
          <p className="mb-6 font-serif text-3xl font-bold text-navy">
            {formatINR0(Math.round(amount / 100))}
          </p>
        ) : null}

        <ol className="mx-auto mb-8 flex max-w-sm items-center gap-2 text-xs">
          <li className="flex flex-1 flex-col items-center gap-1 text-success-700">
            <CheckCircle2 size={20} />
            <span className="font-semibold">Sanctioned</span>
          </li>
          <li aria-hidden className="h-0.5 flex-1 rounded bg-grey-200" />
          <li className="flex flex-1 flex-col items-center gap-1 text-muted">
            <Banknote size={20} />
            <span className="font-semibold">Disbursal</span>
          </li>
        </ol>

        <button onClick={submit} disabled={busy} className="btn btn-gold">
          Continue <ArrowRight size={16} />
        </button>
      </div>
      <Reassurance />
    </div>
  );
}
