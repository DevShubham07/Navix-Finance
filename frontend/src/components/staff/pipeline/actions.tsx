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
import { staffApi, adminApi, type ApplicationView } from "@/lib/api/applications";
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
 */
function ProofDecisionActions({
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

export function KycActions({ app }: { app: ApplicationView }) {
  const refresh = useRefreshAfterAction();
  const m = useMutation({
    mutationFn: (decision: boolean) => staffApi.kycDecision(app.id, decision),
    onSuccess: () => refresh(app.id),
  });
  return (
    <ActionGate permission="kyc:approve">
      <div className="flex items-center gap-2">
        <ApproveRejectButtons pending={m.isPending} onApprove={() => m.mutate(true)} onReject={() => m.mutate(false)} />
        <ActionError error={m.error} />
      </div>
    </ActionGate>
  );
}

/**
 * Reborrow review (KYC approver): clear or reject a returning borrower flagged for past delinquency.
 * Separate from fresh KYC — REVIEW_PENDING → PRE_APPROVED (clear) / REJECTED. Same `kyc:approve` perm.
 */
export function ReviewActions({ app }: { app: ApplicationView }) {
  const refresh = useRefreshAfterAction();
  const m = useMutation({
    mutationFn: (decision: boolean) => staffApi.reviewDecision(app.id, decision),
    onSuccess: () => refresh(app.id),
  });
  return (
    <ActionGate permission="kyc:approve">
      <div className="flex items-center gap-2">
        <ApproveRejectButtons
          pending={m.isPending}
          onApprove={() => m.mutate(true)}
          onReject={() => m.mutate(false)}
          approveLabel="Clear borrower"
          rejectLabel="Reject"
        />
        <ActionError error={m.error} />
      </div>
    </ActionGate>
  );
}

export function AssignActions({ app }: { app: ApplicationView }) {
  const refresh = useRefreshAfterAction();
  const me = useStaffMe();
  const isAdmin = me.data?.role === "ADMIN";
  const [execId, setExecId] = React.useState("");
  // Assignee picker: only ACTIVE Credit Executives (dfd §13.4 activation gating).
  const execQ = useQuery({
    queryKey: ["staff-executives"],
    queryFn: () => adminApi.listStaff(),
    staleTime: 60_000,
  });
  const execs = (execQ.data ?? []).filter(
    (s) => s.role === "CREDIT_EXECUTIVE" && s.status === "ACTIVE",
  );
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
  return (
    <ActionGate permission="loan:approve">
      <div className="flex items-center gap-2">
        {execQ.isLoading ? (
          <span className="text-xs text-muted">Loading executives…</span>
        ) : execs.length === 0 ? (
          <span className="text-xs text-muted">No active credit executives</span>
        ) : (
          <>
            <Select
              aria-label="Credit executive"
              className="!mb-0"
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
            <button
              onClick={() => m.mutate()}
              disabled={m.isPending || !execId}
              className="btn btn-sm btn-navy disabled:opacity-50"
            >
              {m.isPending ? <Loader2 size={14} className="animate-spin" /> : null} Assign
            </button>
          </>
        )}
        {isAdmin && me.data && (
          <button
            onClick={() => mSelf.mutate()}
            disabled={mSelf.isPending}
            className="btn btn-sm btn-outline disabled:opacity-50"
            title="Assign this credit review to yourself (admin oversight)"
          >
            {mSelf.isPending ? <Loader2 size={14} className="animate-spin" /> : null} Assign to me
          </button>
        )}
        <ActionError error={m.error || mSelf.error} />
      </div>
    </ActionGate>
  );
}

export function ExecActions({ app }: { app: ApplicationView }) {
  const refresh = useRefreshAfterAction();
  const m = useMutation({
    mutationFn: (decision: boolean) => staffApi.execDecision(app.id, decision),
    onSuccess: () => refresh(app.id),
  });
  return (
    <ActionGate permission="loan:review">
      <div className="flex items-center gap-2">
        <ApproveRejectButtons pending={m.isPending} onApprove={() => m.mutate(true)} onReject={() => m.mutate(false)} />
        <ActionError error={m.error} />
      </div>
    </ActionGate>
  );
}

export function HeadActions({ app }: { app: ApplicationView }) {
  const refresh = useRefreshAfterAction();
  const m = useMutation({
    mutationFn: (decision: boolean) =>
      staffApi.headDecision(app.id, {
        decision,
        // Approve at the requested amount by default.
        approvedAmountPaise: decision ? app.amountRequestedPaise ?? undefined : undefined,
      }),
    onSuccess: () => refresh(app.id),
  });
  return (
    <ActionGate permission="loan:approve">
      <div className="flex items-center gap-2">
        <ApproveRejectButtons pending={m.isPending} onApprove={() => m.mutate(true)} onReject={() => m.mutate(false)} />
        <ActionError error={m.error} />
      </div>
    </ActionGate>
  );
}

/**
 * KYC-approver credit fast-path: on an applied KYC_APPROVED application the KYC approver clears the
 * credit gate in one step (→ DISBURSEMENT_PENDING) or rejects it. The action only appears once the
 * borrower has chosen an amount (amountRequestedPaise set).
 */
export function KycCreditActions({ app }: { app: ApplicationView }) {
  const refresh = useRefreshAfterAction();
  const m = useMutation({
    mutationFn: (decision: boolean) =>
      staffApi.kycCreditDecision(app.id, {
        decision,
        approvedAmountPaise: decision ? app.amountRequestedPaise ?? undefined : undefined,
      }),
    onSuccess: () => refresh(app.id),
  });
  if (app.amountRequestedPaise == null) {
    return <span className="text-xs italic text-muted">Awaiting the borrower&apos;s amount</span>;
  }
  return (
    <ActionGate permission="loan:approve">
      <div className="flex items-center gap-2">
        <ApproveRejectButtons
          pending={m.isPending}
          onApprove={() => m.mutate(true)}
          onReject={() => m.mutate(false)}
          approveLabel="Approve for disbursement"
          rejectLabel="Reject"
        />
        <ActionError error={m.error} />
      </div>
    </ActionGate>
  );
}

export function DisbursementActions({ app }: { app: ApplicationView }) {
  const refresh = useRefreshAfterAction();
  const m = useMutation({
    mutationFn: (vars: { decision: boolean; txnRef?: string; notes?: string }) =>
      staffApi.disbursementDecision(app.id, vars.decision, vars.txnRef, vars.notes),
    onSuccess: () => refresh(app.id),
  });
  return (
    <ProofDecisionActions
      permission="loan:disburse"
      approveLabel="Approve & release"
      rejectLabel="Reject"
      pending={m.isPending}
      error={m.error}
      // Txn id optional here: with one the loan activates immediately (skips the
      // accountant); without one it routes to the accountant to confirm.
      requireProofOnApprove={false}
      proofPlaceholder="Transaction id (optional)"
      hint="Enter a transaction id to release & activate immediately, or approve without one to send it to the accountant."
      onApprove={(proof) => m.mutate({ decision: true, txnRef: proof || undefined, notes: proof ? `Txn/ref: ${proof}` : undefined })}
      onReject={(proof) => m.mutate({ decision: false, notes: proof })}
    />
  );
}

export function AccountantActions({ app }: { app: ApplicationView }) {
  const refresh = useRefreshAfterAction();
  const m = useMutation({
    mutationFn: (vars: { decision: boolean; txnRef?: string; notes?: string }) =>
      staffApi.accountantValidate(app.id, vars.decision, vars.txnRef, vars.notes),
    onSuccess: () => refresh(app.id),
  });
  return (
    <ProofDecisionActions
      permission="loan:activate"
      approveLabel="Confirm transfer"
      rejectLabel="Mark failed"
      pending={m.isPending}
      error={m.error}
      onApprove={(proof) => m.mutate({ decision: true, txnRef: proof || undefined, notes: proof ? `Txn/ref: ${proof}` : undefined })}
      onReject={(proof) => m.mutate({ decision: false, notes: proof })}
    />
  );
}
