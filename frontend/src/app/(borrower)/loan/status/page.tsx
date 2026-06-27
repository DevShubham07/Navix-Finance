"use client";

import * as React from "react";
import Link from "next/link";
import {
  Sparkles, XCircle, ArrowRight, Loader2, RefreshCw, Phone, ShieldCheck, FileClock,
} from "lucide-react";
import { LoanStatusTracker } from "@/components/borrower/loan-status-tracker";
import {
  useLiveApplication,
  useLiveEvents,
  appStatusToStage,
  canChooseAmount,
  isTerminalBad,
} from "@/lib/api/live-journey";
import { statusLabel } from "@/lib/api/applications";
import { formatDateTime } from "@/lib/utils";
import { BRAND } from "@/lib/brand";

/**
 * Live application status — driven entirely by the real backend state machine.
 * The credit/disbursement decisions are made by staff (in /staff/applications);
 * this page polls and reflects the application as it walks to ACTIVE.
 */
export default function LoanStatusPage() {
  const { appId, app, isLoading, refetch } = useLiveApplication();
  const eventsQuery = useLiveEvents(appId, app?.status);
  const stage = appStatusToStage(app);

  // No live application started in this browser yet.
  if (appId == null) {
    return (
      <div className="container max-w-content py-10">
        <div className="rounded border border-line bg-white p-8 text-center shadow-sm">
          <span className="mx-auto mb-3 grid h-14 w-14 place-items-center rounded-full bg-navy-tint text-navy">
            <FileClock size={26} />
          </span>
          <h1 className="text-2xl">No application yet</h1>
          <p className="mb-5 text-muted">Start your application to track it here in real time.</p>
          <Link href="/signup/mobile-otp" className="btn btn-gold">Start application <ArrowRight size={16} /></Link>
        </div>
      </div>
    );
  }

  const active = app?.status === "ACTIVE";
  const declined = isTerminalBad(app);
  const choose = canChooseAmount(app);
  const processing = !!app && !active && !declined && !choose;
  const events = eventsQuery.data ?? [];
  const declineNote = [...events].reverse().find((e) => e.notes)?.notes;

  return (
    <div className="container max-w-content py-10">
      <div className="mb-1 flex items-center justify-between gap-3">
        <h1 className="mb-0">Application status</h1>
        <button
          onClick={refetch}
          className="flex items-center gap-1.5 rounded border border-line px-3 py-1.5 text-xs text-muted hover:bg-grey-100 hover:text-ink"
        >
          <RefreshCw size={13} /> Refresh
        </button>
      </div>
      <p className="mb-7 text-muted">
        {app ? (
          <>
            Application #{app.id} ·{" "}
            <span className="font-semibold text-ink">{statusLabel(app.status)}</span>
          </>
        ) : (
          "Loading your application…"
        )}
      </p>

      <div className="grid gap-8 lg:grid-cols-[1fr_minmax(0,360px)]">
        <div className="rounded border border-line bg-white p-6 shadow-sm">
          {isLoading && !app ? (
            <div className="h-64 animate-pulse rounded bg-grey-100" />
          ) : (
            <LoanStatusTracker status={stage} />
          )}
        </div>

        <div className="flex flex-col gap-4">
          {choose && (
            <div className="rounded border border-success-100 bg-success-50/60 p-6 text-center shadow-sm">
              <span className="mx-auto mb-3 grid h-14 w-14 place-items-center rounded-full bg-success-50 text-success-600">
                <Sparkles size={26} />
              </span>
              <h3 className="font-serif text-lg text-navy">KYC approved</h3>
              <p className="mb-4 text-sm text-muted">Choose how much you&apos;d like to draw to continue.</p>
              <Link href="/loan/apply" className="btn btn-gold btn-block">
                Choose your amount <ArrowRight size={16} />
              </Link>
            </div>
          )}

          {processing && (
            <div className="rounded border border-line bg-white p-6 text-center shadow-sm">
              <span className="mx-auto mb-3 grid h-14 w-14 place-items-center rounded-full bg-navy-tint text-navy">
                <Loader2 size={26} className="animate-spin" />
              </span>
              <h3 className="font-serif text-lg text-navy">Under review</h3>
              <p className="text-sm text-muted">
                Our team is processing your application. This page updates automatically — currently{" "}
                <strong className="text-ink">{app && statusLabel(app.status)}</strong>.
              </p>
            </div>
          )}

          {active && (
            <div className="rounded border border-success-100 bg-success-50/60 p-6 text-center shadow-sm">
              <span className="mx-auto mb-3 grid h-14 w-14 place-items-center rounded-full bg-success-50 text-success-600">
                <ShieldCheck size={26} />
              </span>
              <h3 className="font-serif text-lg text-navy">Your advance is active</h3>
              <p className="mb-4 text-sm text-muted">Manage everything from your dashboard.</p>
              <Link href="/dashboard" className="btn btn-gold btn-block">
                Go to dashboard <ArrowRight size={16} />
              </Link>
            </div>
          )}

          {declined && (
            <div className="rounded border border-error-100 bg-error-50/50 p-6 text-center shadow-sm">
              <span className="mx-auto mb-3 grid h-14 w-14 place-items-center rounded-full bg-error-50 text-error-600">
                <XCircle size={26} />
              </span>
              <h3 className="font-serif text-lg text-navy">Not approved this time</h3>
              <p className="text-sm text-muted">{declineNote ?? "Your application did not meet current credit policy."}</p>
              <p className="mt-3 text-sm text-muted">
                Questions?{" "}
                <a href={BRAND.phoneHref} className="inline-flex items-center gap-1 font-semibold text-navy">
                  <Phone size={13} /> {BRAND.phone}
                </a>
              </p>
            </div>
          )}

          {events.length > 0 && (
            <details className="rounded border border-line bg-white p-4 shadow-sm">
              <summary className="cursor-pointer text-sm font-semibold text-navy">
                Audit trail ({events.length})
              </summary>
              <ul className="mt-2 space-y-1.5 text-xs text-muted">
                {events.map((e) => (
                  <li key={e.id} className="flex items-center justify-between gap-2 border-b border-line pb-1.5 last:border-0">
                    <span className="text-ink">
                      {e.action ?? "transition"}{" "}
                      <span className="text-muted">
                        {e.fromStatus ? `${e.fromStatus} → ` : ""}{e.toStatus ?? ""}
                      </span>
                    </span>
                    <span className="flex-shrink-0">{e.at ? formatDateTime(e.at) : ""}</span>
                  </li>
                ))}
              </ul>
            </details>
          )}
        </div>
      </div>
    </div>
  );
}
