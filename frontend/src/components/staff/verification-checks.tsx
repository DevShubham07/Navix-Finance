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
 * Query keys: the checks are read under the single key `["verifications", id]`, shared by Customer 360's
 * tabs, the application/stage dialogs and the force-disbursement action (one `staffApi.verifications(id)`
 * payload, one fetch). Every write here invalidates it, plus the application-info dialog's
 * `["customer-verifications", id]`. `staff-verification-progress` is unchanged.
 */

import * as React from "react";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { Loader2, Bell, ShieldCheck, RotateCcw, Link2 } from "lucide-react";
import { Dialog, DialogFooter } from "@/components/ui/dialog";
import { staffApi, paiseToINR, type StepResult, type CheckStatus } from "@/lib/api/applications";
import { humanizeCheck, formatDateTime } from "@/lib/utils";
import { AADHAAR_PAN_LINK, withAadhaarPanLink } from "@/lib/staff/aadhaar-pan-link";
import {
  isRetryOutcomeUnknown,
  isRetryStillRunning,
  providerAttribution,
  retryGuardKey,
} from "@/lib/staff/verification-dashboard";
import { errMessage } from "@/components/staff/pipeline/hooks";
import { PermissionGate } from "@/components/staff/pipeline/actions";
import { ResumeLinkDialog } from "@/components/staff/resume-link-dialog";
import { ConfirmDialog, EmptyState, ErrorState, Skeleton, StatusBadge, toast } from "@/components/ui";

/**
 * A placeholder card's status is `NOT_RUN` — deliberately distinct from the real `PENDING` status
 * (which means "ran, awaiting a result") so a reviewer can't mistake "never attempted" for
 * "in flight". `StepResult.status` (applications.ts) is intentionally left alone since it mirrors
 * the backend's real `CheckStatus` enum; this widened type only exists for this panel's display list.
 */
type DisplayStatus = CheckStatus | "NOT_RUN";
type DisplayStep = Omit<StepResult, "status"> & { status: DisplayStatus };

/**
 * The {@link StatusBadge} kind for a check's status pill. EMPLOYMENT (the EPFO/UAN lookup) gates
 * nothing, so it renders as `advisory` and its PASS/FAIL stay out of the gating green/red; every
 * other check is a real `verification`. A synthetic `NOT_RUN` placeholder is in neither enum and
 * falls through to the badge's neutral tone, reading "Not Run".
 */
function checkKind(checkType: string): "advisory" | "verification" {
  return checkType === "EMPLOYMENT" ? "advisory" : "verification";
}

/**
 * Failure keys {@link Provenance} renders in prose, hidden from the generic `derived` table below it
 * so each fact appears once. Without this the reviewer got "Provider error: true" and "Provider error
 * code: HTTP_500" as raw table rows — the same facts, in the least readable place on the card.
 */
const DERIVED_RENDERED_BY_PROVENANCE: ReadonlySet<string> = new Set([
  "providerError", "providerErrorCode", "providerEndpoint", "providerCode", "providerDetail",
]);

/**
 * Mirrors the backend allow-list in `ApplicationVerificationService.retryExternalCheck` exactly.
 * Gates the "Retry API" button only — ESIGN and AADHAAR are never in this list (the backend refuses
 * them: a retry re-runs a provider call, but eSign is a borrower-initiated legal act and DigiLocker
 * needs the borrower's own consent redirect) even though they do get a synthetic placeholder card,
 * see {@link PLACEHOLDER_CHECKS}.
 */
const RETRYABLE_CHECKS: readonly string[] = ["PAN", "EMAIL", "ADDRESS", "BUREAU", "EMPLOYMENT", "PENNY_DROP", "SELFIE"];

