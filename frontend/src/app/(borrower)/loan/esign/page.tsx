"use client";

import * as React from "react";
import { useRouter } from "next/navigation";
import { FileSignature, Loader2, ShieldCheck } from "lucide-react";
import { Reassurance } from "@/components/borrower/reassurance";
import { StepResultBanner } from "@/components/borrower/step-result-banner";
import { offerApi, type StepResult } from "@/lib/api/applications";
import { useOffer, completeOfferStep, nextOfferRoute, prevOfferRoute } from "@/lib/offer";
import { formatApiError } from "@/lib/api/errors";

/**
 * Screen 9: eSign the sanction letter.
 *
 * <p>The provider behind this is currently a mock (`MockEsignAdapter`) that signs immediately, so
 * there is no redirect to wait on. The page is written for the real flow anyway — one action, one
 * result — so swapping the adapter in changes nothing here.
 *
 * <p>Unlike the identity checks, this one really does gate: the backend refuses to move the
 * application to disbursement without a signature, so a failure here stops rather than passes
 * through.
 */
export default function EsignPage() {
  const router = useRouter();
  const { appId } = useOffer();
  const [busy, setBusy] = React.useState(false);
  const [result, setResult] = React.useState<StepResult | null>(null);
  const [error, setError] = React.useState<string>();

  const sign = async () => {
    if (appId == null) return;
    setBusy(true);
    setError(undefined);
    try {
      const r = await offerApi.esign(appId);
      setResult(r);
      if (r.status === "PASS") {
        await completeOfferStep(appId, "OFFER_ESIGN", router, nextOfferRoute("esign"));
        return;
      }
      setBusy(false);
    } catch (err) {
      setError(formatApiError(err, "Could not sign your agreement — please try again."));
      setBusy(false);
    }
  };

  return (
    <div>
      <div className="form-card text-center">
        <span className="mx-auto mb-4 grid h-16 w-16 place-items-center rounded-full bg-navy text-white">
          <FileSignature size={32} />
        </span>
        <h1 className="text-2xl">Sign your agreement</h1>
        <p className="mx-auto mb-6 max-w-md text-muted">
          Signing confirms you accept the Key Fact Statement you just read — the amount, the charges,
          the repayment date and the total repayable.
        </p>

        <ul className="mx-auto mb-6 max-w-sm space-y-2 text-left text-sm">
          {[
            "Legally valid electronic signature",
            "A signed copy is saved to your documents",
            "Nothing is disbursed until you confirm your bank account",
          ].map((t) => (
            <li key={t} className="flex items-center gap-2 text-ink">
              <ShieldCheck size={15} className="text-success-600" /> {t}
            </li>
          ))}
        </ul>

        <button onClick={sign} disabled={busy} className="btn btn-gold">
          {busy ? <Loader2 size={16} className="animate-spin" /> : <FileSignature size={16} />}
          {busy ? "Signing…" : "Sign now"}
        </button>

        <StepResultBanner result={result} />
        {error ? <p className="mt-3 text-sm text-error-600">{error}</p> : null}
      </div>

      <div className="mt-8">
        <a href={prevOfferRoute("esign")} className="btn btn-outline btn-sm">Back</a>
      </div>
      <Reassurance />
    </div>
  );
}
