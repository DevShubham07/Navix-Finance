"use client";

import * as React from "react";
import { useRouter } from "next/navigation";
import { useQuery } from "@tanstack/react-query";
import { AlertTriangle, ExternalLink, FileText, Loader2 } from "lucide-react";
import { WizardActions } from "@/components/borrower/wizard-actions";
import { Reassurance } from "@/components/borrower/reassurance";
import { offerApi } from "@/lib/api/applications";
import { useOffer, completeOfferStep, nextOfferRoute, prevOfferRoute } from "@/lib/offer";
import { formatApiError } from "@/lib/api/errors";

/**
 * Screen 8: read the sanction letter — a full Key Fact Statement (revamp.md decision 38).
 *
 * <p>No submit button of its own: the borrower reads here and signs on the next screen. The PDF is
 * re-rendered server-side on every visit, so a borrower who went back and changed their amount reads
 * the letter matching what they'll actually sign, never a cached earlier one.
 */
export default function SanctionLetterPage() {
  const router = useRouter();
  const { appId } = useOffer();
  const [busy, setBusy] = React.useState(false);

  const q = useQuery({
    queryKey: ["sanction-letter", appId],
    queryFn: () => offerApi.sanctionLetter(appId as number),
    enabled: appId != null,
    // The URL is a short-lived presign; don't serve a stale one from cache on a revisit.
    gcTime: 0,
    staleTime: 0,
    retry: false,
  });

  const submit = async () => {
    if (appId == null) return;
    setBusy(true);
    await completeOfferStep(appId, "OFFER_SANCTION_LETTER", router, nextOfferRoute("sanction-letter"));
  };

  return (
    <div>
      <div className="form-card">
        <p className="lead mb-1">Your sanction letter</p>
        <p className="mb-5 text-sm text-muted">
          This is the Key Fact Statement for your loan — the amount, the charges, what reaches your
          account, and what you repay. Read it before you sign on the next screen.
        </p>

        {q.isLoading ? (
          <div className="flex items-center justify-center gap-2 rounded border border-line bg-grey-100 py-16 text-sm text-muted">
            <Loader2 size={16} className="animate-spin" /> Preparing your sanction letter…
          </div>
        ) : q.error ? (
          <div className="flex items-start gap-2 rounded border border-error-100 bg-error-50 p-4 text-sm text-error-700">
            <AlertTriangle size={16} className="mt-0.5 flex-shrink-0" />
            <span>{formatApiError(q.error, "Could not prepare your sanction letter.")}</span>
          </div>
        ) : q.data ? (
          <>
            {/* An <object> renders inline where the browser has a PDF viewer, and falls back to the
                link below where it doesn't (most mobile browsers) — the letter must be readable on
                a phone, which is where most borrowers are. */}
            <iframe
              src={q.data.url}
              aria-label="Sanction letter"
              className="h-[32rem] w-full rounded border border-line bg-white"
            >
              <p className="p-6 text-sm text-muted">
                Your browser can&apos;t display the PDF here — open it using the link below.
              </p>
            </iframe>
            <a
              href={q.data.url}
              target="_blank"
              rel="noopener noreferrer"
              className="btn btn-outline mt-4 sm:btn-sm"
            >
              <FileText size={16} /> Open sanction letter <ExternalLink size={14} />
            </a>
          </>
        ) : null}
      </div>

      <WizardActions
        backHref={prevOfferRoute("sanction-letter")}
        continueLabel="I've read it — continue"
        onContinue={submit}
        loading={busy}
        disabled={busy || !q.data}
      />
      <Reassurance />
    </div>
  );
}