/**
 * Checks that get a synthetic `NOT_RUN` placeholder card when `summary()` has no row for them yet —
 * every retryable check (so Retry API is always reachable) plus ESIGN and AADHAAR, whose most
 * important case is exactly a never-run/abandoned attempt (customer closed the tab on the sanction
 * letter, or never opened DigiLocker) and which would otherwise have no card at all to hang a
 * "Send link to customer" button on. ESIGN/AADHAAR placeholders must not offer Retry — see
 * {@link RETRYABLE_CHECKS}.
 */
const PLACEHOLDER_CHECKS: readonly string[] = [...RETRYABLE_CHECKS, "ESIGN", "AADHAAR"];

/**
 * Checks for which "Send link to customer" (see `resume-link-dialog.tsx`) makes sense — the
 * borrower-only Phase-3 steps plus the bureau security question. Mirrors the backend's
 * `VerificationOutreachService` check→step map exactly; the backend still refuses (e.g.
 * `STEP_LINK_NOT_ELIGIBLE`, surfaced via `errMessage`, no special-casing here) whenever the
 * application/check isn't actually eligible (wrong status, no pending bureau question, …).
 */
const LINKABLE_CHECKS: readonly string[] = ["ESIGN", "AADHAAR", "DIGILOCKER", "SELFIE", "ADDRESS", "BUREAU"];

/**
 * Whether "Send link to customer" is offered on this card — the same eligibility the backend's
 * `VerificationOutreachService` enforces, applied up front so the button never opens a dialog that
 * can only say `STEP_LINK_NOT_ELIGIBLE`: the offer-journey steps exist only on a SANCTIONED file
 * (a KYC_PENDING file's eSign/Aadhaar placeholder cards must not offer them), and the bureau link
 * only while the file still awaits review *and* the bureau is actually holding a security question.
 */
function canSendLink(step: DisplayStep, applicationStatus: string | undefined): boolean {
  if (step.status === "PASS" || !LINKABLE_CHECKS.includes(step.checkType)) return false;
  if (step.checkType === "BUREAU") {
    return applicationStatus === "KYC_PENDING" && step.derived?.bureauChallenge === true;
  }
  return applicationStatus === "SANCTIONED";
}

/** "monthlySalaryPaise" -> "Monthly salary" (the trailing "Paise" is stripped — see {@link isPaiseKey}). */
function humanizeKey(key: string): string {
  return key
    .replace(/Paise$/, "")
    .replace(/([a-z0-9])([A-Z])/g, "$1 $2")
    .replace(/[_\s]+/g, " ")
    .replace(/^./, (c) => c.toUpperCase());
}

