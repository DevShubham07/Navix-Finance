"use client";

import * as React from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useQueryClient } from "@tanstack/react-query";
import { RotateCw, Zap, ShieldCheck, ArrowRight, Loader2, AlertTriangle } from "lucide-react";
import { useMounted } from "@/hooks/use-mounted";
import { useLiveApplication, writeStoredAppId, routeForBlockedStart } from "@/lib/api/live-journey";
import { borrowerApi, paiseToINR, ApplicationApiError, type ApplicationView } from "@/lib/api/applications";
import { formatApiError } from "@/lib/api/errors";
import { ActiveLoanNotice } from "@/components/borrower/active-loan-notice";

/**
 * Returning-borrower reborrow (live). A repeat borrower in good standing is pre-approved — one tap
 * reuses their saved KYC profile and drops them on the amount page (no signup). A borrower with past
 * delinquency is instead routed to a KYC re-review (we show their status page). Calls the real
 * `POST /api/borrower/applications/reborrow`; the backend decides the routing from loan history.
 */
export default function ReloanPage() {
  const router = useRouter();
  const mounted = useMounted();
  const queryClient = useQueryClient();
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

  // One advance at a time: a borrower who holds a live loan must repay it before borrowing again.
  const hasLiveLoan =
    app?.status === "ACTIVE" || app?.status === "OVERDUE" || app?.status === "DEFAULTED";

  if (hasLiveLoan && app) {
    return <ActiveLoanNotice app={app} />;
  }

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
      // Seed the shared application list optimistically so the header/account-menu "New loan" CTA
      // hides immediately (the new app is now in-flight) with no flash, then invalidate to reconcile
      // with server truth — together this also points the status page at this brand-new application
      // rather than the prior, closed one.
      queryClient.setQueryData<ApplicationView[]>(["my-apps"], (old) =>
        old ? [result, ...old.filter((a) => a.id !== result.id)] : [result],
      );
      queryClient.invalidateQueries({ queryKey: ["my-apps"] });
      // Both forks first confirm their current salary (day + payslips) on a dedicated step; that step
      // then decides the next hop from the live status — PRE_APPROVED → choose amount, REVIEW_PENDING →
      // track the KYC re-review. So route both to /loan/salary here.
      router.push("/loan/salary");
    } catch (e) {
      if (e instanceof ApplicationApiError && e.code === "NO_PRIOR_LOAN") {
        setNoPrior(true);
      } else {
        // A live-app block (an application/loan already in flight) → the matching existing screen;
        // any other backend error surfaces inline.
        const dest = e instanceof ApplicationApiError ? routeForBlockedStart(e.code) : null;
        if (dest) {
          router.push(dest);
        } else {
          setError(formatApiError(e, "Something went wrong — please try again."));
        }
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
        Your KYC carries over from before — just re-confirm your current salary, pick an amount, and the
        money&apos;s on its way. If you&apos;ve ever missed a repayment, a quick approver review runs first.
      </p>

      <div className="rounded border border-gold-soft bg-gold-50/50 p-7 text-center shadow-sm">
        {limitPaise != null && (
          <>
            <div className="text-sm text-muted">Available to borrow now</div>
            <div className="my-1 font-serif text-3xl font-bold text-navy sm:text-4xl">{paiseToINR(limitPaise)}</div>
            <div className="mb-5 text-sm text-muted">Instant loan up to ₹10,00,000</div>
          </>
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
