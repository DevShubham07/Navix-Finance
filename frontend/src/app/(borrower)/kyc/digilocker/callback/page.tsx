"use client";

import * as React from "react";
import { useRouter } from "next/navigation";
import { Loader2, CheckCircle2, ArrowRight, AlertTriangle } from "lucide-react";
import { readStoredAppId } from "@/lib/api/live-journey";
import { verificationApi, ApplicationApiError } from "@/lib/api/applications";

type Phase = "working" | "done" | "failed";

const RETRY_MS = 4000;
const MAX_ATTEMPTS = 8; // ~30s of retrying while the provider materialises the Aadhaar XML

/**
 * Real DigiLocker return target (the `redirectUrl` passed to verify/digilocker/init).
 *
 * Landing here IS the post-consent signal (DigiLocker redirected the user back), so we
 * finalise directly — `digilockerComplete` probes the Aadhaar XML, which is the true
 * source of truth — instead of waiting for the provider's status flag, which is unreliable
 * and routinely never reports completed. If the XML isn't ready the instant we ask, the
 * backend returns a retryable DIGILOCKER_NOT_READY and we poll a bounded number of times.
 */
export default function KycDigiLockerCallbackPage() {
  const router = useRouter();
  const [phase, setPhase] = React.useState<Phase>("working");

  React.useEffect(() => {
    let cancelled = false;
    let timer: ReturnType<typeof setTimeout> | null = null;
    const appId = readStoredAppId();
    if (appId == null) { setPhase("failed"); return; }

    let attempts = 0;
    const attempt = async () => {
      attempts += 1;
      try {
        const r = await verificationApi.digilockerComplete(appId);
        if (!cancelled) setPhase(r.status === "FAIL" ? "failed" : "done");
      } catch (err) {
        if (cancelled) return;
        const retryable = err instanceof ApplicationApiError && err.code === "DIGILOCKER_NOT_READY";
        if (retryable && attempts < MAX_ATTEMPTS) {
          timer = setTimeout(() => { void attempt(); }, RETRY_MS);
        } else {
          setPhase("failed");
        }
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
            <h1 className="text-2xl">Aadhaar verified</h1>
            <p className="mb-6 text-muted">Your identity is confirmed via DigiLocker. You can return to your application.</p>
            <button onClick={() => router.push("/signup/pan")} className="btn btn-gold btn-block">
              Continue <ArrowRight size={16} />
            </button>
          </>
        ) : phase === "failed" ? (
          <>
            <span className="mx-auto mb-4 grid h-16 w-16 place-items-center rounded-full bg-error-50 text-error-600">
              <AlertTriangle size={32} />
            </span>
            <h1 className="text-2xl">Couldn&apos;t confirm DigiLocker</h1>
            <p className="mb-6 text-muted">Please return to your application and try the DigiLocker step again.</p>
            <button onClick={() => router.push("/signup/digilocker")} className="btn btn-gold btn-block">
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