/** Keys ending in "Paise" (e.g. monthlySalaryPaise, eligibleLimitPaise) carry raw integer paise. */
function isPaiseKey(key: string): boolean {
  return /Paise$/.test(key);
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
export function VerificationChecksPanel({ applicationId, aadhaar }: {
  applicationId: number;
  /** Full Aadhaar from the customer profile, for the derived Aadhaar–PAN linkage card (masked otherwise). */
  aadhaar?: string | null;
}) {
  // Same key and queryFn as Customer 360's tabs (customer-tabs.tsx): identical endpoint, identical
  // StepResult[] shape, no `select` — so one cache entry serves both.
  const q = useQuery({
    queryKey: ["verifications", applicationId],
    queryFn: () => staffApi.verifications(applicationId),
    retry: false,
  });
  // Required-step completion snapshot (Phase 3.2 progress tracker).
  const progressQ = useQuery({
    queryKey: ["staff-verification-progress", applicationId],
    queryFn: () => staffApi.verificationProgress(applicationId),
    retry: false,
  });
  // The application's lifecycle status decides which cards may offer "Send link to customer" (see
  // canSendLink). Same query key as the application dialogs, so it is usually already in the cache.
  const appQ = useQuery({
    queryKey: ["staff-application", applicationId],
    queryFn: () => staffApi.get(applicationId),
    retry: false,
  });
  // KYC-approver / admin nudge the borrower with their pending steps (Phase 3.4). A real in-app +
  // SMS + email + WhatsApp send with no cooldown, so it sits behind a confirm. The endpoint only
  // publishes an event, so the toast says "queued", never "sent"; `sent: false` means nothing was
  // pending and nothing was published.
  const [confirmRemind, setConfirmRemind] = React.useState(false);
  const remind = useMutation({
    mutationFn: () => staffApi.sendReminder(applicationId),
    onSuccess: (r) => {
      setConfirmRemind(false);
      if (r.sent) toast.success("Reminder queued");
      else toast.info("Nothing pending — no reminder sent");
    },
  });

  // The check currently open in the manual-override dialog (KYC approver / admin), or null.
  const [override, setOverride] = React.useState<DisplayStep | null>(null);
  const [retry, setRetry] = React.useState<DisplayStep | null>(null);
  const [resumeLink, setResumeLink] = React.useState<DisplayStep | null>(null);
  // Retry in-flight guard: check type -> its row's `checkedAt` (the row's updatedAt) when a retry was
  // sent. A retry is a billable provider call, and the client stops waiting at 120s without aborting
  // it — so once the outcome is unknown, Retry stays off for that check until its row is written
  // again. Held in component state on purpose: closing and reopening the panel clears it.
  // Keyed by application + check type (retryGuardKey): the panel can be re-pointed at another
  // application without unmounting, and one file's guard must never touch another file's button.
  const [retryInFlight, setRetryInFlight] = React.useState<Record<string, string | null>>({});
  const holdRetry = (key: string, baseline: string | null) =>
    setRetryInFlight((prev) => ({ ...prev, [key]: baseline }));
  const releaseRetry = (key: string) =>
    setRetryInFlight((prev) => {
      if (!(key in prev)) return prev;
      const next = { ...prev };
      delete next[key];
      return next;
    });
  const refreshChecks = () => {
    void q.refetch();
    void progressQ.refetch();
  };

  // Real rows first, then a synthetic NOT_RUN placeholder for every check in PLACEHOLDER_CHECKS with
  // no row yet — otherwise a never-run check (PENNY_DROP is the live case, ESIGN/AADHAAR the
  // abandoned-attempt case) has no card and so no way to override, retry, or send a link for it,
  // even though the backend endpoints upsert and work with no prior row.
  // ESIGN/AADHAAR placeholders only once the file is SANCTIONED: before that the offer journey has
  // not started, so "NOT RUN" would read as outstanding work on a file still under credit review.
  const applicationStatus = appQ.data?.status;
  const steps: DisplayStep[] = React.useMemo(() => {
    const real = withAadhaarPanLink(q.data ?? [], aadhaar);
    const seen = new Set(real.map((s) => s.checkType));
    const wanted = PLACEHOLDER_CHECKS.filter(
      (c) => RETRYABLE_CHECKS.includes(c) || applicationStatus === "SANCTIONED",
    );
    const placeholders: DisplayStep[] = wanted.filter((c) => !seen.has(c)).map((checkType) => ({
      checkType,
      status: "NOT_RUN",
      message: "Not run on this application",
      derived: {},
    }));
    return [...real, ...placeholders];
  }, [q.data, applicationStatus, aadhaar]);
  const p = progressQ.data;
  const retryLive = retry ? steps.find((s) => s.checkType === retry.checkType) ?? retry : null;

  return (
    <div className="mt-5">
      <div className="mb-2 flex flex-wrap items-center justify-between gap-2">
        <div className="flex items-center gap-2">
          <span className="text-xs font-semibold uppercase tracking-wide text-muted">Verification checks</span>
          <PermissionGate permission="kyc:approve">
            <button
              onClick={() => {
                // Clear a previous failed attempt so the confirm opens without a stale error.
                remind.reset();
                setConfirmRemind(true);
              }}
              disabled={remind.isPending || remind.isSuccess}
              title="Remind the borrower of their pending verification steps"
              className="inline-flex items-center gap-1 rounded border border-line px-2 py-0.5 text-[8.8px] font-semibold text-navy hover:bg-navy-tint disabled:opacity-50"
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
        <div className="grid gap-2 sm:grid-cols-2">
          {Array.from({ length: 4 }).map((_, i) => (
            <Skeleton key={i} variant="row" />
          ))}
        </div>
      ) : q.error ? (
        <ErrorState error={q.error} onRetry={() => void q.refetch()} className="py-4" />
      ) : steps.length === 0 ? (
        <EmptyState title="No verification checks recorded yet." className="py-4" />
      ) : (
        <div className="grid gap-2 sm:grid-cols-2">
          {steps.map((s, i) => {
            const entries = Object.entries(s.derived ?? {})
              .filter(([k]) => !DERIVED_RENDERED_BY_PROVENANCE.has(k));
            const retryRunning = isRetryStillRunning(
              retryInFlight,
              retryGuardKey(applicationId, s.checkType),
              s.checkedAt,
            );
            return (
              <div key={`${s.checkType}-${i}`} className="rounded border border-line bg-grey-50 p-3">
                <div className="flex items-center justify-between gap-2">
                  <span className="text-sm font-semibold text-navy">{humanizeCheck(s.checkType)}</span>
                  <StatusBadge kind={checkKind(s.checkType)} value={s.status} />
                </div>
                {s.message ? <p className="mt-1 text-xs text-ink/90">{s.message}</p> : null}
                <Provenance step={s} />
                {entries.length > 0 && (
                  <dl className="mt-2 space-y-0.5 text-xs">
                    {entries.map(([k, v]) => (
                      <div key={k} className="flex items-start justify-between gap-3">
                        <dt className="text-muted">{humanizeKey(k)}</dt>
                        <dd className="break-all text-right text-ink">
                          {isPaiseKey(k) && typeof v === "number" && Number.isFinite(v)
                            ? paiseToINR(v)
                            : stringifyDerived(v)}
                        </dd>
                      </div>
                    ))}
                  </dl>
                )}
                {/* The linkage card is derived from the PAN row — override or re-run PAN instead. */}
                {s.checkType !== AADHAAR_PAN_LINK && <PermissionGate permission="kyc:approve">
                  <div className="mt-2 flex flex-wrap gap-2 border-t border-line pt-2">
                    <button
                      onClick={() => setOverride(s)}
                      className="inline-flex items-center gap-1 rounded border border-line px-2 py-0.5 text-[8.8px] font-semibold text-navy hover:bg-navy-tint"
                    >
                      <ShieldCheck size={12} /> Manual override
                    </button>
                    {canSendLink(s, applicationStatus) && (
                      <button
                        onClick={() => setResumeLink(s)}
                        className="inline-flex items-center gap-1 rounded border border-line px-2 py-0.5 text-[8.8px] font-semibold text-navy hover:bg-navy-tint"
                      >
                        <Link2 size={12} /> Send link to customer
                      </button>
                    )}
                  </div>
                </PermissionGate>}
                {RETRYABLE_CHECKS.includes(s.checkType) && (
                  <PermissionGate permission="verification:retry">
                    <div className="mt-2">
                      <button
                        onClick={() => setRetry(s)}
                        disabled={retryRunning}
                        className="inline-flex items-center gap-1 rounded border border-line px-2 py-0.5 text-[8.8px] font-semibold text-navy hover:bg-navy-tint disabled:cursor-not-allowed disabled:opacity-50"
                      >
                        <RotateCcw size={12} /> Retry API
                      </button>
                      {retryRunning && <StillRunningNote onRefresh={refreshChecks} />}
                    </div>
                  </PermissionGate>
                )}
              </div>
            );
          })}
        </div>
      )}
      {override && (
        <OverrideDialog applicationId={applicationId} step={override} onClose={() => setOverride(null)} />
      )}
      {retry && retryLive && (
        <RetryDialog
          applicationId={applicationId}
          step={retry}
          currentCheckedAt={retryLive.checkedAt ?? null}
          stillRunning={isRetryStillRunning(
            retryInFlight,
            retryGuardKey(applicationId, retry.checkType),
            retryLive.checkedAt,
          )}
          onSent={(baseline) => holdRetry(retryGuardKey(applicationId, retry.checkType), baseline)}
          onAnswered={() => releaseRetry(retryGuardKey(applicationId, retry.checkType))}
          onRefresh={refreshChecks}
          onClose={() => setRetry(null)}
        />
      )}
      {resumeLink && (
        <ResumeLinkDialog
          applicationId={applicationId}
          checkType={resumeLink.checkType}
          onClose={() => {
            setResumeLink(null);
          }}
        />
      )}
      <ConfirmDialog
        open={confirmRemind}
        onClose={() => setConfirmRemind(false)}
        onConfirm={() => remind.mutate()}
        busy={remind.isPending}
        title="Send the borrower a reminder?"
        confirmLabel="Send reminder"
        body={
          <>
            <p className="m-0">
              The borrower is sent their pending verification steps by in-app notification, SMS, email
              and WhatsApp. There is no cooldown — every confirm sends again.
            </p>
            {remind.error ? (
              <p className="m-0 mt-2 text-xs text-error-700">{errMessage(remind.error)}</p>
            ) : null}
          </>
        }
      />
    </div>
  );
}

