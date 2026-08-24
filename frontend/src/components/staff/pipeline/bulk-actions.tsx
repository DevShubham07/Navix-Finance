"use client";

/**
 * Bulk assign / bulk reject on the live-applications queues.
 *
 * There is NO batch endpoint on the backend and this module must never add one — a sequential
 * loop over the existing per-id endpoints (`staffApi.rejectLead`, `staffApi.disbursementDecision`,
 * `staffApi.assign`) keeps every audited SoD/ownership/event-trail guard those endpoints already
 * enforce, for free. This is the same shape as the telecalling queue's bulk "Send to selected"
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
import { Select } from "@/components/ui";
import { staffApi } from "@/lib/api/applications";
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

/** Set-based multi-select, the same shape `telecalling/page.tsx` uses for its bulk send. */
export function useQueueSelection(rowIds: number[]) {
  const [selected, setSelected] = React.useState<Set<number>>(new Set());

  // Reset whenever the underlying row list changes (a new poll, a filter, a page nav) — a stale
  // selection could otherwise point at ids no longer in view.
  React.useEffect(() => setSelected(new Set()), [rowIds.length]);

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

async function runSequentially(ids: number[], call: (id: number) => Promise<unknown>): Promise<BulkResult> {
  const ok: number[] = [];
  const failed: BulkResult["failed"] = [];
  for (const id of ids) {
    try {
      await call(id);
      ok.push(id);
    } catch (e) {
      failed.push({ id, message: errMessage(e) });
    }
  }
  return { ok, failed };
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

  React.useEffect(() => {
    if (open) {
      setReason("");
      setResult(null);
    }
  }, [open]);

  const m = useMutation({
    mutationFn: () =>
      runSequentially(ids, (id) =>
        mode === "credit"
          ? staffApi.rejectLead(id, reason.trim())
          : staffApi.disbursementDecision(id, false, undefined, reason.trim()),
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

  React.useEffect(() => {
    if (open) {
      setExecId("");
      setResult(null);
    }
  }, [open]);

  const execQ = useQuery({
    queryKey: ["staff-executives"],
    queryFn: () => staffApi.creditExecutives(),
    staleTime: 60_000,
  });
  const execs = execQ.data ?? [];

  const m = useMutation({
    mutationFn: () => runSequentially(ids, (id) => staffApi.assign(id, Number.parseInt(execId, 10))),
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
            <span className="text-xs text-error-700">Couldn&apos;t load executives — {errMessage(execQ.error)}</span>
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
 */
export function BulkActionBar({
  count,
  onReject,
  onAssign,
}: {
  count: number;
  onReject?: () => void;
  onAssign?: () => void;
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
      {onReject && (
        <button
          type="button"
          onClick={onReject}
          className="btn btn-sm bg-error-600 border-error-600 text-white hover:bg-error-700"
        >
          Reject selected
        </button>
      )}
    </div>
  );
}
