"use client";

import * as React from "react";
import { useRouter } from "next/navigation";
import { Loader2, CheckCircle2, ArrowRight, AlertTriangle } from "lucide-react";
import { readStoredAppId } from "@/lib/api/live-journey";
import { verificationApi, ApplicationApiError } from "@/lib/api/applications";

type Phase = "working" | "done" | "unavailable" | "denied" | "failed";

const RETRY_MS = 4000;
const MAX_ATTEMPTS = 45; // ~3min of retrying while the provider materialises the Aadhaar XML

// An upstream outage is not a race we can win by asking faster. Polling a down DigiLocker at the
// not-ready cadence is what ran one borrower to 183 provider calls in 20 minutes, so these back off
// to half-minute waits and give up after a handful of them.
const UPSTREAM_RETRY_MS = 30_000;
const MAX_UPSTREAM_ATTEMPTS = 6;

/**
 * DigiLocker's return target (the `redirectUrl` passed to verify/digilocker/init).
 *
 * <p>Landing here IS the post-consent signal — DigiLocker redirected the user back — so we finalise
 * directly rather than waiting on the provider's status flag, which routinely never reports
 * completed. `digilockerComplete` probes the Aadhaar XML, which is the real source of truth; if it
 * isn't ready the instant we ask, the backend returns a retryable DIGILOCKER_NOT_READY and we poll
 * a bounded number of times. Every other error code used to fail on the first try with nothing on
 * screen, so a declined consent and a provider outage looked identical — they now have their own
 * cadences and their own cards.
 *
 * <p>The step moved to the Phase-3 offer journey, so the routes here point at `/loan/*`.
 */
export default function KycDigiLockerCallbackPage() {
  const router = useRouter();
  const [phase, setPhase] = React.useState<Phase>("working");
  // An e-Aadhaar whose digital signature didn't check out is still an Aadhaar we hold; the copy
  // differs but the borrower moves on either way.
  const [dscReview, setDscReview] = React.useState(false);

  React.useEffect(() => {
    let cancelled = false;
    let timer: ReturnType<typeof setTimeout> | null = null;
    const appId = readStoredAppId();
    if (appId == null) { setPhase("failed"); return; }

    // Two budgets, never shared: a slow outage must not eat the not-ready allowance the borrower
    // actually needs when the XML is merely still materialising.
    let attempts = 0;
    let upstreamAttempts = 0;
    const attempt = async () => {
      try {
        const r = await verificationApi.digilockerComplete(appId);
        if (cancelled) return;
        if (r.status === "REVIEW" && r.derived?.validDsc === false) {
          // The backend has the e-Aadhaar and will never fetch it again, so sending the borrower
          // back to retry would only burn consent sessions for a verdict that is already final.
          setDscReview(true);
          setPhase("done");
          return;
        }
        setPhase(r.status === "FAIL" ? "failed" : "done");
      } catch (err) {
        if (cancelled) return;
        const code = err instanceof ApplicationApiError ? err.code : null;
        if (code === "DIGILOCKER_CONSENT_DENIED") {
          // Terminal for this session — the provider will keep saying no until a new one is minted.
          setPhase("denied");
          return;
        }
        if (code === "DIGILOCKER_NOT_READY") {
          attempts += 1;
          if (attempts < MAX_ATTEMPTS) {
            timer = setTimeout(() => { void attempt(); }, RETRY_MS);
            return;
          }
          setPhase("failed");
          return;
        }
        if (code === "DIGILOCKER_UPSTREAM_DOWN" || code === "DIGILOCKER_PROVIDER_ERROR") {
          upstreamAttempts += 1;
          if (upstreamAttempts < MAX_UPSTREAM_ATTEMPTS) {
            timer = setTimeout(() => { void attempt(); }, UPSTREAM_RETRY_MS);
            return;
          }
          setPhase("unavailable");
          return;
        }
        setPhase("failed");
      }
    };

    void attempt();
    return () => { cancelled = true; if (timer) clearTimeout(timer); };
  }, []);

  return (
    <div className="container max-w-content py-16">
      <div className="mx-auto max-w-md rounded-lg border border-line bg-white p-10 text-center shadow-md">
        {phase === "done" ? (
          <>
            <span className="mx-auto mb-4 grid h-16 w-16 place-items-center rounded-full bg-success-50 text-success-600">
              <CheckCircle2 size={34} />
            </span>
            <h1 className="text-2xl">{dscReview ? "Aadhaar received" : "Aadhaar verified"}</h1>
            <p className="mb-6 text-muted">
              {dscReview
                ? "We have your Aadhaar from DigiLocker and our team will verify it during review. You can carry on with your loan."
                : "Your identity is confirmed via DigiLocker. You can carry on with your loan."}
            </p>
            <button onClick={() => router.push("/loan/references")} className="btn btn-gold btn-block">
              Continue <ArrowRight size={16} />
            </button>
          </>
        ) : phase === "unavailable" ? (
          <>
            <span className="mx-auto mb-4 grid h-16 w-16 place-items-center rounded-full bg-warning-50 text-warning-700">
              <AlertTriangle size={32} />
            </span>
            <h1 className="text-2xl">DigiLocker is temporarily unavailable</h1>
            <p className="mb-6 text-muted">
              DigiLocker isn&apos;t responding right now. Upload your Aadhaar card instead and we&apos;ll
              verify it during review, or come back to this step a little later.
            </p>
            <button onClick={() => router.push("/loan/digilocker?manual=1")} className="btn btn-gold btn-block">
              Upload my Aadhaar instead <ArrowRight size={16} />
            </button>
            <button onClick={() => router.push("/loan/status")} className="btn btn-outline btn-block mt-3">
              Try again later
            </button>
          </>
        ) : phase === "denied" ? (
          <>
            <span className="mx-auto mb-4 grid h-16 w-16 place-items-center rounded-full bg-warning-50 text-warning-700">
              <AlertTriangle size={32} />
            </span>
            <h1 className="text-2xl">You declined the DigiLocker consent</h1>
            <p className="mb-6 text-muted">
              We can&apos;t read your Aadhaar without your approval. Start DigiLocker again and accept
              the consent screen to carry on.
            </p>
            <button onClick={() => router.push("/loan/digilocker")} className="btn btn-gold btn-block">
              Start DigiLocker again <ArrowRight size={16} />
            </button>
          </>
        ) : phase === "failed" ? (
          <>
            <span className="mx-auto mb-4 grid h-16 w-16 place-items-center rounded-full bg-error-50 text-error-600">
              <AlertTriangle size={32} />
            </span>
            <h1 className="text-2xl">Couldn&apos;t confirm DigiLocker</h1>
            <p className="mb-6 text-muted">Please return to your application and try the DigiLocker step again.</p>
            <button onClick={() => router.push("/loan/digilocker")} className="btn btn-gold btn-block">
              Back to DigiLocker <ArrowRight size={16} />
            </button>
          </>
        ) : (
          <>
            <span className="mx-auto mb-4 grid h-16 w-16 place-items-center rounded-full bg-navy-tint text-navy">
              <Loader2 size={34} className="animate-spin" />
            </span>
            <h1 className="text-2xl">Confirming your consent</h1>
            <p className="text-muted">Fetching your Aadhaar details from DigiLocker. This takes a moment…</p>
          </>
        )}
      </div>
    </div>
  );
}