/** The `derived` keys {@link providerLine} renders itself — see {@link DERIVED_RENDERED_BY_PROVENANCE}. */
function str(derived: Record<string, unknown> | undefined, key: string): string | null {
  const value = derived?.[key];
  return typeof value === "string" && value.trim() !== "" ? value : null;
}

/**
 * Which API answered this check, and when — or which one failed to.
 *
 * <p>Shared with the Verifications panel's per-check rows so every screen says the same thing about a
 * provider. The branch that matters is the failure one: a check the vendor could not run is stored
 * with **`provider` null** and a status of REVIEW, so before this existed both screens showed a bare
 * amber "Review" with no way to tell a genuine judgement call from an API that fell over. The
 * endpoint, the vendor's own code and its message are read from `derived`, where
 * `ApplicationVerificationService.providerUnavailable` now keeps them (already redacted — bodies and
 * request values never leave `provider_api_execution`). Rows written before that keep only
 * `providerErrorCode`, which is why the code falls back to it.
 */
export function providerLine(
  step: {
    provider?: string | null;
    checkedAt?: string | null;
    derived?: Record<string, unknown>;
  },
  /** `labelled` words the answering party ("Answered by DIGITAP") — the checks panel's form. */
  opts?: { labelled?: boolean },
): string | null {
  const bits: string[] = [];
  if (step.derived?.providerError === true) {
    bits.push("Provider unavailable");
    const endpoint = str(step.derived, "providerEndpoint");
    if (endpoint) bits.push(endpoint);
    const code = str(step.derived, "providerCode") ?? str(step.derived, "providerErrorCode");
    if (code) bits.push(code);
  } else if (step.provider) {
    bits.push(
      opts?.labelled
        ? providerAttribution(step.provider) ?? step.provider
        : step.provider === "MANUAL"
          ? "Manual override"
          : step.provider,
    );
  }
  if (step.checkedAt) bits.push(formatDateTime(step.checkedAt));
  return bits.length > 0 ? bits.join(" · ") : null;
}

