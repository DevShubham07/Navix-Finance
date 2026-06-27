"use client";

import * as React from "react";
import { useRouter } from "next/navigation";
import { Gauge, Loader2, CheckCircle2, RefreshCw, ArrowRight } from "lucide-react";
import { Reassurance } from "@/components/borrower/reassurance";
import { useOnboarding } from "@/lib/onboarding";
import { verificationApi, ApplicationApiError, type StepResult } from "@/lib/api/applications";

type Phase = "running" | "done" | "failed";

export default function SignupBureauPage() {
  const router = useRouter();
  const { mounted, appId } = useOnboarding();
  const [phase, setPhase] = React.useState<Phase>("running");
  const [error, setError] = React.useState<string>();
  const ran = React.useRef(false);

  React.useEffect(() => {
    if (mounted && appId == null) router.replace("/signup/mobile-otp");
  }, [mounted, appId, router]);

  const run = React.useCallback(async () => {
    if (appId == null) return;
    setPhase("running");
    setError(undefined);
    try {
      const r: StepResult = await verificationApi.bureau(appId);
      // Score / risk category are intentionally never surfaced to the borrower.
      setPhase(r.status === "FAIL" ? "failed" : "done");
    } catch (err) {
      setError(err instanceof ApplicationApiError ? `${err.message} (${err.code})` : "Could not run the credit check.");
      setPhase("failed");
    }
  }, [appId]);

  React.useEffect(() => {
    if (appId != null && !ran.current) {
      ran.current = true;
      void run();
    }
  }, [appId, run]);

  return (
    <div>
      <div className="form-card text-center">
        <span className={`mx-auto mb-4 grid h-16 w-16 place-items-center rounded-full ${phase === "done" ? "bg-success-50 text-success-600" : "bg-navy-tint text-navy"}`}>
          {phase === "done" ? <CheckCircle2 size={32} /> : phase === "failed" ? <Gauge size={32} /> : <Loader2 size={32} className="animate-spin" />}
        </span>
        <h1 className="text-2xl">
          {phase === "running" ? "Running your credit check" : phase === "done" ? "Credit check complete" : "Credit check didn't finish"}
        </h1>
        <p className="mx-auto mb-6 max-w-md text-muted">
          {phase === "running"
            ? "We're securely checking your credit history with the bureau. This takes a few seconds."
            : phase === "done"
              ? "All done — no action needed. Let's confirm your salary next."
              : "Something went wrong reaching the credit bureau. Please try again."}
        </p>

        {phase === "done" ? (
          <button onClick={() => router.push("/signup/salary")} className="btn btn-gold">
            Continue <ArrowRight size={16} />
          </button>
        ) : phase === "failed" ? (
          <button onClick={run} className="btn btn-gold">
            <RefreshCw size={16} /> Retry credit check
          </button>
        ) : null}

        {error ? <p className="mt-3 text-sm text-error-600">{error}</p> : null}
      </div>

      <div className="mt-8">
        <a href="/signup/pan" className="btn btn-outline btn-sm">Back</a>
      </div>
      <Reassurance />
    </div>
  );
}
