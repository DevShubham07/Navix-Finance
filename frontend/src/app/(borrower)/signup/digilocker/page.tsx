"use client";

import * as React from "react";
import { useRouter } from "next/navigation";
import { Building2, Lock, ArrowRight, Loader2, RefreshCw } from "lucide-react";
import { Reassurance } from "@/components/borrower/reassurance";
import { StepResultBanner } from "@/components/borrower/step-result-banner";
import { useOnboarding, nextAfterStep } from "@/lib/onboarding";
import { verificationApi, type StepResult } from "@/lib/api/applications";
import { formatApiError } from "@/lib/api/errors";

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
  const polls = React.useRef(0);
  const MAX_RETRIES = 3;
  // ~3 min at POLL_MS. The status resolves to PASS once the DigiLocker tab redirects to our
  // callback and that tab fetches the Aadhaar. If consent isn't finished in that window, fall back
  // to advancing so the borrower is never stuck waiting — the unfinished Aadhaar drops to staff
  // manual review at submit-kyc (the callback tab still finalises independently if the user
  // completes consent later).
  const POLL_LIMIT = 45;

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
        setTimeout(() => router.push(nextAfterStep("/signup/pan")), 700);
      }
    } catch (err) {
      setError(formatApiError(err, "Could not finalise DigiLocker."));
      setPhase("failed");
    }
  }, [appId, router]);

  const poll = React.useCallback(async () => {
    if (appId == null) return;
    try {
      polls.current += 1;
      const r = await verificationApi.digilockerStatus(appId);
      setResult(r);
      if (r.status === "PASS") {
        // The callback tab fetched the Aadhaar and our status now reflects it — done.
        stop();
        setPhase("done");
        setTimeout(() => router.push(nextAfterStep("/signup/pan")), 600);
      } else if (r.derived?.completed === true) {
        // The provider reports completion but the Aadhaar hasn't been fetched yet (e.g. the
        // callback tab was blocked). Finalise from here.
        stop();
        await finalise();
      } else if (r.derived?.failed === true || r.status === "FAIL") {
        stop();
        setPhase("failed");
      } else if (polls.current >= POLL_LIMIT) {
        // Consent not confirmed in time. Don't hard-block the wizard — advance; the
        // unfinished Aadhaar falls to staff manual review at submit-kyc.
        stop();
        setPhase("done");
        setTimeout(() => router.push(nextAfterStep("/signup/pan")), 600);
      }
      // Otherwise (pending / client_initiated / in_progress) keep polling: the user is still
      // finishing consent in the DigiLocker tab.
    } catch (err) {
      // Status API failed — stop and surface a retry rather than spinning silently.
      stop();
      setError(formatApiError(err, "Lost connection to DigiLocker."));
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
      polls.current = 0;
      timer.current = setInterval(() => { void poll(); }, POLL_MS);
      void poll();
    } catch (err) {
      setError(formatApiError(err, "Could not start DigiLocker."));
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
