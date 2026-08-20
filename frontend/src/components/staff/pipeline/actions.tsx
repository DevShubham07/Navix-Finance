"use client";

/**
 * Per-stage maker-checker action clusters + the permission gates they render behind.
 *
 * Each cluster performs exactly one state-machine transition via `staffApi` and
 * invalidates the relevant queues on success ({@link useRefreshAfterAction}). The
 * backend is the source of truth (requireRole + SoD via the event trail); these
 * gates only stop the UI offering a step the signed-in role can't take. Moved
 * verbatim from the former `live-pipeline.tsx` god-file — logic unchanged.
 */

import * as React from "react";
import { useQuery, useMutation } from "@tanstack/react-query";
import { Check, X, Loader2 } from "lucide-react";
import { Input, Select } from "@/components/ui";
import { hasPermission, type Permission } from "@/lib/auth/rbac";
import { staffApi, type ApplicationView } from "@/lib/api/applications";
import { SanctionDialog } from "@/components/staff/sanction-dialog";
import { useStaffMe, useRefreshAfterAction, errMessage } from "@/components/staff/pipeline/hooks";

function ApproveRejectButtons({
  onApprove,
  onReject,
  pending,
  approveLabel = "Approve",
  rejectLabel = "Reject",
}: {
  onApprove: () => void;
  onReject: () => void;
  pending: boolean;
  approveLabel?: string;
  rejectLabel?: string;
}) {
  return (
    <>
      <button
        onClick={onApprove}
        disabled={pending}
        className="btn btn-sm bg-success-600 border-success-600 text-white hover:bg-success-700 disabled:opacity-50"
      >
        {pending ? <Loader2 size={14} className="animate-spin" /> : <Check size={14} />} {approveLabel}
      </button>
      <button
        onClick={onReject}
        disabled={pending}
        className="btn btn-sm bg-error-600 border-error-600 text-white hover:bg-error-700 disabled:opacity-50"
      >
        <X size={14} /> {rejectLabel}
      </button>
    </>
  );
}

function ActionError({ error }: { error: unknown }) {
  if (!error) return null;
  return <span className="text-xs text-error-700">{errMessage(error)}</span>;
}

/**
 * Gate a maker-checker action cluster on the signed-in role's permission.
 *
 * The backend is the source of truth (requireRole + SoD via the event trail),
 * but the UI must not even *offer* a step the role can't take — e.g. a Credit
 * Executive must never see the Credit Head's approve/reject. ADMIN holds every
 * permission, so it retains oversight across all steps.
 */
function ActionGate({ permission, children }: { permission: Permission; children: React.ReactNode }) {
  const me = useStaffMe();
  const role = me.data?.role;
  if (!role) return null; // session still loading / not signed in
  if (!hasPermission(role, permission)) {
    return <span className="text-xs italic text-muted">Not your step</span>;
  }
  return <>{children}</>;
}

/**
 * Gate a whole panel/section on the signed-in role's permission(s).
 *
 * Unlike {@link ActionGate} (which hides only the buttons inside an always-rendered
 * panel), this hides the entire child — used so each role sees only the queues it
 * works on (e.g. a Credit Executive doesn't see the Credit Head's decision panel).
 * Pass an array to allow any-of. ADMIN holds every permission, so it sees all panels.
 */
export function PermissionGate({
  permission,
  children,
  fallback = null,
}: {
  permission: Permission | Permission[];
  children: React.ReactNode;
  fallback?: React.ReactNode;
}) {
  const role = useStaffMe().data?.role;
  if (!role) return null; // session still loading / not signed in
  const perms = Array.isArray(permission) ? permission : [permission];
  if (!perms.some((p) => hasPermission(role, p))) return <>{fallback}</>;
  return <>{children}</>;
}

/** Friendly fallback for a page/section the signed-in role can't access. */
export function NoAccessNotice({ message = "You don't have access to this queue." }: { message?: string }) {
  return (
    <p className="rounded border border-line bg-white px-5 py-8 text-center text-sm text-muted shadow-sm">{message}</p>
  );
}

