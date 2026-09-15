"use client";

/**
 * Assign a collections executive to a loan, from a table row.
 *
 * The picker itself already existed, but only on the detail dialog — a Collection Head looking at a
 * queue or bucket row could see who was assigned and never change it. And it hung off a list of
 * *cases*, so a loan without one offered nothing at all. Here it takes a loan id, opens the case
 * behind the assign (idempotent server-side), and works the same whether or not one exists — which
 * is what makes chasing a borrower BEFORE their salary day possible: assigning a pre-due loan no
 * longer flips it to IN_COLLECTIONS, so an on-time borrower is never branded delinquent.
 *
 * Three shapes of the same action live here — `WorklistAssignActions` (a button opening a dialog, for
 * sticky action cells), `InlineOfficerSelect` (the DPD register's "Collections exec" cell) and
 * `BulkAssignOfficerDialog` (the register's checkbox selection). They share
 * `openCaseThenAssign`/`useCollectionOfficers` so the open-then-assign sequence and the set of
 * caches it invalidates exist exactly once.
 */

import * as React from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Loader2, UserPlus } from "lucide-react";
import { Dialog, DialogFooter, DialogHeader, DialogTitle, Select } from "@/components/ui";
import { hasPermission } from "@/lib/auth/rbac";
import { collectionsApi } from "@/lib/api/applications";
import { errMessage, useStaffMe } from "@/components/staff/pipeline/hooks";
import { runSequentially } from "@/components/staff/pipeline/bulk-actions";

/** Every surface that renders an assignment. A reassign must not leave the worklist row, the case
 *  lists and the two dashboard queues disagreeing about who owns the loan. */
const ASSIGN_INVALIDATE_KEYS = [
  ["collections-worklist"],
  ["collections-cases"],
  ["collection-cases"],
  ["staff-queue"],
  ["staff-dashboard-queue"],
] as const;

/**
 * The assign is three calls, not one: a loan may have no collection case yet, and the case is a
 * bookkeeping artefact nobody should have to create by hand. Lives outside the hook so the bulk loop
 * runs the identical sequence rather than a second copy of it.
 */
async function openCaseThenAssign(loanId: number, officerId: number) {
  // Open the case on demand — idempotent server-side, so a reassign never duplicates it, and
  // nobody has to remember a separate "start collections" step.
  const existing = await collectionsApi.caseByLoan(loanId);
  const caseId = existing?.id ?? (await collectionsApi.openCase(loanId)).id;
  return collectionsApi.assignOfficer(caseId, officerId);
}

/**
 * Shared by the dialog and the register's inline cell so the sequence and the invalidation set can't
 * drift apart. Callers add their own per-call `onSuccess`/`onError` — React Query runs those in
 * addition to the invalidation here.
 */
function useAssignOfficer(loanId: number) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (officerId: number) => openCaseThenAssign(loanId, officerId),
    onSuccess: () => {
      for (const key of ASSIGN_INVALIDATE_KEYS) {
        qc.invalidateQueries({ queryKey: key });
      }
    },
  });
}

/** One request for the whole page: every row observes this key, so React Query serves them all from
 *  one cache entry and `staleTime` stops a refetch storm as rows mount across pagination. */
function useCollectionOfficers(enabled = true) {
  return useQuery({
    queryKey: ["collection-officers"],
    queryFn: collectionsApi.listOfficers,
    staleTime: 60_000,
    enabled,
  });
}

export function WorklistAssignActions({
  loanId,
  assignedOfficerName,
  compact,
}: {
  loanId: number;
  assignedOfficerName?: string | null;
  compact?: boolean;
}) {
  const role = useStaffMe().data?.role;
  const canManage = role != null && hasPermission(role, "collections:manage");
  const [open, setOpen] = React.useState(false);

  if (!canManage) {
    return assignedOfficerName ? <OfficerBadge name={assignedOfficerName} /> : null;
  }

  return (
    <>
      {assignedOfficerName && !compact && <OfficerBadge name={assignedOfficerName} />}
      <button
        onClick={() => setOpen(true)}
        className="btn btn-sm btn-outline"
        title={
          assignedOfficerName
            ? `Assigned to ${assignedOfficerName} — reassign`
            : "Assign a collections executive"
        }
      >
        <UserPlus size={14} /> {assignedOfficerName ? "Reassign" : "Assign"}
      </button>
      {open && (
        <AssignDialog
          loanId={loanId}
          assignedOfficerName={assignedOfficerName}
          onClose={() => setOpen(false)}
        />
      )}
    </>
  );
}

