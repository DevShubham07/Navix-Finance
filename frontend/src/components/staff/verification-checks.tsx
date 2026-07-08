"use client";

/**
 * Shared verification-checks panel: an application's automated KYC checks (PAN, email,
 * address, salary, …) with a progress tracker and a per-check maker-checker manual override.
 *
 * Extracted verbatim from the former `VerificationCards` in `pipeline/customer-review.tsx`
 * so both `CustomerReview` and the `/staff/verifications` dashboard render the exact same
 * cards. The one behavioural change vs. the old version: the inert "manual override" span +
 * instant Approve/Reject buttons are replaced by a single "Manual override" button per card
 * that opens a {@link Dialog} to capture the PASS/FAIL choice **and optional remarks**, which
 * are now threaded through `staffApi.manualVerificationDecision(..., notes)` into the audit
 * trail (the client + backend already accepted `notes`; only the call site was dropping it).
 *
 * Query keys (`staff-verifications`, `staff-verification-progress`) are intentionally
 * unchanged — other surfaces share them.
 */

import * as React from "react";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { Loader2, Bell, ShieldCheck } from "lucide-react";
import { Dialog, DialogFooter } from "@/components/ui/dialog";
import { staffApi, type StepResult, type CheckStatus } from "@/lib/api/applications";
import { humanizeCheck } from "@/lib/utils";
import { errMessage } from "@/components/staff/pipeline/hooks";
import { PermissionGate } from "@/components/staff/pipeline/actions";

/** Status pill styling per verification outcome (green PASS / amber REVIEW / red FAIL / grey PENDING). */
const CHECK_PILL: Record<CheckStatus, string> = {
  PASS: "bg-success-100 text-success-700",
  REVIEW: "bg-warning-100 text-warning-800",
  FAIL: "bg-error-100 text-error-700",
  PENDING: "bg-grey-100 text-muted",
};

/** "monthlySalaryPaise" -> "Monthly salary paise". */
function humanizeKey(key: string): string {
  return key
    .replace(/([a-z0-9])([A-Z])/g, "$1 $2")
    .replace(/[_\s]+/g, " ")
    .replace(/^./, (c) => c.toUpperCase());
}

function stringifyDerived(value: unknown): string {
  if (value == null) return "—";
  if (typeof value === "string") return value;
  if (typeof value === "number" || typeof value === "boolean") return String(value);
  try {
    return JSON.stringify(value);
  } catch {
    return String(value);
  }
}

/**
 * Verification cards for an application's automated checks (PAN, email, address, salary, …).
 * One card per {@link StepResult}: the check name, a status pill, the message, the key `derived`
 * fields the reviewer needs, and a KYC-approver "Manual override" affordance. Embedded by
 * {@link CustomerReview} and the `/staff/verifications` dashboard.
 */
export function VerificationChecksPanel({ applicationId }: { applicationId: number }) {
  const q = useQuery({
    queryKey: ["staff-verifications", applicationId],
    queryFn: () => staffApi.verifications(applicationId),
    retry: false,
  });
  // Required-step completion snapshot (Phase 3.2 progress tracker).
  const progressQ = useQuery({
    queryKey: ["staff-verification-progress", applicationId],
    queryFn: () => staffApi.verificationProgress(applicationId),
    retry: false,
  });
  // KYC-approver / admin nudge the borrower with their pending steps (Phase 3.4).
  const remind = useMutation({ mutationFn: () => staffApi.sendReminder(applicationId) });

  // The check currently open in the manual-override dialog (KYC approver / admin), or null.
  const [override, setOverride] = React.useState<StepResult | null>(null);

  const steps: StepResult[] = q.data ?? [];
  const p = progressQ.data;

  return (
    <div className="mt-5">
      <div className="mb-2 flex flex-wrap items-center justify-between gap-2">
        <div className="flex items-center gap-2">
          <span className="text-xs font-semibold uppercase tracking-wide text-muted">Verification checks</span>
          <PermissionGate permission="kyc:approve">
            <button
              onClick={() => remind.mutate()}
              disabled={remind.isPending || remind.isSuccess}
              title="Remind the borrower of their pending verification steps"
              className="inline-flex items-center gap-1 rounded border border-line px-2 py-0.5 text-[11px] font-semibold text-navy hover:bg-navy-tint disabled:opacity-50"
            >
              {remind.isPending ? <Loader2 size={11} className="animate-spin" /> : <Bell size={11} />}
              {remind.isSuccess ? (remind.data?.sent ? "Reminded" : "Nothing pending") : "Send reminder"}
            </button>
          </PermissionGate>
        </div>
        {p && (
          <span className="text-xs text-muted">
            <span className="font-semibold text-navy">{p.completed}/{p.required}</span> done · {p.percent}%
            {p.failed > 0 ? <span className="text-error-700"> · {p.failed} failed</span> : null}
            {p.pending > 0 ? <span className="text-warning-800"> · {p.pending} pending</span> : null}
          </span>
        )}
      </div>
      {p && (
        <div className="mb-3 h-1.5 w-full overflow-hidden rounded-full bg-grey-200">
          <div className="h-full rounded-full bg-success-600 transition-all" style={{ width: `${p.percent}%` }} />
        </div>
      )}
      {q.isLoading ? (
        <p className="text-sm text-muted">Loading…</p>
      ) : q.error ? (
        <p className="text-sm text-error-700">{errMessage(q.error)}</p>
      ) : steps.length === 0 ? (
        <p className="text-sm text-muted">No verification checks recorded yet.</p>
      ) : (
        <div className="grid gap-2 sm:grid-cols-2">
          {steps.map((s, i) => {
            const entries = Object.entries(s.derived ?? {});
            return (
              <div key={`${s.checkType}-${i}`} className="rounded border border-line bg-grey-50 p-3">
                <div className="flex items-center justify-between gap-2">
                  <span className="text-sm font-semibold text-navy">{humanizeCheck(s.checkType)}</span>
                  <span className={`rounded-full px-2 py-0.5 text-xs font-semibold ${CHECK_PILL[s.status]}`}>
                    {s.status}
                  </span>
                </div>
                {s.message ? <p className="mt-1 text-xs text-ink/90">{s.message}</p> : null}
                {entries.length > 0 && (
                  <dl className="mt-2 space-y-0.5 text-xs">
                    {entries.map(([k, v]) => (
                      <div key={k} className="flex items-start justify-between gap-3">
                        <dt className="text-muted">{humanizeKey(k)}</dt>
                        <dd className="break-all text-right text-ink">{stringifyDerived(v)}</dd>
                      </div>
                    ))}
                  </dl>
                )}
                <PermissionGate permission="kyc:approve">
                  <div className="mt-2 border-t border-line pt-2">
                    <button
                      onClick={() => setOverride(s)}
                      className="inline-flex items-center gap-1 rounded border border-line px-2 py-0.5 text-[11px] font-semibold text-navy hover:bg-navy-tint"
                    >
                      <ShieldCheck size={12} /> Manual override
                    </button>
                  </div>
                </PermissionGate>
              </div>
            );
          })}
        </div>
      )}
      {override && (
        <OverrideDialog applicationId={applicationId} step={override} onClose={() => setOverride(null)} />
      )}
    </div>
  );
}