/**
 * Who produced this result, when, and against which provider transaction.
 *
 * <p>The step payload used to carry only the check type, status, message and derived blob, so a
 * reviewer could read what a check concluded but not whether a vendor or a colleague concluded it,
 * how long ago, or which provider call to quote when disputing it. `provider` is "MANUAL" on a staff
 * override, which is the distinction that matters most here and was previously only inferable from
 * the message text.
 */
function Provenance({ step }: { step: DisplayStep }) {
  const bits: string[] = [];
  const line = providerLine(step, { labelled: true });
  if (line) bits.push(line);
  if (typeof step.nameMatch === "number") bits.push(`name match ${Math.round(step.nameMatch * 100)}%`);
  if (typeof step.score === "number") bits.push(`score ${step.score}`);
  const detail = str(step.derived, "providerDetail");
  if (detail) bits.push(detail);
  if (step.reopenedAt) bits.push(`reopened for the customer ${formatDateTime(step.reopenedAt)}`);
  if (bits.length === 0) return null;
  return (
    <p className="mt-1 text-[8.8px] text-muted">
      {bits.join(" · ")}
      {step.providerTxnId ? (
        <>
          {" · "}
          <span className="font-mono break-all" title="Provider transaction id">{step.providerTxnId}</span>
        </>
      ) : null}
    </p>
  );
}