function OfficerBadge({ name }: { name: string }) {
  return (
    <span
      className="max-w-[9rem] truncate rounded-full bg-navy-tint px-2 py-0.5 text-xs font-semibold text-navy"
      title={`Assigned officer: ${name}`}
    >
      {name}
    </span>
  );
}

/**
 * A dialog rather than an inline `<select>`: these live in sticky action cells of a horizontally
 * scrolling register, where a full-width control forces the grid to scroll sideways.
 *
 * `InlineOfficerSelect` below is the deliberate exception — it sits in an ordinary mid-table column
 * rather than a sticky cell, and is hard-capped at 10rem, so it costs the grid a fixed, budgeted
 * amount of width instead of the widest officer name.
 */
function AssignDialog({
  loanId,
  assignedOfficerName,
  onClose,
}: {
  loanId: number;
  assignedOfficerName?: string | null;
  onClose: () => void;
}) {
  const [officerId, setOfficerId] = React.useState("");

  const officersQ = useCollectionOfficers();
  const officers = officersQ.data ?? [];

  const assign = useAssignOfficer(loanId);

  return (
    <Dialog open onClose={onClose} className="max-w-md" aria-label="Assign a collections executive">
      <DialogHeader>
        <DialogTitle>Assign collections · Loan #{loanId}</DialogTitle>
        <p className="text-sm text-muted">
          {assignedOfficerName
            ? `Currently with ${assignedOfficerName}.`
            : "Only ACTIVE collections executives can be assigned."}
        </p>
      </DialogHeader>

      {officersQ.isLoading ? (
        <p className="text-sm text-muted">Loading officers…</p>
      ) : officersQ.error ? (
        <p className="text-sm text-error-700">Couldn&apos;t load officers — {errMessage(officersQ.error)}</p>
      ) : officers.length === 0 ? (
        <p className="text-sm text-muted">No active collections executives.</p>
      ) : (
        <Select
          label="Collections executive"
          value={officerId}
          onChange={(e) => setOfficerId(e.target.value)}
        >
          <option value="" disabled>
            {assignedOfficerName ? "Reassign to…" : "Assign to…"}
          </option>
          {officers.map((s) => (
            <option key={s.id} value={s.id}>
              {s.name}
            </option>
          ))}
        </Select>
      )}

      {assign.error && <p className="mt-2 text-sm text-error-700">{errMessage(assign.error)}</p>}

      <div className="mt-5 flex justify-end gap-2">
        <button onClick={onClose} className="btn btn-outline">
          Cancel
        </button>
        <button
          onClick={() => assign.mutate(Number.parseInt(officerId, 10), { onSuccess: onClose })}
          disabled={!officerId || assign.isPending}
          className="btn btn-navy disabled:opacity-50"
        >
          {assign.isPending && <Loader2 size={14} className="animate-spin" />}{" "}
          {assignedOfficerName ? "Reassign" : "Assign"}
        </button>
      </div>
    </Dialog>
  );
}

/**
 * The DPD register's "Collections exec" cell, as a picker — assigning from the column named after
 * the thing being set, rather than through the Actions-cell dialog above.
 *
 * Width is the whole safety argument: `.field select` is `width: 100%`, and in the register's
 * auto-layout grid that would hand the column the widest officer name's intrinsic width. The 10rem
 * cap is what keeps this out of the horizontal-scroll trap the dialog exists to avoid.
 */
