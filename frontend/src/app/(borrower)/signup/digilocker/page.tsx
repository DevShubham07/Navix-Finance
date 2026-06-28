"use client";

import * as React from "react";
import { useRouter } from "next/navigation";
import { Building2, Lock, ArrowRight, Loader2, RefreshCw } from "lucide-react";
import { Reassurance } from "@/components/borrower/reassurance";
import { StepResultBanner } from "@/components/borrower/step-result-banner";
import { useOnboarding } from "@/lib/onboarding";
import { verificationApi, ApplicationApiError, type StepResult } from "@/lib/api/applications";

type Phase = "idle" | "connecting" | "polling" | "done" | "failed";
const POLL_MS = 4000;

export default function SignupDigiLockerPage() {
  const router = useRouter();
  const { mounted, appId } = useOnboarding();
  const [phase, setPhase] = React.useState<Phase>("idle");
  const [result, setResult] = React.useState<StepResult | null>(null);
  const [error, setError] = React.useState<string>();
  const [retryCount, setRetryCount] = React.useState(0);
  const timer = React.useRef<ReturnType<typeof setInterval> | null>(null);
  const MAX_RETRIES = 3;

  React.useEffect(() => {
    if (mounted && appId == null) router.replace("/signup/mobile-otp");
  }, [mounted, appId, router]);

  const stop = React.useCallback(() => {
    if (timer.current) { clearInterval(timer.current); timer.current = null; }
  }, []);

  React.useEffect(() => () => stop(), [stop]);

  const finalise = React.useCallback(async () => {
    if (appId == null) return;
    try {
      const r = await verificationApi.digilockerComplete(appId);
      setResult(r);
      setPhase(r.status === "FAIL" ? "failed" : "done");
      if (r.status === "PASS" || r.status === "REVIEW") {
        setTimeout(() => router.push("/signup/pan"), 700);
      }
    } catch (err) {
      setError(err instanceof ApplicationApiError ? `${err.message} (${err.code})` : "Could not finalise DigiLocker.");
      setPhase("failed");
    }
  }, [appId, router]);

  const poll = React.useCallback(async () => {
    if (appId == null) return;
    try {
      const r = await verificationApi.digilockerStatus(appId);
      setResult(r);
      const status = r.derived?.status;
      if (r.derived?.completed === true) {
        // Fully completed in this tab — fetch the Aadhaar and route.
        stop();
        await finalise();
      } else if (status === "client_initiated") {
        // The DigiLocker session is live with the provider (the user has been handed off
        // to the consent tab). Don't block the wizard waiting for full completion — the
        // separate callback tab finalises the Aadhaar fetch. Stop polling and continue.
        stop();
        setPhase("done");
        setTimeout(() => router.push("/signup/pan"), 600);
      } else if (r.derived?.failed === true || r.status === "FAIL") {
        stop();
        setPhase("failed");
      }
      // Any other status (e.g. "pending") → keep polling until the session is initiated.
    } catch (err) {
      // Status API failed — stop and surface a retry rather than spinning silently.
      stop();
      setError(err instanceof ApplicationApiError ? `${err.message} (${err.code})` : "Lost connection to DigiLocker.");
      setPhase("failed");
    }
  }, [appId, stop, finalise, router]);

  const connect = async () => {
    if (appId == null) return;
    if (retryCount >= MAX_RETRIES) {
      setError("Maximum DigiLocker attempts reached. Please contact support.");
      return;
    }
    setRetryCount((n) => n + 1);
    setPhase("connecting");
    setError(undefined);
    setResult(null);
    try {
      // The DigiLocker provider (Fintrix/Surepass) caches the consent session keyed by
      // redirect_url and re-serves the SAME (eventually-expired) token for a repeat URL.
      // A fixed callback therefore gets stuck on a stale token → the SDK rejects it with
      // "Access Denied / token expired". Make the URL unique per attempt to force a fresh
      // session. The callback page resolves the app from localStorage, so it ignores these
      // params. (See verify/digilocker/init flow.)
      const nonce = `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 8)}`;
      const redirectUrl = `${window.location.origin}/kyc/digilocker/callback?app=${appId}&sid=${nonce}`;
      const r = await verificationApi.digilockerInit(appId, redirectUrl);
      const url = typeof r.derived?.url === "string" ? (r.derived.url as string) : null;
      if (url) window.open(url, "_blank", "noopener,noreferrer");
      setPhase("polling");
      stop();
      timer.current = setInterval(() => { void poll(); }, POLL_MS);
      void poll();
    } catch (err) {
      setError(err instanceof ApplicationApiError ? `${err.message} (${err.code})` : "Could not start DigiLocker.");
      setPhase("failed");
    }
  };

  return (
    <div>
      <div className="form-card text-center">
        <span className="mx-auto mb-4 grid h-16 w-16 place-items-center rounded-full bg-navy text-white">
          <Building2 size={32} />
        </span>
        <h1 className="text-2xl">Connect DigiLocker</h1>
        <p className="mx-auto mb-6 max-w-md text-muted">
          You&apos;ll head to DigiLocker to share your Aadhaar securely. We only receive the details you approve —
          never your password.
        </p>

        <ul className="mx-auto mb-6 max-w-sm space-y-2 text-left text-sm">
          {["Government of India digital identity", "Consent-based, read-only access", "Bank-grade encryption"].map((t) => (
            <li key={t} className="flex items-center gap-2 text-ink">
              <Lock size={15} className="text-success-600" /> {t}
            </li>
          ))}
        </ul>

        {phase === "polling" || phase === "connecting" ? (
          <div className="flex items-center justify-center gap-2 text-sm text-muted">
            <Loader2 size={16} className="animate-spin" />
            {phase === "connecting" ? "Opening DigiLocker…" : "Waiting for you to finish in the DigiLocker tab…"}
          </div>
        ) : phase === "done" ? (
          <div className="flex items-center justify-center gap-2 text-sm font-semibold text-success-700">
            Aadhaar verified — continuing… <ArrowRight size={16} />
          </div>
        ) : retryCount >= MAX_RETRIES && phase === "failed" ? (
          <div className="rounded border border-error-100 bg-error-50 p-4 text-sm text-error-700">
            You&apos;ve reached the maximum of {MAX_RETRIES} DigiLocker attempts. Please{" "}
            <a href="mailto:info@navixfinance.com" className="font-semibold underline">contact our support team</a>{" "}
            to continue your application.
          </div>
        ) : (
          <>
            <button onClick={connect} className="btn btn-gold">
              {phase === "failed" ? <RefreshCw size={16} /> : null}
              {phase === "failed" ? "Try DigiLocker again" : "Continue with DigiLocker"}
              {phase !== "failed" ? <ArrowRight size={16} /> : null}
            </button>
            {phase === "failed" && retryCount > 0 && retryCount < MAX_RETRIES && (
              <p className="mt-2 text-xs text-muted">
                {MAX_RETRIES - retryCount} attempt{MAX_RETRIES - retryCount !== 1 ? "s" : ""} remaining
              </p>
            )}
          </>
        )}

        <StepResultBanner result={phase === "done" || phase === "failed" ? result : null} />
        {error ? <p className="mt-3 text-sm text-error-600">{error}</p> : null}
      </div>

      <div className="mt-8">
        <a href="/signup/address" className="btn btn-outline btn-sm">Back</a>
      </div>
      <Reassurance />
    </div>
  );
}
