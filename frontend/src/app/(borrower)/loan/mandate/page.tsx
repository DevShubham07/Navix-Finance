"use client";

import * as React from "react";
import { useRouter } from "next/navigation";
import { AlertTriangle, CheckCircle2, Loader2, PhoneCall, RefreshCw } from "lucide-react";
import { Reassurance } from "@/components/borrower/reassurance";
import { offerApi, type StepResult } from "@/lib/api/applications";
import { useOffer, completeOfferStep, nextOfferRoute } from "@/lib/offer";
import { formatApiError } from "@/lib/api/errors";

type Phase = "checking" | "ready" | "waiting" | "done" | "failed" | "unsupported";

const POLL_MS = 4000;
const MAX_ATTEMPTS = 45;

/**
 * Auto-repayment (NACH eMandate) setup. Blocking: no skip, and ordinary failures are retried here by
 * the borrower. Same redirect-out / poll-on-return shape as the eSign callback, except Digio returns
 * Digio has no return URL (it ends on its own exit page), so the authorisation opens in a NEW tab and
 * this one stays mounted, polling status — the poll is the source of truth, not any redirect.
 *
 * BANK_UNSUPPORTED is terminal: retrying cannot help, a staff override is the only way forward.
 */
export default function MandatePage() {
  const router = useRouter();
  const { appId } = useOffer();
  const [phase, setPhase] = React.useState<Phase>("checking");
  const [error, setError] = React.useState<string>();
  const [busy, setBusy] = React.useState(false);
  // Bumped by start() so the status poll restarts after a new attempt.
  const [pollKey, setPollKey] = React.useState(0);
  // Set when the browser blocked the new tab, so the borrower can open Digio themselves.
  const [manualUrl, setManualUrl] = React.useState<string>();

  const classify = React.useCallback((r: StepResult): Phase => {
    const st = r.derived?.status;
    if (r.derived?.bankUnsupported === true || st === "BANK_UNSUPPORTED") return "unsupported";
    // PASS also covers a repeat poll after hand-off — treat it as success, never an error.
    if (r.status === "PASS") return "done";
    if (r.status === "FAIL") return "failed";
    return st === "PENDING_AUTHORIZATION" ? "waiting" : "ready";
  }, []);

  // Read the live state on mount / return from Digio, and keep polling while authorisation is pending.
  React.useEffect(() => {
    if (appId == null) return;
    let cancelled = false;
    let timer: ReturnType<typeof setTimeout> | null = null;
    let attempts = 0;
    const tick = async () => {
      attempts += 1;
      try {
        const r = await offerApi.mandateStatus(appId);
        if (cancelled) return;
        const next = classify(r);
        setPhase(next);
        if (next === "waiting" && attempts < MAX_ATTEMPTS) timer = setTimeout(() => { void tick(); }, POLL_MS);
        else if (next === "waiting") setPhase("ready"); // out of patience: let them restart
      } catch {
        if (!cancelled) setPhase("ready");
      }
    };
    void tick();
    return () => { cancelled = true; if (timer) clearTimeout(timer); };
  }, [appId, classify, pollKey]);

  React.useEffect(() => {
    if (phase === "done" && appId != null) {
      void completeOfferStep(appId, "OFFER_MANDATE", router, nextOfferRoute("mandate"));
    }
  }, [phase, appId, router]);

  // Start, and also the retry: init mints a fresh attempt and a new redirect URL each time.
  const start = async () => {
    if (appId == null) return;
    setBusy(true);
    setError(undefined);
    setManualUrl(undefined);
    // Open synchronously inside the click so popup blockers allow it; point it at Digio once init returns.
    const tab = window.open("", "_blank");
    try {
      const r = await offerApi.mandateInit(appId);
      const next = classify(r);
      const url = typeof r.derived?.url === "string" ? (r.derived.url as string) : null;
      if (next === "waiting" && url) {
        if (tab) tab.location.href = url;
        else setManualUrl(url);
        setPhase("waiting");
        setPollKey((k) => k + 1);
      } else {
        tab?.close();
        setPhase(next === "waiting" ? "ready" : next);
        if (next === "ready" || next === "failed") setError("We could not start the setup. Please try again.");
      }
    } catch (err) {
      tab?.close();
      setError(formatApiError(err, "We could not start the setup. Please try again."));
      setPhase("failed");
    }
    setBusy(false);
  };

  if (appId == null || phase === "checking" || phase === "waiting" || phase === "done") {
    return (
      <div className="form-card text-center">
        {phase === "done" ? <CheckCircle2 size={28} className="mx-auto text-success-600" /> : <Loader2 size={28} className="mx-auto animate-spin text-navy" />}
        <p className="lead mt-4 mb-1">{phase === "done" ? "Auto-repayment set up" : phase === "waiting" ? "Confirming with your bank" : "Loading…"}</p>
        <p className="text-sm text-muted">
          {phase === "done"
            ? "Taking you to the next step…"
            : phase === "waiting"
              ? "Your bank's page opened in a new tab. Authorise the mandate there, then come back to this tab — we'll continue automatically. Please keep this tab open."
              : "This takes a few seconds — please don't close this page."}
        </p>
        {phase === "waiting" && manualUrl ? (
          <a href={manualUrl} target="_blank" rel="noopener noreferrer" className="btn btn-gold mt-4">
            Open your bank&apos;s page
          </a>
        ) : null}
      </div>
    );
  }

  if (phase === "unsupported") {
    return (
      <div className="form-card text-center">
        <PhoneCall size={28} className="mx-auto text-navy" />
        <p className="lead mt-4 mb-1">We&apos;ll be in touch</p>
        <p className="text-sm text-muted">
          Your bank can&apos;t set up automatic repayment online. Our team will contact you to finish this
          step — there is nothing more you need to do here.
        </p>
      </div>
    );
  }

  return (
    <div>
      <div className="form-card">
        <p className="lead mb-1">Set up auto-repayment</p>
        {/* PLACEHOLDER COPY — ceiling disclosure wording needs compliance sign-off. */}
        <p className="mb-4 text-sm text-muted">
          To receive your advance, authorise an eMandate with your bank so your repayment can be collected
          automatically on the due date. The mandate allows debits up to a maximum amount (ceiling) that is
          higher than your advance. [Compliance to confirm the final ceiling wording.]
        </p>
        {phase === "failed" ? (
          <p className="mb-3 flex items-start gap-2 text-sm text-error-600">
            <AlertTriangle size={15} className="mt-0.5 flex-shrink-0" />
            {error ?? "The setup did not complete. Nothing was charged — please try again."}
          </p>
        ) : error ? <p className="mb-3 text-sm text-error-600">{error}</p> : null}
        <button type="button" onClick={start} disabled={busy} className="btn btn-gold">
          {busy ? <Loader2 size={16} className="animate-spin" /> : phase === "failed" ? <RefreshCw size={16} /> : null}
          {phase === "failed" ? "Try again" : "Continue to your bank"}
        </button>
      </div>
      <Reassurance />
    </div>
  );
}
