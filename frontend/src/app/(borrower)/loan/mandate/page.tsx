"use client";

import * as React from "react";
import { useRouter } from "next/navigation";
import { AlertTriangle, CheckCircle2, Loader2, PhoneCall, RefreshCw } from "lucide-react";
import { Reassurance } from "@/components/borrower/reassurance";
import { offerApi, type StepResult } from "@/lib/api/applications";
import { completeOfferStep, nextOfferRoute } from "@/lib/offer";
import { useBorrowerSession, useLiveApplication } from "@/lib/api/live-journey";
import { formatApiError } from "@/lib/api/errors";

type Phase = "checking" | "ready" | "waiting" | "stalled" | "done" | "failed" | "unsupported";

const POLL_MS = 4000;
const MAX_ATTEMPTS = 45;
const MAX_CONSECUTIVE_ERRORS = 3;

/** Only ever send the borrower to https on Digio's own domain. */
function isDigioUrl(raw: string): boolean {
  try {
    const u = new URL(raw);
    return u.protocol === "https:" && (u.hostname === "digio.in" || u.hostname.endsWith(".digio.in"));
  } catch {
    return false;
  }
}

/**
 * Auto-repayment (NACH eMandate) setup. Blocking: no skip, and ordinary failures are retried here by
 * the borrower. Digio has no return URL (it ends on its own exit page), so the authorisation opens in
 * a NEW tab and this one stays mounted, polling status — the poll is the source of truth, not any
 * redirect. Mount reads the live status, so a refresh or second tab resumes correctly.
 *
 * BANK_UNSUPPORTED is terminal: retrying cannot help, a staff override is the only way forward.
 */