/** The retry guard's explanation, with the refetch it asks for one click away. */
function StillRunningNote({ onRefresh }: { onRefresh: () => void }) {
  return (
    <p className="mt-1 text-[8.8px] text-warning-800">
      Still running at the provider — refresh in a minute.{" "}
      <button type="button" onClick={onRefresh} className="font-semibold underline hover:text-navy">
        Refresh
      </button>
    </p>
  );
}

function RetryDialog({
  applicationId,
  step,
  currentCheckedAt,
  stillRunning,
  onSent,
  onAnswered,
  onRefresh,
  onClose,
}: {
  applicationId: number;
  step: DisplayStep;
  /** The check's row timestamp right now — the guard's baseline when Retry is pressed. */
  currentCheckedAt: string | null;
  /** A retry for this check went out and its outcome is not known yet. */
  stillRunning: boolean;
  onSent: (baseline: string | null) => void;
  /** The server answered (either way), so the guard can lift. */
  onAnswered: () => void;
  onRefresh: () => void;
  onClose: () => void;
}) {
  const qc = useQueryClient();
  const fields = retryFields(step.checkType);
  const [input, setInput] = React.useState<Record<string, string>>({});
  const missingRequired = fields.some((field) => field.required && !input[field.key]?.trim());
  // These callbacks live on useMutation (not on mutate()), so they still run if the dialog is
  // closed mid-call — the guard is the panel's, and it must hear how the call ended.
  const retry = useMutation({
    mutationFn: () => staffApi.retryVerification(
      applicationId,
      step.checkType,
      Object.fromEntries(Object.entries(input).filter(([, value]) => value.trim() !== "")),
    ),
    onMutate: () => onSent(currentCheckedAt),
    onError: (error) => {
      // A 120s give-up or a gateway timeout leaves the provider call possibly running: keep the
      // guard. Any other error is the server's answer, and retrying after it is fine.
      if (!isRetryOutcomeUnknown(error)) onAnswered();
    },
    onSuccess: () => {
      onAnswered();
      qc.invalidateQueries({ queryKey: ["verifications", applicationId] });
      qc.invalidateQueries({ queryKey: ["staff-verification-progress", applicationId] });
      qc.invalidateQueries({ queryKey: ["staff-verif-overview"] });
      // the pop-up's Verifications tab may read the same rows under its own key.
      qc.invalidateQueries({ queryKey: ["customer-verifications", applicationId] });
      toast.success(`${humanizeCheck(step.checkType)} check re-run`);
      onClose();
    },
  });
  return <Dialog open onClose={onClose} className="!max-w-md" aria-labelledby="retry-api-title">
    <h3 id="retry-api-title" className="font-serif text-lg text-navy">Retry {humanizeCheck(step.checkType)}</h3>
    <p className="mt-1 text-sm text-muted">Saved customer details are used where available. Add only the fields required for this retry. Timeout: 120 seconds.</p>
    <div className="mt-4 space-y-3">
      {fields.length === 0 ? <p className="text-sm text-muted">No extra information is needed.</p> : fields.map((field) => (
        <label key={field.key} className="block text-xs font-semibold text-navy">
          {field.label}{field.required ? " *" : ""}
          <input
            type={field.type ?? "text"}
            value={input[field.key] ?? ""}
            onChange={(event) => setInput((current) => ({ ...current, [field.key]: event.target.value }))}
            placeholder={field.placeholder}
            className="mt-1 w-full rounded border border-line px-3 py-2 text-sm font-normal text-ink"
            aria-label={field.label}
          />
          {field.help ? <span className="mt-1 block font-normal text-muted">{field.help}</span> : null}
        </label>
      ))}
    </div>
    {retry.error && <p className="mt-2 text-xs text-error-700">{errMessage(retry.error)}</p>}
    {stillRunning && !retry.isPending && <StillRunningNote onRefresh={onRefresh} />}
    <DialogFooter><button className="btn btn-sm btn-outline" onClick={onClose}>Cancel</button><button className="btn btn-sm btn-navy" disabled={retry.isPending || missingRequired || stillRunning} onClick={() => retry.mutate()}>{retry.isPending ? <Loader2 size={14} className="animate-spin" /> : <RotateCcw size={14} />} Retry</button></DialogFooter>
  </Dialog>;
}

