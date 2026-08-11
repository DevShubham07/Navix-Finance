"use client";

import * as React from "react";
import { useRouter } from "next/navigation";
import { Loader2, CheckCircle2, ArrowRight, AlertTriangle } from "lucide-react";
import { readStoredAppId } from "@/lib/api/live-journey";
import { offerApi } from "@/lib/api/applications";
import { completeOfferStep, nextOfferRoute } from "@/lib/offer";

type Phase = "working" | "done" | "failed";

const POLL_MS = 4000;
const MAX_ATTEMPTS = 45; // ~3min while the provider finalises and countersigns the PDF

/**
 * Where the Aadhaar e-sign provider returns the borrower. Landing here is the signal to resolve the
 * signature: we poll our own backend (which re-reads the contract from the provider) until it reports a
 * terminal state, because the provider's redirect says only that the journey ended, not that a signature
 * was captured.
 *
 * The `app` and `sid` query params are advisory — the application id comes from local storage, the same
 * rule the DigiLocker callback follows.
 */
export default function EsignCallbackPage() {
  const router = useRouter();
  const [phase, setPhase] = React.useState<Phase>("working");

  React.useEffect(() => {
    let cancelled = false;
    let timer: ReturnType<typeof setTimeout> | null = null;
    const appId = readStoredAppId();
    if (appId == null) {
      setPhase("failed");
      return;
    }

    let attempts = 0;
    const attempt = async () => {
      attempts += 1;
      try {
        const r = await offerApi.esignStatus(appId);
        if (cancelled) return;
        if (r.status === "PASS") {
          setPhase("done");
          // Advance through the shared helper so the server-driven step list decides what's next.
          await completeOfferStep(
            appId,
            "OFFER_SANCTION_LETTER",
            router,
            nextOfferRoute("sanction-letter"),
          );
          return;
        }
        if (r.status === "PENDING" && attempts < MAX_ATTEMPTS) {
          timer = setTimeout(() => { void attempt(); }, POLL_MS);
          return;
        }
        // REVIEW / FAIL, or we ran out of patience: back to the agreement, where drawing is offered.
        setPhase("failed");
      } catch {
        if (cancelled) return;
        setPhase("failed");
      }
    };
    void attempt();
    return () => { cancelled = true; if (timer) clearTimeout(timer); };
  }, [router]);

  return (
    <div className="form-card mx-auto max-w-lg text-center">
      {phase === "working" ? (
        <>
          <Loader2 size={28} className="mx-auto animate-spin text-navy" />
          <p className="lead mt-4 mb-1">Confirming your signature</p>
          <p className="text-sm text-muted">This takes a few seconds — please don&apos;t close this page.</p>
        </>
      ) : phase === "done" ? (
        <>
          <CheckCircle2 size={28} className="mx-auto text-success-600" />
          <p className="lead mt-4 mb-1">Agreement signed</p>
          <p className="text-sm text-muted">Taking you to the next step…</p>
        </>
      ) : (
        <>
          <AlertTriangle size={28} className="mx-auto text-error-600" />
          <p className="lead mt-4 mb-1">We couldn&apos;t confirm your signature</p>
          <p className="text-sm text-muted">
            Nothing is lost. Go back to your agreement to try again, or sign by drawing instead.
          </p>
          <button
            type="button"
            onClick={() => router.push("/loan/sanction-letter")}
            className="btn btn-gold mt-5"
          >
            Back to your agreement <ArrowRight size={16} />
          </button>
        </>
      )}
    </div>
  );
}