export default function MandatePage() {
  const router = useRouter();
  // useLiveApplication, NOT useOffer: useOffer redirects any application that is not SANCTIONED to
  // /loan/status, which is correct for every other offer step and wrong for this one. A borrower with a
  // live part-repaid loan — or a closed one setting auto-repayment up before their next advance — is
  // exactly who an invite link is for, and bouncing them was an artefact of the shared hook, not a rule.
  // The backend decides who may actually register (OfferService.MANDATE_SETUP_ALLOWED).
  const { appId, app, isLoading: appLoading } = useLiveApplication({ needLoan: false });
  // The invite link carries no token, so it is often opened signed out (SMS on a phone, a fresh browser).
  // Without a session /mine never resolves an app id and the page spun on "Loading…" for ever — send the
  // borrower to sign in and bring them straight back here.
  const { data: session, isLoading: sessionLoading } = useBorrowerSession();
  React.useEffect(() => {
    if (!sessionLoading && session === null) router.replace("/login?next=/loan/mandate");
  }, [session, sessionLoading, router]);
  // Signed in, /mine settled, still nothing to point at: say so instead of spinning.
  const noApplication = !!session && appId == null && !appLoading;
  // In the journey this step is blocking and success advances it. Reached by invite on a loan that has
  // already left the journey, success is simply the end: there is nothing to advance to.
  const inJourney = app?.status === "SANCTIONED";
  const [phase, setPhase] = React.useState<Phase>("checking");
  const [error, setError] = React.useState<string>();
  const [busy, setBusy] = React.useState(false);
  // Synchronous re-entry guard: every init files a real NPCI mandate, so a double-tap must not send two.
  const initInFlight = React.useRef(false);
  // Bumped by start() so the status poll restarts after a new attempt.
  const [pollKey, setPollKey] = React.useState(0);
  // Set when the browser blocked the new tab, so the borrower can open Digio themselves.
  const [bankUrl, setBankUrl] = React.useState<string>();
  const [popupBlocked, setPopupBlocked] = React.useState(false);
  const [stalled, setStalled] = React.useState<string>();

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
    let errors = 0;
    const tick = async () => {
      attempts += 1;
      try {
        const r = await offerApi.mandateStatus(appId);
        if (cancelled) return;
        errors = 0;
        const next = classify(r);
        if (next === "waiting" && attempts >= MAX_ATTEMPTS) {
          setStalled("We haven't seen your mandate complete yet. If you've authorised it with your bank, check again in a moment.");
          setPhase("stalled");
          return;
        }
        setPhase(next);
        if (next === "waiting") timer = setTimeout(() => { void tick(); }, POLL_MS);
      } catch {
        if (cancelled) return;
        errors += 1;
        if (errors >= MAX_CONSECUTIVE_ERRORS) {
          setStalled("We couldn't check your mandate right now. If you've authorised it with your bank, try checking again.");
          setPhase("stalled");
          return;
        }
        timer = setTimeout(() => { void tick(); }, POLL_MS);
      }
    };
    void tick();
    return () => { cancelled = true; if (timer) clearTimeout(timer); };
  }, [appId, classify, pollKey]);

  React.useEffect(() => {
    if (phase === "done" && appId != null && inJourney) {
      void completeOfferStep(appId, "OFFER_MANDATE", router, nextOfferRoute("mandate"));
    }
  }, [phase, appId, router, inJourney]);

  // Start, and also the retry: init mints a fresh attempt and a new redirect URL each time.
  const start = async () => {
    if (appId == null || initInFlight.current) return;
    initInFlight.current = true;
    setBusy(true);
    setError(undefined);
    setPopupBlocked(false);
    // Open synchronously inside the click so popup blockers allow it; point it at Digio once init returns.
    const tab = window.open("", "_blank");
    if (tab) tab.opener = null; // reverse-tabnabbing: the opened page must not reach this session
    try {
      const r = await offerApi.mandateInit(appId);
      const next = classify(r);
      const url = typeof r.derived?.url === "string" ? (r.derived.url as string) : null;
      if (next === "waiting" && url && isDigioUrl(url)) {
        setBankUrl(url);
        if (tab) tab.location.href = url;
        else setPopupBlocked(true);
        setPhase("waiting");
        setPollKey((k) => k + 1);
      } else {
        tab?.close();
        if (next === "waiting") {
          // Pending but no usable (or untrusted) URL: nothing to send the borrower to.
          setError("We could not open your bank's page. Please try again.");
          setPhase("failed");
        } else {
          setPhase(next);
          if (next === "ready" || next === "failed") setError("We could not start the setup. Please try again.");
        }
      }
    } catch (err) {
      tab?.close();
      setError(formatApiError(err, "We could not start the setup. Please try again."));
      setPhase("failed");
    }
    initInFlight.current = false;
    setBusy(false);
  };

  // Re-open the bank page; if we never had its URL (page reloaded), mint a fresh attempt instead.
  const reopen = () => {
    if (bankUrl) window.open(bankUrl, "_blank", "noopener,noreferrer");
    else void start();
  };

  const checkAgain = () => {
    setStalled(undefined);
    setPhase("waiting");
    setPollKey((k) => k + 1);
  };

  if (noApplication) {
    return (
      <div className="form-card text-center">
        <AlertTriangle size={28} className="mx-auto text-warning-800" />
        <p className="lead mt-4 mb-1">No advance found</p>
        <p className="text-sm text-muted">
          We couldn&apos;t find an advance on this account to set up auto-repayment for. If you signed in
          with a different mobile number, sign out and use the one your advance is on.
        </p>
        <a href="/dashboard" className="btn btn-gold mt-4">Go to my dashboard</a>
      </div>
    );
  }

  if (appId == null || phase === "checking" || phase === "waiting" || phase === "done") {
    return (
      <div className="form-card text-center">
        {phase === "done" ? <CheckCircle2 size={28} className="mx-auto text-success-600" /> : <Loader2 size={28} className="mx-auto animate-spin text-navy" />}
        <p className="lead mt-4 mb-1">{phase === "done" ? "Auto-repayment set up" : phase === "waiting" ? "Confirming with your bank" : "Loading…"}</p>
        <p className="text-sm text-muted">
          {phase === "done"
            ? inJourney
              ? "Taking you to the next step…"
              : "Your repayment will now be collected automatically on the due date. There's nothing else to do."
            : phase === "waiting"
              ? "Your bank's page opened in a new tab. Authorise the mandate there, then come back to this tab — we'll continue automatically. Please keep this tab open."
              : "This takes a few seconds — please don't close this page."}
        </p>
        {phase === "done" && !inJourney ? (
          <a href="/dashboard" className="btn btn-gold mt-4">Back to my dashboard</a>
        ) : null}
        {phase === "waiting" ? (
          popupBlocked && bankUrl ? (
            <a href={bankUrl} target="_blank" rel="noopener noreferrer" className="btn btn-gold mt-4">
              Open your bank&apos;s page
            </a>
          ) : (
            <button type="button" onClick={reopen} disabled={busy} className="btn btn-outline mt-4">
              {busy ? <Loader2 size={16} className="animate-spin" /> : null}
              Reopen your bank&apos;s page
            </button>
          )
        ) : null}
      </div>
    );
  }

  if (phase === "stalled") {
    return (
      <div className="form-card text-center">
        <AlertTriangle size={28} className="mx-auto text-warning-800" />
        <p className="lead mt-4 mb-1">Still waiting</p>
        <p className="text-sm text-muted">{stalled}</p>
        <div className="mt-4 flex justify-center gap-2">
          <button type="button" onClick={checkAgain} className="btn btn-gold">Check again</button>
          <button type="button" onClick={reopen} disabled={busy} className="btn btn-outline">
            {busy ? <Loader2 size={16} className="animate-spin" /> : null}
            Reopen your bank&apos;s page
          </button>
        </div>
      </div>
    );
  }

  if (phase === "unsupported") {
    return (
      <div className="form-card text-center">
        <PhoneCall size={28} className="mx-auto text-navy" />
        <p className="lead mt-4 mb-1">We&apos;ll be in touch</p>
        <p className="text-sm text-muted">
          Your bank can&apos;t set up automatic repayment online. Our team will be in touch with you to
          finish this step, so you don&apos;t need to do anything or try again.
          {inJourney ? " Your advance will move forward once it is done." : ""}
        </p>
      </div>
    );
  }

  return (
    <div>
      <div className="form-card">
        <p className="lead mb-1">Set up auto-repayment</p>
        {/*
          COMPLIANCE: the ceiling disclosure below is factual and states no figure. Compliance still owes us
          the final wording (and whether the amount itself must be shown) — change it HERE, never by leaving
          a marker in the copy: the previous marker rendered to borrowers.
        */}
        <p className="mb-4 text-sm text-muted">
          {inJourney
            ? "To receive your advance, authorise an eMandate with your bank so your repayment can be collected automatically on the due date."
            : "Authorise an eMandate with your bank so your repayment is collected automatically on the due date — nothing to remember, and no late fee for forgetting."}{" "}
          The mandate allows debits up to a maximum amount (ceiling) that is higher than your advance.
          You can cancel it with your bank at any time.
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