type RetryField = { key: string; label: string; placeholder?: string; help?: string; type?: "text" | "number"; required?: boolean };

function retryFields(checkType: string): RetryField[] {
  switch (checkType) {
    case "PAN":
      return [{ key: "pan", label: "PAN", placeholder: "Uses saved PAN when blank" }];
    case "EMAIL":
      return [{ key: "email", label: "Email address", placeholder: "Uses saved email when blank", type: "text" }];
    case "ADDRESS":
      return [
        { key: "latitude", label: "Latitude", placeholder: "e.g. 28.6139", type: "number", required: true },
        { key: "longitude", label: "Longitude", placeholder: "e.g. 77.2090", type: "number", required: true },
      ];
    case "BUREAU":
      return [{ key: "otp", label: "Consent OTP", placeholder: "Enter the borrower’s fresh 6-digit consent OTP", required: true, help: "A fresh OTP is required by Digitap. Do not reuse a previous code." }];
    case "EMPLOYMENT":
      // The backend derives every EMPLOYMENT input from the stored profile — no extra fields to collect.
      return [];
    case "PENNY_DROP":
      return [
        { key: "accountNumber", label: "Account number", placeholder: "Uses saved account when blank" },
        { key: "ifsc", label: "IFSC", placeholder: "Uses saved IFSC when blank" },
      ];
    case "SELFIE":
      return [{ key: "selfieObjectKey", label: "Selfie storage key", placeholder: "applications/…/selfie.jpg", required: true }];
    default:
      return [];
  }
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
  step: DisplayStep;
  onClose: () => void;
}) {
  const qc = useQueryClient();
  const [decision, setDecision] = React.useState<boolean | null>(null);
  const [notes, setNotes] = React.useState("");
  const titleId = `override-title-${applicationId}-${step.checkType}`;

  const decide = useMutation({
    mutationFn: (d: boolean) =>
      staffApi.manualVerificationDecision(applicationId, step.checkType, d, notes.trim() || undefined),
    onSuccess: (_result, d) => {
      qc.invalidateQueries({ queryKey: ["verifications", applicationId] });
      qc.invalidateQueries({ queryKey: ["staff-verification-progress", applicationId] });
      // The dashboard groups applications off this overview query; refresh it so a card's
      // failed/passed counts update immediately after an override (not on the 15s poll).
      qc.invalidateQueries({ queryKey: ["staff-verif-overview"] });
      // the pop-up's Verifications tab may read the same rows under its own key.
      qc.invalidateQueries({ queryKey: ["customer-verifications", applicationId] });
      toast.success(`${humanizeCheck(step.checkType)} overridden to ${d ? "PASS" : "FAIL"}`);
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
          <StatusBadge kind={checkKind(step.checkType)} value={step.status} />
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