export function InlineOfficerSelect({
  loanId,
  officerId,
  officerName,
}: {
  loanId: number;
  officerId: number | null;
  officerName: string | null;
}) {
  const role = useStaffMe().data?.role;
  const canManage = role != null && hasPermission(role, "collections:manage");

  const officersQ = useCollectionOfficers(canManage);
  const assign = useAssignOfficer(loanId);

  const current = officerId != null ? String(officerId) : "";

  // Hold the operator's choice until the server agrees. The worklist polls every 8s, so a poll
  // landing mid-flight would otherwise snap this cell back to the old name and then forward again a
  // beat later — the assignment would look like it had failed. The row DATA is untouched meanwhile,
  // so a table sorted on the officer doesn't yank the row out from under the cursor mid-save; it
  // re-sorts once, after the refetch confirms.
  const [pending, setPending] = React.useState<string | null>(null);
  React.useEffect(() => {
    if (pending != null && pending === current) setPending(null);
  }, [pending, current]);
  const value = pending ?? current;

  const officers = React.useMemo(() => officersQ.data ?? [], [officersQ.data]);
  // The list is ACTIVE officers only, and may not have arrived yet — either way a controlled
  // <select> whose value matches no option renders blank, i.e. an assigned loan would read as
  // unassigned. Keep whoever currently holds it in the list, active or not.
  const options = React.useMemo(() => {
    const base = officers.map((s) => ({ value: String(s.id), label: s.name }));
    if (value && !base.some((o) => o.value === value)) {
      base.unshift({ value, label: officerName ?? `Officer #${value}` });
    }
    return base;
  }, [officers, value, officerName]);

  // Mirrors the register's `dash()` placeholder.
  const readOnlyName = officerName?.trim() ? officerName : "—";

  // A Collection Executive reads this column, they never set it (the backend gates assignOfficer to
  // the Head). Also the state while `useStaffMe` is in flight — fail closed.
  if (!canManage) return <>{readOnlyName}</>;

  // One broken officers request shouldn't paint every row on the page red; the reason goes on hover.
  if (officersQ.error) {
    return (
      <span title={`Couldn't load officers — ${errMessage(officersQ.error)}`}>{readOnlyName}</span>
    );
  }
  if (!officersQ.isLoading && options.length === 0) {
    return <span title="No active collections executives">{readOnlyName}</span>;
  }

  const selectedLabel = options.find((o) => o.value === value)?.label;

  const onChange = (next: string) => {
    if (!next || next === value) return;
    setPending(next);
    // On failure fall back to server truth; the message below explains why.
    assign.mutate(Number.parseInt(next, 10), { onError: () => setPending(null) });
  };

  return (
    // Wide enough for the 10rem select plus the spinner slot and its gap, so neither ever flexes.
    // `relative` is load-bearing: the `.sr-only` live region below is `position: absolute`, so
    // without a positioned ancestor its containing block is the page, not the horizontally
    // scrolling register — it escaped `.staff-table-scroll`'s clip, landed ~1300px out at its
    // static position, and widened the document until mobile browsers shrank the whole page to fit.
    <div className="relative w-[11.5rem]">
      <div className="flex items-center gap-1">
        <Select
          aria-label={`Collections executive for loan #${loanId}`}
          title={selectedLabel ?? undefined}
          className="!mb-0 w-40"
          value={value}
          disabled={assign.isPending || officersQ.isLoading}
          onChange={(e) => onChange(e.target.value)}
        >
          {/* Disabled, not selectable: the API takes a numeric officer id and there is no unassign
              endpoint, so "unassign" must not be silently offered. */}
          <option value="" disabled>
            {officerName ? "Reassign to…" : "Assign to…"}
          </option>
          {options.map((o) => (
            <option key={o.value} value={o.value}>
              {o.label}
            </option>
          ))}
        </Select>
        {/* Fixed-width slot so the column doesn't twitch when the spinner appears. */}
        <span className="w-3.5 shrink-0" aria-hidden="true">
          {assign.isPending && <Loader2 size={13} className="animate-spin text-muted" />}
        </span>
      </div>
      <span className="sr-only">{assign.isPending ? "Saving assignment…" : ""}</span>
      {assign.error && (
        <p
          role="alert"
          className="mt-1 truncate text-[0.625rem] text-error-700"
          title={errMessage(assign.error)}
        >
          {errMessage(assign.error)}
        </p>
      )}
    </div>
  );
}