/**
 * Maker-checker manual override for one verification check (KYC approver / admin): shows the
 * check's current status + message, captures a PASS/FAIL decision and optional remarks, and
 * threads the remarks through `manualVerificationDecision(..., notes)` into the audit trail.
 */
function OverrideDialog({
  applicationId,
  step,
  onClose,
}: {
  applicationId: number;
  step: StepResult;
  onClose: () => void;
}) {
  const qc = useQueryClient();
  const [decision, setDecision] = React.useState<boolean | null>(null);
  const [notes, setNotes] = React.useState("");
  const titleId = `override-title-${applicationId}-${step.checkType}`;

  const decide = useMutation({
    mutationFn: (d: boolean) =>
      staffApi.manualVerificationDecision(applicationId, step.checkType, d, notes.trim() || undefined),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["staff-verifications", applicationId] });
      qc.invalidateQueries({ queryKey: ["staff-verification-progress", applicationId] });
      // The dashboard groups applications off this overview query; refresh it so a card's
      // failed/passed counts update immediately after an override (not on the 15s poll).
      qc.invalidateQueries({ queryKey: ["staff-verif-overview"] });
      onClose();
    },
  });

  return (
    <Dialog open onClose={onClose} className="!max-w-md" aria-labelledby={titleId}>
      <div className="mb-3">
        <h3 id={titleId} className="font-serif text-lg text-navy">
          Manual override
        </h3>
        <p className="mt-0.5 text-sm text-muted">{humanizeCheck(step.checkType)}</p>
      </div>

      <div className="mb-4 rounded border border-line bg-grey-50 p-3">
        <div className="flex items-center justify-between gap-2">
          <span className="text-xs font-semibold uppercase tracking-wide text-muted">Current status</span>
          <span className={`rounded-full px-2 py-0.5 text-xs font-semibold ${CHECK_PILL[step.status]}`}>
            {step.status}
          </span>
        </div>
        {step.message ? <p className="mt-1.5 text-xs text-ink/90">{step.message}</p> : null}
      </div>

      <div className="mb-4">
        <span className="mb-1.5 block text-xs font-semibold uppercase tracking-wide text-muted">Decision</span>
        <div className="flex gap-2">
          <button
            type="button"
            onClick={() => setDecision(true)}
            className={`flex-1 rounded border px-3 py-1.5 text-sm font-semibold transition ${
              decision === true
                ? "border-success-600 bg-success-50 text-success-700"
                : "border-line text-muted hover:border-success-600 hover:text-success-700"
            }`}
          >
            Approve (PASS)
          </button>
          <button
            type="button"
            onClick={() => setDecision(false)}
            className={`flex-1 rounded border px-3 py-1.5 text-sm font-semibold transition ${
              decision === false
                ? "border-error-600 bg-error-50 text-error-700"
                : "border-line text-muted hover:border-error-600 hover:text-error-700"
            }`}
          >
            Reject (FAIL)
          </button>
        </div>
      </div>

      <div className="mb-1">
        <label htmlFor={`${titleId}-notes`} className="mb-1.5 block text-xs font-semibold uppercase tracking-wide text-muted">
          Remarks <span className="font-normal normal-case">(optional — recorded in the audit trail)</span>
        </label>
        <textarea
          id={`${titleId}-notes`}
          value={notes}
          onChange={(e) => setNotes(e.target.value)}
          rows={3}
          placeholder="Why is this being overridden?"
          className="w-full rounded border border-line px-3 py-2 text-sm"
        />
      </div>

      {decide.error && <p className="mt-1 text-xs text-error-700">{errMessage(decide.error)}</p>}

      <DialogFooter>
        <button
          type="button"
          onClick={onClose}
          disabled={decide.isPending}
          className="btn btn-sm btn-outline disabled:opacity-50"
        >
          Cancel
        </button>
        <button
          type="button"
          onClick={() => decision != null && decide.mutate(decision)}
          disabled={decision == null || decide.isPending}
          className="btn btn-sm btn-navy disabled:opacity-50"
        >
          {decide.isPending ? <Loader2 size={14} className="animate-spin" /> : null} Confirm
        </button>
      </DialogFooter>
    </Dialog>
  );
}