/**
 * Approve/Reject cluster that also captures a transaction id / reference, stored
 * in the action's audit `notes`. Used by the Disbursement Head (approve the
 * release) and the Accountant (confirm the bank transfer). Approve is disabled
 * until a reference is entered; Reject doesn't require one (the text, if any, is
 * passed as the rejection/failure reason).
 *
 * Both paths need a reference typed in, and a free-text input is far too wide for
 * a table cell — so in `compact` (queue-row) mode this renders nothing at all and
 * the staffer uses `Open`, where the same cluster renders in full.
 */
function ProofDecisionActions({
  compact,
  permission,
  approveLabel,
  rejectLabel,
  pending,
  error,
  onApprove,
  onReject,
  requireProofOnApprove = true,
  proofPlaceholder = "Transaction id / reference",
  hint,
}: {
  compact?: boolean;
  permission: Permission;
  approveLabel: string;
  rejectLabel: string;
  pending: boolean;
  error: unknown;
  onApprove: (proof: string) => void;
  onReject: (proof?: string) => void;
  requireProofOnApprove?: boolean;
  proofPlaceholder?: string;
  hint?: string;
}) {
  const [proof, setProof] = React.useState("");
  const proofMissing = requireProofOnApprove && proof.trim().length === 0;
  if (compact) return null;
  return (
    <ActionGate permission={permission}>
      <div className="flex flex-col gap-1">
        <div className="flex flex-wrap items-center gap-2">
          <Input
            aria-label="Transaction id or reference"
            value={proof}
            onChange={(e) => setProof(e.target.value)}
            inputClassName="w-56"
            className="!mb-0"
            placeholder={proofPlaceholder}
          />
          <button
            onClick={() => onApprove(proof.trim())}
            disabled={pending || proofMissing}
            className="btn btn-sm bg-success-600 border-success-600 text-white hover:bg-success-700 disabled:opacity-50"
          >
            {pending ? <Loader2 size={14} className="animate-spin" /> : <Check size={14} />} {approveLabel}
          </button>
          <button
            onClick={() => onReject(proof.trim() || undefined)}
            disabled={pending}
            className="btn btn-sm bg-error-600 border-error-600 text-white hover:bg-error-700 disabled:opacity-50"
          >
            <X size={14} /> {rejectLabel}
          </button>
          <ActionError error={error} />
        </div>
        {hint && <p className="text-xs text-muted">{hint}</p>}
      </div>
    </ActionGate>
  );
}

export function KycActions({ app, compact }: { app: ApplicationView; compact?: boolean }) {
  const refresh = useRefreshAfterAction();
  const m = useMutation({
    mutationFn: (decision: boolean) => staffApi.kycDecision(app.id, decision),
    onSuccess: () => refresh(app.id),
  });
  return (
    <ActionGate permission="kyc:approve">
      <div className="flex items-center gap-2">
        {/* Reject is a rejection of a person's file — it belongs on the detail dialog next to the
            evidence, not as a one-click button on a dense queue row. */}
        {compact ? (
          <button
            onClick={() => m.mutate(true)}
            disabled={m.isPending}
            className="btn btn-sm bg-success-600 border-success-600 text-white hover:bg-success-700 disabled:opacity-50"
          >
            {m.isPending ? <Loader2 size={14} className="animate-spin" /> : <Check size={14} />} Approve
          </button>
        ) : (
          <ApproveRejectButtons
            pending={m.isPending}
            onApprove={() => m.mutate(true)}
            onReject={() => m.mutate(false)}
          />
        )}
        <ActionError error={m.error} />
      </div>
    </ActionGate>
  );
}

/**
 * The credit assignment picker. `compact` (queue-row) renders nothing: a `<select>` of executives
 * cannot fit a table cell without forcing the grid to scroll sideways, so assignment happens on the
 * application detail dialog that `Open` raises.
 */
