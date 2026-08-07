"use client";

import * as React from "react";
import { useRouter } from "next/navigation";
import { Building2, Lock, ArrowRight, Loader2, RefreshCw } from "lucide-react";
import { Reassurance } from "@/components/borrower/reassurance";
import { StepResultBanner } from "@/components/borrower/step-result-banner";
import { useOffer, nextOfferRoute, prevOfferRoute, completeOfferStep } from "@/lib/offer";
import { verificationApi, type StepResult } from "@/lib/api/applications";
import { formatApiError } from "@/lib/api/errors";

type Phase = "idle" | "connecting" | "polling" | "done" | "failed";
const POLL_MS = 4000;
// Resolved at call time, not module load: the step list is server-driven since V47.
const next = () => nextOfferRoute("digilocker");

/**
 * Screen 3: Aadhaar via DigiLocker. Moved from `/signup/digilocker` — Phase 1 pushed every heavy
 * check past the credit decision, so this now runs against a sanctioned application.
 *
 * <p>The live-flow gotchas documented in CLAUDE.md §14 still apply and are why this looks the way it
 * does: the provider caches the consent session by `redirect_url` (hence the per-attempt nonce), and
 * completion is redirect-driven rather than poll-driven (hence the callback page and the bounded
 * poll here as a fallback).
 *
 * <p>Never blocks. A failure passes through silently to the staff Verification Dashboard
 * (revamp.md decision 11).
 */
export default function LoanDigiLockerPage() {
  const router = useRouter();
  const { appId } = useOffer();
  const [phase, setPhase] = React.useState<Phase>("idle");
  const [result, setResult] = React.useState<StepResult | null>(null);
  const [error, setError] = React.useState<string>();
  const [retryCount, setRetryCount] = React.useState(0);
  const timer = React.useRef<ReturnType<typeof setInterval> | null>(null);
  const polls = React.useRef(0);
  const MAX_RETRIES = 3;
  // ~3 min at POLL_MS. If consent isn't finished in that window we advance anyway rather than
  // strand the borrower; the callback tab still finalises independently if they finish later.
  const POLL_LIMIT = 45;

  const stop = React.useCallback(() => {
    if (timer.current) { clearInterval(timer.current); timer.current = null; }
  }, []);

  React.useEffect(() => () => stop(), [stop]);

  const advance = React.useCallback(() => {
    if (appId == null) { router.push(next()); return; }
    void completeOfferStep(appId, "OFFER_DIGILOCKER", router, next());
  }, [appId, router]);

  const finalise = React.useCallback(async () => {
    if (appId == null) return;
    try {
      const r = await verificationApi.digilockerComplete(appId);
      setResult(r);
      setPhase(r.status === "FAIL" ? "failed" : "done");
      if (r.status === "PASS" || r.status === "REVIEW") setTimeout(advance, 700);
    } catch (err) {
      setError(formatApiError(err, "Could not finalise DigiLocker."));
      setPhase("failed");
    }
  }, [appId, advance]);

  const poll = React.useCallback(async () => {
    if (appId == null) return;
    try {
      polls.current += 1;
      const r = await verificationApi.digilockerStatus(appId);
      setResult(r);
      if (r.status === "PASS") {
        stop();
        setPhase("done");
        setTimeout(advance, 600);
      } else if (r.derived?.completed === true) {
        // The provider reports completion but the Aadhaar hasn't been fetched yet (e.g. the
        // callback tab was blocked). Finalise from here.
        stop();
        await finalise();
      } else if (r.derived?.failed === true || r.status === "FAIL") {
        stop();
        setPhase("failed");
      } else if (polls.current >= POLL_LIMIT) {
        stop();
        setPhase("done");
        setTimeout(advance, 600);
      }
      // Otherwise (pending / client_initiated / in_progress) keep polling — the borrower is still
      // finishing consent in the DigiLocker tab.
    } catch (err) {
      stop();
      setError(formatApiError(err, "Lost connection to DigiLocker."));
      setPhase("failed");
    }
  }, [appId, stop, finalise, advance]);

  const skip = React.useCallback(() => {
    stop();
    setPhase("done");
    advance();
  }, [stop, advance]);

  const connect = async () => {
    if (appId == null) return;
    if (retryCount >= MAX_RETRIES) { skip(); return; }
    setRetryCount((n) => n + 1);
    setPhase("connecting");
    setError(undefined);
    setResult(null);
    try {
      // The provider caches the consent session keyed by redirect_url and re-serves the SAME
      // (eventually-expired) token for a repeat URL, which the SDK then rejects with "Access
      // Denied". A per-attempt nonce forces a fresh session. The callback resolves the app from
      // localStorage, so it ignores these params.
      const nonce = `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 8)}`;
      const redirectUrl = `${window.location.origin}/kyc/digilocker/callback?app=${appId}&sid=${nonce}`;
      const r = await verificationApi.digilockerInit(appId, redirectUrl);
      const url = typeof r.derived?.url === "string" ? (r.derived.url as string) : null;
      if (url) {
        // Same tab: consent redirects back to our callback, which finalises and routes forward.
        // Navigation unloads this page immediately; the poll below is a fallback for the rare
        // case navigation is blocked.
        window.location.assign(url);
        setPhase("polling");
        stop();
        polls.current = 0;
        timer.current = setInterval(() => { void poll(); }, POLL_MS);
        void poll();
        return;
      }
      // No consent URL — the provider couldn't start a session. The backend already recorded
      // DigiLocker as REVIEW; show it and move on rather than blocking.
      setResult(r);
      setPhase("done");
      setTimeout(() => skip(), 900);
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
          You&apos;ll head to DigiLocker to share your Aadhaar securely. We only receive the details
          you approve — never your password.
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
            Continuing… <ArrowRight size={16} />
          </div>
        ) : retryCount >= MAX_RETRIES && phase === "failed" ? (
          <div className="space-y-4">
            <div className="rounded border border-line bg-grey-100 p-4 text-sm text-ink">
              DigiLocker didn&apos;t connect after a few tries. No problem — you can continue now and
              our team will verify your Aadhaar during review.
            </div>
            <button onClick={skip} className="btn btn-gold">
              Continue <ArrowRight size={16} />
            </button>
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
            <div className="mt-4">
              <button type="button" onClick={skip} className="text-sm font-semibold text-navy hover:underline">
                Skip for now — verify during review
              </button>
            </div>
          </>
        )}

        <StepResultBanner result={phase === "done" || phase === "failed" ? result : null} />
        {error ? <p className="mt-3 text-sm text-error-600">{error}</p> : null}
      </div>

      <div className="mt-8">
        <a href={prevOfferRoute("digilocker")} className="btn btn-outline btn-sm">Back</a>
      </div>
      <Reassurance />
    </div>
  );
}
