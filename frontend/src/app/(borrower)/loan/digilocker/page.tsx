"use client";

import * as React from "react";
import { useRouter } from "next/navigation";
import { Building2, Lock, ArrowRight, Loader2, RefreshCw, UploadCloud } from "lucide-react";
import { Reassurance } from "@/components/borrower/reassurance";
import { StepResultBanner } from "@/components/borrower/step-result-banner";
import { useOffer, nextOfferRoute, prevOfferRoute, completeOfferStep } from "@/lib/offer";
import { verificationApi, type StepResult } from "@/lib/api/applications";
import { formatApiError } from "@/lib/api/errors";

type Phase = "idle" | "connecting" | "polling" | "done" | "failed" | "manual";
const POLL_MS = 4000;
// Resolved at call time, not module load: the step list is server-driven since V47.
const next = () => nextOfferRoute("digilocker");

// Aadhaar card photos arrive the same way bank statements do (signup/bank) or the cheque/passbook
// fallback (loan/disbursal-account): a phone photo or a scanned PDF, capped the same way.
const AADHAAR_ACCEPT = "application/pdf,image/jpeg,image/png";
const AADHAAR_MAX_BYTES = 10 * 1024 * 1024;

function pickAadhaarFile(
  e: React.ChangeEvent<HTMLInputElement>,
  setFile: (f: File | null) => void,
  setError: (e: string | undefined) => void,
) {
  const f = e.target.files?.[0] ?? null;
  e.target.value = ""; // allow re-picking the same file after a mistaken selection
  if (!f) return;
  if (!AADHAAR_ACCEPT.split(",").includes(f.type)) {
    setError("Upload a PDF, JPG or PNG file.");
    return;
  }
  if (f.size > AADHAAR_MAX_BYTES) {
    setError("File must be under 10 MB.");
    return;
  }
  setError(undefined);
  setFile(f);
}