export function AssignActions({ app, compact }: { app: ApplicationView; compact?: boolean }) {
  const refresh = useRefreshAfterAction();
  const me = useStaffMe();
  const canAssignSelf = me.data?.role === "ADMIN" || me.data?.role === "CREDIT_HEAD";
  const [execId, setExecId] = React.useState("");
  // Assignee picker: only active Credit Executives, plus the acting Credit Head via self-assign.
  // Sourced from the dedicated staff-readable endpoint, NOT adminApi.listStaff() — that route is
  // ADMIN-only, so it 403'd for the Credit Head and left this picker permanently empty
  // ("No active credit executives"), making assignment impossible for the role that owns the step.
  const execQ = useQuery({
    queryKey: ["staff-executives"],
    queryFn: () => staffApi.creditExecutives(),
    staleTime: 60_000,
  });
  const execs = execQ.data ?? [];
  const m = useMutation({
    mutationFn: () => staffApi.assign(app.id, Number.parseInt(execId, 10)),
    onSuccess: () => refresh(app.id),
  });
  // ADMIN oversight: self-assign and drive the credit step solo (the backend lifts the
  // active-Credit-Executive requirement for ADMIN).
  const mSelf = useMutation({
    mutationFn: () => staffApi.assign(app.id, Number(me.data!.id)),
    onSuccess: () => refresh(app.id),
  });
  if (compact) return null;
  return (
    <ActionGate permission="loan:approve">
      {/* `items-end` so the buttons sit on the select's baseline rather than its label's. */}
      <div className="flex flex-wrap items-end gap-2">
        {execQ.isLoading ? (
          <span className="text-xs text-muted">Loading executives…</span>
        ) : execQ.error ? (
          // Distinguish "couldn't load the list" from "the list is genuinely empty" — conflating
          // the two is what hid the ADMIN-only-endpoint bug behind a plausible-looking message.
          <span className="text-xs text-error-700">
            Couldn&apos;t load executives — {errMessage(execQ.error)}
          </span>
        ) : execs.length === 0 ? (
          <span className="text-xs text-muted">No active credit executives</span>
        ) : (
          <Select
            label="Credit executive"
            className="w-56"
            value={execId}
            onChange={(e) => setExecId(e.target.value)}
          >
            <option value="" disabled>
              Select executive…
            </option>
            {execs.map((s) => (
              <option key={s.id} value={s.id}>
                {s.name}
              </option>
            ))}
          </Select>
        )}
        <div className="flex flex-wrap items-center gap-2">
          {execs.length > 0 && (
            <button
              onClick={() => m.mutate()}
              disabled={m.isPending || !execId}
              className="btn btn-sm btn-navy disabled:opacity-50"
            >
              {m.isPending ? <Loader2 size={14} className="animate-spin" /> : null} {app.assignedExecutiveId ? "Reassign" : "Assign"}
            </button>
          )}
          {canAssignSelf && me.data && (
            <button
              onClick={() => mSelf.mutate()}
              disabled={mSelf.isPending}
              className="btn btn-sm btn-outline disabled:opacity-50"
              title="Assign this credit review to yourself"
            >
              {mSelf.isPending ? <Loader2 size={14} className="animate-spin" /> : null} {app.assignedExecutiveId ? "Reassign to me" : "Assign to me"}
            </button>
          )}
        </div>
        <ActionError error={m.error || mSelf.error} />
      </div>
    </ActionGate>
  );
}

/**
 * The Credit Executive's row actions (V45) — Reject lead · Mark lead pending · Accept lead.
 *
 * "Accept lead" is the FINAL credit decision, so it opens {@link SanctionDialog} rather than firing
 * inline: the reviewer must set an amount and salary-credit day, and see the projection before it
 * commits. Reject and Mark pending both capture a reason, and assignment needs an executive picker.
 * "Mark lead pending" is only a tag (the lead keeps its status and its place in the queue, and the
 * borrower is never told).
 *
 * `compact` is the queue-row rendering: only "Accept lead" — the one decision that needs no typing —
 * stays on the row. Reject, Mark pending and assignment all need a text field or a picker, which
 * would force the table to scroll sideways, so a row sends the reviewer to `Open` (the application
 * detail dialog) where this same component renders in full.
 */