/**
 * Bulk version of the same assign, for the DPD register's checkbox selection: one collections
 * executive across every selected loan.
 *
 * Deliberately a loop over the per-loan sequence above and not a batch call — there is no batch
 * endpoint and adding one would move the ownership/role checks off the audited per-case endpoint the
 * Head already uses. A failure on one loan is recorded and the loop continues, so a single stale row
 * can't cost the operator the other nineteen.
 */
export function BulkAssignOfficerDialog({
  loanIds,
  open,
  onClose,
  onDone,
}: {
  loanIds: number[];
  open: boolean;
  onClose: () => void;
  /** Called once the loop finishes (success or partial failure) — the caller clears its selection
   *  here, while the dialog stays open showing the "n assigned, m failed" summary. */
  onDone?: () => void;
}) {
  const qc = useQueryClient();
  const role = useStaffMe().data?.role;
  const canManage = role != null && hasPermission(role, "collections:manage");

  const [officerId, setOfficerId] = React.useState("");
  const [result, setResult] = React.useState<Awaited<ReturnType<typeof runSequentially>> | null>(null);

  React.useEffect(() => {
    if (open) {
      setOfficerId("");
      setResult(null);
    }
  }, [open]);

  const officersQ = useCollectionOfficers(open && canManage);
  const officers = officersQ.data ?? [];

  const m = useMutation({
    mutationFn: () => runSequentially(loanIds, (loanId) => openCaseThenAssign(loanId, Number.parseInt(officerId, 10))),
    onSuccess: (r) => {
      setResult(r);
      // Once for the whole run, not per row: the register polls anyway, and N invalidations would
      // fire N refetches of the same worklist while the loop is still going.
      for (const key of ASSIGN_INVALIDATE_KEYS) {
        qc.invalidateQueries({ queryKey: key });
      }
      onDone?.();
    },
  });

  // Fail closed — a Collection Executive reads this register, they never set assignments.
  if (!canManage) return null;

  const count = loanIds.length;

  return (
    <Dialog open={open} onClose={onClose} className="max-w-md" aria-label="Assign a collections executive in bulk">
      <DialogHeader>
        <DialogTitle>
          Assign {count} loan{count === 1 ? "" : "s"}?
        </DialogTitle>
        <p className="text-sm text-muted">
          Each selected loan is assigned to this executive, one at a time. Only ACTIVE collections
          executives can be assigned.
        </p>
      </DialogHeader>

      {result ? (
        <>
          <p className="text-xs text-ink">
            {result.ok.length} assigned
            {result.failed.length > 0 && (
              <span className="text-error-700">
                , {result.failed.length} failed (loan #{result.failed.map((f) => f.id).join(", #")})
              </span>
            )}
            .
          </p>
          {result.failed.length > 0 && (
            // The ids alone don't say what to do next; the first reason usually applies to all.
            <p className="mt-1 text-xs text-muted">{result.failed[0].message}</p>
          )}
          <DialogFooter>
            <button type="button" onClick={onClose} className="btn btn-sm btn-navy">
              Done
            </button>
          </DialogFooter>
        </>
      ) : (
        <>
          {officersQ.isLoading ? (
            <p className="text-sm text-muted">Loading officers…</p>
          ) : officersQ.error ? (
            <p className="text-sm text-error-700">Couldn&apos;t load officers — {errMessage(officersQ.error)}</p>
          ) : officers.length === 0 ? (
            <p className="text-sm text-muted">No active collections executives.</p>
          ) : (
            <Select
              label="Collections executive"
              value={officerId}
              onChange={(e) => setOfficerId(e.target.value)}
            >
              <option value="" disabled>
                Assign to…
              </option>
              {officers.map((s) => (
                <option key={s.id} value={s.id}>
                  {s.name}
                </option>
              ))}
            </Select>
          )}

          {m.error && <p className="mt-2 text-sm text-error-700">{errMessage(m.error)}</p>}

          <DialogFooter>
            <button type="button" onClick={onClose} disabled={m.isPending} className="btn btn-sm btn-outline">
              Cancel
            </button>
            <button
              type="button"
              onClick={() => m.mutate()}
              disabled={!officerId || count === 0 || m.isPending}
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