/**
 * Screen 3: Aadhaar via DigiLocker. Moved from `/signup/digilocker` — Phase 1 pushed every heavy
 * check past the credit decision, so this now runs against a sanctioned application.
 *
 * <p>The live-flow gotchas documented in CLAUDE.md §14 still apply and are why this looks the way it
 * does: the provider caches the consent session by `redirect_url` (hence the per-attempt nonce), and
 * completion is redirect-driven rather than poll-driven (hence the callback page and the bounded
 * poll here as a fallback).
 *
 * <p>DigiLocker used to be skippable outright — "Skip for now, verify during review" — which left
 * staff with nothing to review: no Aadhaar data, no document, just a note to trust the borrower.
 * There is now no silent skip. Every path that used to bypass the screen (max retries, the
 * poll-limit timeout, the no-consent-URL degradation) now drops the borrower into a mandatory
 * Aadhaar-card upload instead; `advance()` only runs after DigiLocker actually completes or after
 * both card photos are uploaded and recorded (`verifyAadhaarManual`), mirroring the bank-proof
 * fallback on `loan/disbursal-account` that a Disbursement Head later judges.
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
  // ~3 min at POLL_MS. If consent isn't finished in that window we no longer guess it passed —
  // we drop to the Aadhaar-upload fallback instead (see the class doc above).
  const POLL_LIMIT = 45;

  // The Aadhaar-upload fallback is a wholly separate submit path from the DigiLocker state above —
  // it never touches `retryCount` or the poll timer, so its own file/busy/error state is independent.
  const [aadhaarFront, setAadhaarFront] = React.useState<File | null>(null);
  const [aadhaarBack, setAadhaarBack] = React.useState<File | null>(null);
  const [aadhaarBusy, setAadhaarBusy] = React.useState(false);
  const [aadhaarError, setAadhaarError] = React.useState<string>();

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

  // Enters the mandatory Aadhaar-upload fallback. Replaces the old `skip()`, which used to advance
  // straight past this screen — now nothing advances without either a completed DigiLocker or both
  // uploaded card sides.
  const enterAadhaarUpload = React.useCallback(() => {
    stop();
    setPhase("manual");
  }, [stop]);

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
        // Previously advanced blindly here on the assumption consent probably finished. That
        // violated the "no advance without proof" rule this screen now enforces — route to the
        // Aadhaar-upload fallback instead; if the borrower actually did finish in the DigiLocker
        // tab, the callback page finalises independently and this screen is moot.
        stop();
        enterAadhaarUpload();
      }
      // Otherwise (pending / client_initiated / in_progress) keep polling — the borrower is still
      // finishing consent in the DigiLocker tab.
    } catch (err) {
      stop();
      setError(formatApiError(err, "Lost connection to DigiLocker."));
      setPhase("failed");
    }
  }, [appId, stop, finalise, advance, enterAadhaarUpload]);

  const connect = async () => {
    if (appId == null) return;
    if (retryCount >= MAX_RETRIES) { enterAadhaarUpload(); return; }
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
      // DigiLocker as REVIEW; show it, then drop to the Aadhaar-upload fallback rather than
      // advancing on an unproven REVIEW row.
      setResult(r);
      setTimeout(() => enterAadhaarUpload(), 900);
    } catch (err) {
      setError(formatApiError(err, "Could not start DigiLocker."));
      setPhase("failed");
    }
  };

  const pickFront = (e: React.ChangeEvent<HTMLInputElement>) => pickAadhaarFile(e, setAadhaarFront, setAadhaarError);
  const pickBack = (e: React.ChangeEvent<HTMLInputElement>) => pickAadhaarFile(e, setAadhaarBack, setAadhaarError);

  /**
   * The fallback path: no DigiLocker consent, no redirect, no poll — that machinery is exactly what
   * this exists to route around. Both sides upload through the existing presign path as
   * AADHAAR_FRONT / AADHAAR_BACK documents, then `aadhaarManual` records the AADHAAR row as
   * REVIEW/MANUAL_PROOF for the Disbursement Head to judge before release (mirrors
   * `useBankProof` on the disbursal-account screen).
   */
  const submitAadhaarProof = async () => {
    if (appId == null) return;
    if (!aadhaarFront || !aadhaarBack) {
      setAadhaarError("Choose both the front and back of your Aadhaar card first.");
      return;
    }
    setAadhaarBusy(true);
    setAadhaarError(undefined);
    try {
      for (const [docType, file] of [
        ["AADHAAR_FRONT", aadhaarFront],
        ["AADHAAR_BACK", aadhaarBack],
      ] as const) {
        const contentType = file.type || "application/octet-stream";
        const { key, url } = await verificationApi.presignUpload(appId, {
          docType,
          fileName: file.name,
          contentType,
        });
        await verificationApi.putToPresignedUrl(url, file, contentType);
        await verificationApi.uploadedDocuments(appId, { docType, objectKeys: [key] });
      }
      await verificationApi.aadhaarManual(appId);
      advance();
    } catch (err) {
      setAadhaarError(formatApiError(err, "Could not upload your Aadhaar card — please try again."));
      setAadhaarBusy(false);
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

        {phase === "manual" ? (
          <div className="space-y-4 text-left">
            <p className="lead mb-1">Upload your Aadhaar card instead</p>
            <p className="text-sm text-muted">
              DigiLocker didn&apos;t connect. Upload clear photos or scans of both sides of your
              Aadhaar card and we&apos;ll verify it manually before your advance is sent.
            </p>
            <div>
              <p className="mb-1 text-sm font-semibold text-ink">Aadhaar front</p>
              <input
                type="file"
                accept={AADHAAR_ACCEPT}
                onChange={pickFront}
                className="block w-full text-sm text-ink file:mr-3 file:rounded file:border-0 file:bg-navy file:px-3 file:py-1.5 file:text-sm file:font-semibold file:text-white file:cursor-pointer"
              />
              {aadhaarFront ? <p className="mt-1 text-sm text-muted">Selected: {aadhaarFront.name}</p> : null}
            </div>
            <div>
              <p className="mb-1 text-sm font-semibold text-ink">Aadhaar back</p>
              <input
                type="file"
                accept={AADHAAR_ACCEPT}
                onChange={pickBack}
                className="block w-full text-sm text-ink file:mr-3 file:rounded file:border-0 file:bg-navy file:px-3 file:py-1.5 file:text-sm file:font-semibold file:text-white file:cursor-pointer"
              />
              {aadhaarBack ? <p className="mt-1 text-sm text-muted">Selected: {aadhaarBack.name}</p> : null}
            </div>
            {aadhaarError ? <p className="text-sm text-error-600">{aadhaarError}</p> : null}
            <button
              type="button"
              onClick={submitAadhaarProof}
              disabled={aadhaarBusy || !aadhaarFront || !aadhaarBack}
              className="btn btn-gold"
            >
              {aadhaarBusy ? <Loader2 size={16} className="animate-spin" /> : <UploadCloud size={16} />}
              {aadhaarBusy ? "Uploading…" : "Upload & continue"}
            </button>
            {/* Entering upload mode must not be a one-way door. The entry point is a link a borrower
                can hit by accident, and DigiLocker — when it works — is both faster for them and
                better evidence for us (parsed Aadhaar data and the face crop the selfie step
                matches against, neither of which a card photo yields). Retries are still capped by
                MAX_RETRIES, so this cannot become an infinite loop. */}
            {retryCount < MAX_RETRIES && (
              <button
                type="button"
                onClick={() => { setAadhaarError(undefined); setPhase("idle"); }}
                disabled={aadhaarBusy}
                className="block text-sm font-semibold text-navy hover:underline disabled:opacity-50"
              >
                Try DigiLocker again instead
              </button>
            )}
          </div>
        ) : phase === "polling" || phase === "connecting" ? (
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
              DigiLocker didn&apos;t connect after a few tries. No problem — upload your Aadhaar
              card instead and our team will verify it during review.
            </div>
            <button onClick={enterAadhaarUpload} className="btn btn-gold">
              <UploadCloud size={16} /> Upload Aadhaar card instead
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
              <button type="button" onClick={enterAadhaarUpload} className="text-sm font-semibold text-navy hover:underline">
                Can&apos;t connect DigiLocker? Upload your Aadhaar card instead
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