export function CreditDecisionActions({ app, compact }: { app: ApplicationView; compact?: boolean }) {
  const refresh = useRefreshAfterAction();
  const me = useStaffMe();
  const [sanctioning, setSanctioning] = React.useState(false);
  const [prompt, setPrompt] = React.useState<"reject" | "pending" | null>(null);
  const [reason, setReason] = React.useState("");

  const reject = useMutation({
    mutationFn: () => staffApi.rejectLead(app.id, reason.trim() || undefined),
    onSuccess: () => {
      refresh(app.id);
      setPrompt(null);
    },
  });
  const pending = useMutation({
    mutationFn: () => staffApi.markPending(app.id, reason.trim()),
    onSuccess: () => {
      refresh(app.id);
      setPrompt(null);
      setReason("");
    },
  });

  const busy = reject.isPending || pending.isPending;
  const canAssign = me.data?.role === "CREDIT_HEAD" || me.data?.role === "ADMIN";

  // On a queue row: the one decision that needs no typing. Everything else is behind `Open`.
  if (compact) {
    return (
      <ActionGate permission="loan:review">
        <button onClick={() => setSanctioning(true)} className="btn btn-sm btn-gold">
          <Check size={14} /> Accept
        </button>
        <SanctionDialog app={app} open={sanctioning} onClose={() => setSanctioning(false)} />
      </ActionGate>
    );
  }

  return (
    <ActionGate permission="loan:review">
      <div className="flex flex-wrap items-end gap-x-4 gap-y-2">
        {canAssign && <AssignActions app={app} />}
        {app.markedPendingAt && (
          <p className="text-xs text-warning-700">
            Marked pending{app.pendingReason ? ` — ${app.pendingReason}` : ""}
          </p>
        )}
        {prompt ? (
          <div className="flex flex-wrap items-center gap-2">
            <Input
              aria-label={prompt === "reject" ? "Rejection remarks" : "Reason for marking pending"}
              value={reason}
              onChange={(e) => setReason(e.target.value)}
              inputClassName="w-64"
              className="!mb-0"
              placeholder={prompt === "reject" ? "Why (staff-only)" : "What you're waiting on"}
            />
            <button
              onClick={() => (prompt === "reject" ? reject.mutate() : pending.mutate())}
              disabled={busy || (prompt === "pending" && !reason.trim())}
              className="btn btn-sm btn-navy disabled:opacity-50"
            >
              {busy ? <Loader2 size={14} className="animate-spin" /> : null} Confirm
            </button>
            <button onClick={() => setPrompt(null)} disabled={busy} className="btn btn-sm btn-outline">
              Cancel
            </button>
            <ActionError error={reject.error || pending.error} />
          </div>
        ) : (
          <div className="flex flex-wrap items-center gap-2">
            <button
              onClick={() => setPrompt("reject")}
              className="btn btn-sm bg-error-600 border-error-600 text-white hover:bg-error-700"
            >
              <X size={14} /> Reject lead
            </button>
            <button onClick={() => setPrompt("pending")} className="btn btn-sm btn-outline">
              Mark lead pending
            </button>
            <button onClick={() => setSanctioning(true)} className="btn btn-sm btn-gold">
              <Check size={14} /> Accept lead
            </button>
          </div>
        )}
      </div>
      <SanctionDialog app={app} open={sanctioning} onClose={() => setSanctioning(false)} />
    </ActionGate>
  );
}

export function DisbursementActions({ app, compact }: { app: ApplicationView; compact?: boolean }) {
  const refresh = useRefreshAfterAction();
  const m = useMutation({
    mutationFn: (vars: { decision: boolean; txnRef?: string; notes?: string }) =>
      staffApi.disbursementDecision(app.id, vars.decision, vars.txnRef, vars.notes),
    onSuccess: () => refresh(app.id),
  });
  return (
    <ProofDecisionActions
      compact={compact}
      permission="loan:disburse"
      approveLabel="Approve & release"
      rejectLabel="Reject"
      pending={m.isPending}
      error={m.error}
      // Phase 4 (decision 42): the Head makes the transfer and releases directly — there is no
      // accountant hop left to approve into, so the transaction id is required, not optional.
      requireProofOnApprove
      proofPlaceholder="Transaction id"
      hint="Enter the transaction id of the transfer you made. Releasing activates the loan immediately."
      onApprove={(proof) => m.mutate({ decision: true, txnRef: proof || undefined, notes: proof ? `Txn/ref: ${proof}` : undefined })}
      onReject={(proof) => m.mutate({ decision: false, notes: proof })}
    />
  );
}

