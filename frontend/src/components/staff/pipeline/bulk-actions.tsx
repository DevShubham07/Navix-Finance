"use client";

/**
 * Bulk assign / bulk reject on the live-applications queues.
 *
 * There is NO batch endpoint on the backend and this module must never add one — a loop over the
 * existing per-id endpoints (`staffApi.rejectLead`, `staffApi.disbursementDecision`,
 * `staffApi.assign`), still exactly one call per id, keeps every audited SoD/ownership/event-trail
 * guard those endpoints already enforce, for free. The loop runs up to four ids at once
 * (`runWithConcurrency`, lib/staff/queue-bulk.ts) and reports progress; it never merges calls.
 * This is the same shape as the telecalling queue's bulk "Send to selected"
 * (`app/staff/telecalling/page.tsx`): a `Set<number>` selection, a header checkbox that toggles
 * all, a bulk button that only appears once something is selected, and a per-row try/catch so one
 * failure doesn't abort the rest.
 *
 * `RejectDialog` and `AssignDialog` both work for a single id too (pass `ids={[id]}`) — the
 * row-level actions in `actions.tsx` reuse them rather than duplicating the reason/executive UI.
 */

import * as React from "react";
import { useMutation, useQuery } from "@tanstack/react-query";
import { Loader2 } from "lucide-react";
import { Dialog, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { ErrorState, InfoTooltip, Select } from "@/components/ui";
import { staffApi } from "@/lib/api/applications";
import { runWithConcurrency, DEFAULT_BULK_CONCURRENCY } from "@/lib/staff/queue-bulk";
import { useRefreshAfterAction, errMessage, useStaffMe } from "@/components/staff/pipeline/hooks";

/** Roles that may bulk-reject (heads of the maker-checker stages + ADMIN). */
const BULK_REJECT_ROLES = new Set(["CREDIT_HEAD", "DISBURSEMENT_HEAD", "COLLECTION_HEAD", "ADMIN"]);
/** Roles that may bulk-assign — mirrors `staffApi.assign`'s own CREDIT_HEAD/ADMIN requirement. */
const BULK_ASSIGN_ROLES = new Set(["CREDIT_HEAD", "ADMIN"]);

/** The signed-in role's bulk-action rights, read off {@link useStaffMe} once per page. */
export function useBulkRoleFlags() {
  const role = useStaffMe().data?.role;
  return {
    canBulkReject: role != null && BULK_REJECT_ROLES.has(role),
    canBulkAssign: role != null && BULK_ASSIGN_ROLES.has(role),
  };
}

/** Which per-id reject call a {@link RejectDialog} should loop. */
export type RejectMode = "credit" | "disbursement";

/**
 * The {@link BulkActionBar} `rejectDisabledReason` for a selection spanning both reject modes: one
 * bulk run loops exactly one endpoint (`rejectLead` vs `disbursementDecision`), so there is no single
 * mode to run it under.
 */
export const MIXED_REJECT_MODES_REASON =
  "The selection mixes credit-stage and disbursement-pending files, which are rejected through different steps. Select files of one kind to reject them in bulk.";

/** Set-based multi-select, the same shape `telecalling/page.tsx` uses for its bulk send. */
export function useQueueSelection(rowIds: number[]) {
  const [selected, setSelected] = React.useState<Set<number>>(new Set());

  // Reset whenever the underlying row list changes (a new poll, a filter, a page nav) — a stale
  // selection could otherwise point at ids no longer in view. Keyed on the ids themselves, not the
  // length: two server pages of the same size must not carry a selection across.
  const rowKey = rowIds.join(",");
  React.useEffect(() => setSelected(new Set()), [rowKey]);

  const toggle = (id: number) => {
    setSelected((s) => {
      const next = new Set(s);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  };
  const toggleAll = () => {
    setSelected((s) => (s.size === rowIds.length ? new Set() : new Set(rowIds)));
  };
  const clear = () => setSelected(new Set());

  return { selected, toggle, toggleAll, clear };
}

interface BulkResult {
  ok: number[];
  failed: { id: number; message: string }[];
}

/** "n rejected, m failed" / "n assigned, m failed" — singular-aware, lists the failed ids. */
function ResultSummary({ verb, result }: { verb: string; result: BulkResult }) {
  return (
    <p className="text-xs text-ink">
      {result.ok.length} {verb}
      {result.failed.length > 0 && (
        <span className="text-error-700">
          , {result.failed.length} failed (#{result.failed.map((f) => f.id).join(", #")})
        </span>
      )}
      .
    </p>
  );
}

/** Exported so other bulk surfaces (the collections register's bulk officer assign) reuse the same
 *  one-at-a-time loop and partial-failure shape instead of growing a second one. Strictly one id in
 *  flight — the same runner the dialogs below use, at concurrency 1. */
export async function runSequentially(ids: number[], call: (id: number) => Promise<unknown>): Promise<BulkResult> {
  return runWithConcurrency(ids, call, { concurrency: 1, describeError: errMessage });
}

interface BulkProgress {
  done: number;
  total: number;
}

/**
 * Up to {@link DEFAULT_BULK_CONCURRENCY} per-id calls in flight, with a running count — still one
 * existing endpoint call per id (see the module doc), so every guard runs per id.
 */
function runBulk(ids: number[], call: (id: number) => Promise<unknown>, onProgress: (p: BulkProgress) => void) {
  onProgress({ done: 0, total: ids.length });
  return runWithConcurrency(ids, call, {
    concurrency: DEFAULT_BULK_CONCURRENCY,
    describeError: errMessage,
    onProgress: (done, total) => onProgress({ done, total }),
  });
}

/** "12 / 40 done" while a bulk run is in flight. Silent for a single id — nothing to count. */
function ProgressLine({ pending, progress }: { pending: boolean; progress: BulkProgress | null }) {
  if (!pending || !progress || progress.total < 2) return null;
  return (
    <p role="status" aria-live="polite" className="mt-2 text-xs text-muted">
      {progress.done} / {progress.total} done
    </p>
  );
}

/**
 * Reject dialog — one required reason, disabled until non-blank, states the count + consequence
 * before it submits. Loops `staffApi.rejectLead` (credit/sanctioned rows) or
 * `staffApi.disbursementDecision(id, false, undefined, reason)` (disbursement rows).
 */
export function RejectDialog({
  ids,
  mode,
  open,
  onClose,
  onDone,
}: {
  ids: number[];
  mode: RejectMode;
  open: boolean;
  onClose: () => void;
  /** Called once the bulk loop finishes (success or partial failure) — the caller clears its
   *  selection here, while the dialog stays open showing the "n rejected, m failed" summary. */
  onDone?: () => void;
}) {
  const refresh = useRefreshAfterAction();
  const [reason, setReason] = React.useState("");
  const [result, setResult] = React.useState<BulkResult | null>(null);
  const [progress, setProgress] = React.useState<BulkProgress | null>(null);

  React.useEffect(() => {
    if (open) {
      setReason("");
      setResult(null);
      setProgress(null);
    }
  }, [open]);

  const m = useMutation({
    mutationFn: () =>
      runBulk(
        ids,
        (id) =>
          mode === "credit"
            ? staffApi.rejectLead(id, reason.trim())
            : staffApi.disbursementDecision(id, false, undefined, reason.trim()),
        setProgress,
      ),
    onSuccess: (r) => {
      setResult(r);
      r.ok.forEach((id) => refresh(id));
      onDone?.();
    },
  });

  const count = ids.length;
  const consequence =
    mode === "credit"
      ? count === 1
        ? "The borrower is notified and blocked from re-applying for 30 days."
        : "Each borrower is notified and blocked from re-applying for 30 days."
      : count === 1
        ? "The borrower is notified this release was rejected."
        : "Each borrower is notified their release was rejected.";

  return (
    <Dialog open={open} onClose={onClose} aria-label="Reject applications">
      <DialogHeader>
        <DialogTitle>
          Reject {count} application{count === 1 ? "" : "s"}?
        </DialogTitle>
      </DialogHeader>
      <p className="mb-3 text-sm text-muted">{consequence}</p>
      {result ? (
        <>
          <ResultSummary verb="rejected" result={result} />
          <DialogFooter>
            <button type="button" onClick={onClose} className="btn btn-sm btn-navy">
              Done
            </button>
          </DialogFooter>
        </>
      ) : (
        <>
          <textarea
            aria-label="Rejection reason"
            value={reason}
            onChange={(e) => setReason(e.target.value)}
            placeholder="Why (staff-only, required)"
            rows={3}
            className="w-full rounded border border-line px-2 py-1.5 text-sm"
          />
          <ProgressLine pending={m.isPending} progress={progress} />
          {m.error && <p className="mt-2 text-xs text-error-700">{errMessage(m.error)}</p>}
          <DialogFooter>
            <button type="button" onClick={onClose} disabled={m.isPending} className="btn btn-sm btn-outline">
              Cancel
            </button>
            <button
              type="button"
              onClick={() => m.mutate()}
              disabled={m.isPending || !reason.trim()}
              className="btn btn-sm bg-error-600 border-error-600 text-white hover:bg-error-700 disabled:opacity-50"
            >
              {m.isPending ? <Loader2 size={14} className="animate-spin" /> : null} Reject
            </button>
          </DialogFooter>
        </>
      )}
    </Dialog>
  );
}

/** Assign dialog — reuses the same executive picker `AssignActions` uses, loops `staffApi.assign`. */
export function AssignDialog({
  ids,
  open,
  onClose,
  onDone,
}: {
  ids: number[];
  open: boolean;
  onClose: () => void;
  /** Called once the bulk loop finishes (success or partial failure) — the caller clears its
   *  selection here, while the dialog stays open showing the "n assigned, m failed" summary. */
  onDone?: () => void;
}) {
  const refresh = useRefreshAfterAction();
  const [execId, setExecId] = React.useState("");
  const [result, setResult] = React.useState<BulkResult | null>(null);
  const [progress, setProgress] = React.useState<BulkProgress | null>(null);

  React.useEffect(() => {
    if (open) {
      setExecId("");
      setResult(null);
      setProgress(null);
    }
  }, [open]);

  // Static reference data: the active-executive roster changes when an admin activates or
  // deactivates someone, not between dialog opens. Same key and staleTime as the credit workbench.
  const execQ = useQuery({
    queryKey: ["staff-executives"],
    queryFn: () => staffApi.creditExecutives(),
    staleTime: 15 * 60_000,
  });
  const execs = execQ.data ?? [];

  const m = useMutation({
    mutationFn: () => runBulk(ids, (id) => staffApi.assign(id, Number.parseInt(execId, 10)), setProgress),
    onSuccess: (r) => {
      setResult(r);
      r.ok.forEach((id) => refresh(id));
      onDone?.();
    },
  });

  const count = ids.length;

  return (
    <Dialog open={open} onClose={onClose} aria-label="Assign applications">
      <DialogHeader>
        <DialogTitle>
          Assign {count} application{count === 1 ? "" : "s"}?
        </DialogTitle>
      </DialogHeader>
      {result ? (
        <>
          <ResultSummary verb="assigned" result={result} />
          <DialogFooter>
            <button type="button" onClick={onClose} className="btn btn-sm btn-navy">
              Done
            </button>
          </DialogFooter>
        </>
      ) : (
        <>
          {execQ.isLoading ? (
            <span className="text-xs text-muted">Loading executives…</span>
          ) : execQ.error ? (
            <ErrorState
              error={execQ.error}
              title={`Couldn't load executives — ${errMessage(execQ.error)}`}
              onRetry={() => void execQ.refetch()}
              className="py-4"
            />
          ) : execs.length === 0 ? (
            <span className="text-xs text-muted">No active credit executives</span>
          ) : (
            <Select
              label="Credit executive"
              className="w-full"
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
          <ProgressLine pending={m.isPending} progress={progress} />
          {m.error && <p className="mt-2 text-xs text-error-700">{errMessage(m.error)}</p>}
          <DialogFooter>
            <button type="button" onClick={onClose} disabled={m.isPending} className="btn btn-sm btn-outline">
              Cancel
            </button>
            <button
              type="button"
              onClick={() => m.mutate()}
              disabled={m.isPending || !execId}
              className="btn btn-sm btn-navy disabled:opacity-50"
            >
              {m.isPending ? <Loader2 size={14} className="animate-spin" /> : null} Assign
            </button>
          </DialogFooter>
        </>
      )}
    </Dialog>
  );
}

/** The checkbox-column + bulk-bar wiring {@link QueuePanel}/{@link QueueTable} render on. */
export interface QueueSelection {
  selected: Set<number>;
  toggle: (id: number) => void;
  toggleAll: () => void;
  /** Whether every row currently in the queue (not just the visible page) is selected — drives
   *  the header checkbox's checked state, matching `telecalling/page.tsx`'s full-list semantics. */
  allSelected: boolean;
  /** Present only when the signed-in role may take this bulk action — its absence hides the button. */
  onAssign?: () => void;
  onReject?: () => void;
}

/**
 * Bundles selection state + the assign/reject dialogs for one queue's row-id list, gated on the
 * signed-in role. Returns `selection: undefined` when the role can do neither bulk action, so the
 * caller can pass it straight through to {@link QueuePanel}/{@link QueueTable} and get the
 * "byte-identical when absent" behaviour those components promise — no checkbox column, no bar.
 *
 * `rejectMode` omitted means this queue offers no bulk reject at all (e.g. a read-only panel);
 * `allowAssign=false` (default) means no bulk assign even for an assign-capable role — only the
 * credit unallocated queue actually offers it.
 */
export function useBulkQueue(
  ids: number[],
  opts: { rejectMode?: RejectMode; allowAssign?: boolean } = {},
): { selection: QueueSelection | undefined; dialogs: React.ReactNode } {
  const { canBulkReject, canBulkAssign } = useBulkRoleFlags();
  const sel = useQueueSelection(ids);
  const [dialog, setDialog] = React.useState<"reject" | "assign" | null>(null);
  const selectedIds = React.useMemo(() => [...sel.selected], [sel.selected]);

  const canReject = Boolean(opts.rejectMode) && canBulkReject;
  const canAssign = Boolean(opts.allowAssign) && canBulkAssign;

  const selection: QueueSelection | undefined =
    canReject || canAssign
      ? {
          selected: sel.selected,
          toggle: sel.toggle,
          toggleAll: sel.toggleAll,
          allSelected: ids.length > 0 && sel.selected.size === ids.length,
          onAssign: canAssign ? () => setDialog("assign") : undefined,
          onReject: canReject ? () => setDialog("reject") : undefined,
        }
      : undefined;

  const dialogs = (
    <>
      {opts.rejectMode && (
        <RejectDialog
          ids={selectedIds}
          mode={opts.rejectMode}
          open={dialog === "reject"}
          onClose={() => setDialog(null)}
          onDone={sel.clear}
        />
      )}
      {opts.allowAssign && (
        <AssignDialog ids={selectedIds} open={dialog === "assign"} onClose={() => setDialog(null)} onDone={sel.clear} />
      )}
    </>
  );

  return { selection, dialogs };
}

/**
 * The bulk-action bar rendered in a {@link QueuePanel} header once something is selected.
 * `canReject`/`canAssign` are read by the caller off {@link useStaffMe} per the brief's role
 * gating (bulk reject: heads + ADMIN; bulk assign: CREDIT_HEAD/ADMIN, credit panels only).
 *
 * `rejectDisabledReason`: for a role that CAN bulk reject but whose current selection cannot be
 * run as one reject (e.g. it mixes credit-stage and DISBURSEMENT_PENDING files, which loop two
 * different endpoints). Pass it with `onReject` omitted and the Reject button stays in place,
 * disabled, with the reason beside it — rather than vanishing as the selection changes.
 */
export function BulkActionBar({
  count,
  onReject,
  onAssign,
  rejectDisabledReason,
}: {
  count: number;
  onReject?: () => void;
  onAssign?: () => void;
  rejectDisabledReason?: string;
}) {
  if (count === 0) return null;
  return (
    <div className="flex items-center gap-2">
      <span className="text-xs font-semibold text-navy">{count} selected</span>
      {onAssign && (
        <button type="button" onClick={onAssign} className="btn btn-sm btn-navy">
          Assign selected
        </button>
      )}
      {onReject ? (
        <button
          type="button"
          onClick={onReject}
          className="btn btn-sm bg-error-600 border-error-600 text-white hover:bg-error-700"
        >
          Reject selected
        </button>
      ) : rejectDisabledReason ? (
        // A disabled button takes no hover or focus, so the reason lives on a focusable ⓘ beside it.
        <span className="inline-flex items-center gap-1">
          <button
            type="button"
            disabled
            className="btn btn-sm bg-error-600 border-error-600 text-white disabled:opacity-50"
          >
            Reject selected
          </button>
          <InfoTooltip content={rejectDisabledReason} label="Why is Reject selected unavailable?" />
        </span>
      ) : null}
    </div>
  );
}
