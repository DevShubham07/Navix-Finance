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
 */

import * as React from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Loader2, UserPlus } from "lucide-react";
import { Dialog, DialogHeader, DialogTitle, Select } from "@/components/ui";
import { hasPermission } from "@/lib/auth/rbac";
import { collectionsApi } from "@/lib/api/applications";
import { errMessage, useStaffMe } from "@/components/staff/pipeline/hooks";

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
  const qc = useQueryClient();
  const [officerId, setOfficerId] = React.useState("");

  const officersQ = useQuery({
    queryKey: ["collection-officers"],
    queryFn: collectionsApi.listOfficers,
    staleTime: 60_000,
  });
  const officers = officersQ.data ?? [];

  const assign = useMutation({
    mutationFn: async (id: number) => {
      // Open the case on demand — idempotent server-side, so a reassign never duplicates it, and
      // nobody has to remember a separate "start collections" step.
      const existing = await collectionsApi.caseByLoan(loanId);
      const caseId = existing?.id ?? (await collectionsApi.openCase(loanId)).id;
      return collectionsApi.assignOfficer(caseId, id);
    },
    onSuccess: () => {
      for (const key of [
        ["collections-worklist"],
        ["collections-cases"],
        ["collection-cases"],
        ["staff-queue"],
        ["staff-dashboard-queue"],
      ]) {
        qc.invalidateQueries({ queryKey: key });
      }
      onClose();
    },
  });

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
          onClick={() => assign.mutate(Number.parseInt(officerId, 10))}
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
