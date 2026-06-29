"use client";

import * as React from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { RotateCw, Zap, ShieldCheck, ArrowRight, Loader2, AlertTriangle } from "lucide-react";
import { useMounted } from "@/hooks/use-mounted";
import { useLiveApplication, writeStoredAppId } from "@/lib/api/live-journey";
import { borrowerApi, paiseToINR, ApplicationApiError } from "@/lib/api/applications";

/**
 * Returning-borrower reborrow (live). A repeat borrower in good standing is pre-approved — one tap
 * reuses their saved KYC profile and drops them on the amount page (no signup). A borrower with past
 * delinquency is instead routed to a KYC re-review (we show their status page). Calls the real
 * `POST /api/borrower/applications/reborrow`; the backend decides the routing from loan history.
 */
export default function ReloanPage() {
  const router = useRouter();
  const mounted = useMounted();
  const { app } = useLiveApplication();
  const [busy, setBusy] = React.useState(false);
  const [error, setError] = React.useState<string | null>(null);
  const [noPrior, setNoPrior] = React.useState(false);

  if (!mounted) {
    return (
      <div className="container max-w-content py-10">
        <div className="h-72 rounded border border-line bg-white" />
      </div>
    );
  }

  // A borrower with a live advance can still borrow again — their available amount is just reduced
  // by what they currently owe (computed server-side on reborrow), so we don't block here anymore.
  const hasLiveLoan = app?.status === "ACTIVE" || app?.status === "OVERDUE";

  if (noPrior) {
    return (
      <div className="container max-w-content py-10">
        <div className="rounded border border-line bg-white p-8 text-center shadow-sm">
          <h1 className="text-2xl">Welcome back</h1>
          <p className="mb-4 text-muted">Start a fresh application to borrow with NAVIX.</p>
          <Link href="/signup/mobile-otp" className="btn btn-gold">Apply now <ArrowRight size={16} /></Link>
        </div>
      </div>
    );
  }

  const borrowAgain = async () => {
    setBusy(true);
    setError(null);
    try {
      const result = await borrowerApi.reborrow();
      writeStoredAppId(result.id);
      // Flagged (past delinquency) → KYC review on the status page. Pre-approved → upload the latest
      // salary slip (the only thing we re-collect), then choose an amount.
      router.push(result.status === "REVIEW_PENDING" ? "/loan/status" : "/reborrow/salary");
    } catch (e) {
      if (e instanceof ApplicationApiError && e.code === "NO_PRIOR_LOAN") {
        setNoPrior(true);
      } else if (e instanceof ApplicationApiError && e.code === "ACTIVE_APPLICATION") {
        // An unfinished application is already in flight — send them to track it.
        router.push("/loan/status");
      } else if (e instanceof ApplicationApiError) {
        // NO_HEADROOM and friends carry a borrower-friendly message.
        setError(e.message);
      } else {
        setError(e instanceof Error ? e.message : "Something went wrong — please try again.");
      }
    } finally {
      setBusy(false);
    }
  };

  const limitPaise = app?.eligibleLimitPaise ?? null;

  return (
    <div className="container max-w-content py-10">
      <div className="mb-2 flex items-center gap-2 text-xs font-semibold uppercase tracking-wider text-gold-dark">
        <Zap size={14} /> Pre-approved
      </div>
      <h1 className="mb-1">Borrow again, instantly</h1>
      <p className="mb-6 text-muted">
        Your details carry over — no re-KYC. If your past repayments were on time you go straight to
        choosing an amount; otherwise we run a quick review first.
      </p>

      <div className="rounded border border-gold-soft bg-gold-50/50 p-7 text-center shadow-sm">
        {hasLiveLoan ? (
          <div className="mb-5">
            <div className="text-sm text-muted">You have a live advance</div>
            <div className="my-1 font-serif text-xl font-semibold text-navy">You can still borrow more</div>
            <div className="text-sm text-muted">
              We&apos;ll deduct your current outstanding from your 25%-of-salary limit and show your
              available amount on the next step.
            </div>
          </div>
        ) : (
          limitPaise != null && (
            <>
              <div className="text-sm text-muted">Available to borrow now</div>
              <div className="my-1 font-serif text-3xl font-bold text-navy sm:text-4xl">{paiseToINR(limitPaise)}</div>
              <div className="mb-5 text-sm text-muted">Up to 25% of your monthly salary</div>
            </>
          )
        )}
        <button onClick={borrowAgain} disabled={busy} className="btn btn-gold">
          {busy ? <Loader2 size={16} className="animate-spin" /> : <RotateCw size={16} />}
          {busy ? "Checking…" : "Borrow again"}
        </button>
        {error && (
          <div className="mt-3 flex items-center justify-center gap-1.5 text-sm text-error-600">
            <AlertTriangle size={14} /> {error}
          </div>
        )}
      </div>

      <ul className="mt-6 grid gap-3 sm:grid-cols-3">
        {[
          { icon: <Zap size={18} />, t: "No re-KYC", s: "Your verified details carry over" },
          { icon: <ShieldCheck size={18} />, t: "Clean record = instant", s: "Pre-approved → straight to disbursal" },
          { icon: <ArrowRight size={18} />, t: "Same fast flow", s: "Amount → money in your account" },
        ].map((f) => (
          <li key={f.t} className="rounded border border-line bg-white p-4 text-sm shadow-sm">
            <span className="mb-2 grid h-9 w-9 place-items-center rounded bg-navy-tint text-navy">{f.icon}</span>
            <div className="font-semibold text-ink">{f.t}</div>
            <div className="text-xs text-muted">{f.s}</div>
          </li>
        ))}
      </ul>
    </div>
  );
}
