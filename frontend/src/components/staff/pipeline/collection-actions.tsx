"use client";

/**
 * Collections action cluster for the "Awaiting repayment" rows on /staff/applications.
 *
 * Replaces the old two-step "Collectible loans → Open case → go to the case page → assign an
 * officer" journey with a single control: pick a collections executive on the row and assign.
 * A collection case is a bookkeeping artefact, not a decision — `CollectionsService.openCase` is
 * idempotent and only needs the loan to exist — so this cluster opens one on demand behind the
 * assign and never asks a human to create it first.
 *
 * Role split mirrors the backend (`CollectionsService.assignOfficer`): only a COLLECTION_HEAD (or
 * ADMIN) may assign, so a Collection Executive sees the current assignment and the link into the
 * case workspace, but no picker.
 */

import * as React from "react";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import Link from "next/link";
import { Loader2, ArrowRight, UserPlus } from "lucide-react";
import { Select } from "@/components/ui";
import { hasPermission } from "@/lib/auth/rbac";
import { collectionsApi, type ApplicationView, type CaseView } from "@/lib/api/applications";
import { useStaffMe, errMessage } from "@/components/staff/pipeline/hooks";

/** Shared across every row on the page — one request, deduped by React Query's key. */
export function useCollectionCases() {
  return useQuery({
    queryKey: ["collections-cases"],
    queryFn: collectionsApi.listCases,
    staleTime: 30_000,
  });
}

export function CollectionAssignActions({ app, compact }: { app: ApplicationView; compact?: boolean }) {
  const qc = useQueryClient();
  const role = useStaffMe().data?.role;
  const canManage = role != null && hasPermission(role, "collections:manage");
  const canInteract = role != null && hasPermission(role, "collections:interact");

  const casesQ = useCollectionCases();
  const kase: CaseView | undefined = (casesQ.data ?? []).find((c) => c.loanId === app.loanId);

  const officersQ = useQuery({
    queryKey: ["collection-officers"],
    queryFn: collectionsApi.listOfficers,
    staleTime: 60_000,
    enabled: canManage,
  });
  const officers = officersQ.data ?? [];

  const [officerId, setOfficerId] = React.useState("");

  // Executive path: they may work a case but not assign one (backend gates `assignOfficer` to the
  // Head), so they keep the plain "start collections" they had on the removed collectible-loans
  // panel — without it, removing that panel would have taken a capability away.
  const start = useMutation({
    mutationFn: () => collectionsApi.openCase(app.loanId as number),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["collections-cases"] });
      qc.invalidateQueries({ queryKey: ["staff-queue"] });
    },
  });

  const assign = useMutation({
    mutationFn: async (id: number) => {
      // Open the case on demand — idempotent server-side, so a re-assign never duplicates it.
      const caseId = kase?.id ?? (await collectionsApi.openCase(app.loanId as number)).id;
      return collectionsApi.assignOfficer(caseId, id);
    },
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["collections-cases"] });
      qc.invalidateQueries({ queryKey: ["staff-queue"] });
      qc.invalidateQueries({ queryKey: ["staff-dashboard-queue"] });
      setOfficerId("");
    },
  });

  // No loan minted yet → nothing collectible. (Shouldn't happen in the ACTIVE/OVERDUE queues,
  // but the row renders for any application, so fail closed rather than crash on a null loanId.)
  if (app.loanId == null || !canInteract) return null;

  return (
    <div className="flex items-center gap-1.5">
      {kase?.assignedOfficerName && (
        <span
          className="max-w-[9rem] truncate rounded-full bg-navy-tint px-2 py-0.5 text-xs font-semibold text-navy"
          title={`Assigned officer: ${kase.assignedOfficerName}`}
        >
          {kase.assignedOfficerName}
        </span>
      )}

      {!canManage && !kase && (
        <button
          onClick={() => start.mutate()}
          disabled={start.isPending}
          className="btn btn-sm btn-outline disabled:opacity-50"
          title="Open a collection case for this loan"
        >
          {start.isPending ? <Loader2 size={14} className="animate-spin" /> : null} Start collections
        </button>
      )}

      {kase && (
        <Link href={`/staff/collections/${kase.id}`} className="btn btn-sm btn-outline">
          Case <ArrowRight size={14} />
        </Link>
      )}

      {/* The officer picker is a full-width control, so it renders only on the detail dialog
          (`compact` is the queue row). Assigning from the row would force the grid to scroll. */}
      {canManage && !compact && (
        <>
          {officersQ.isLoading ? (
            <span className="text-xs text-muted">Loading officers…</span>
          ) : officersQ.error ? (
            <span className="text-xs text-error-700">
              Couldn&apos;t load officers — {errMessage(officersQ.error)}
            </span>
          ) : officers.length === 0 ? (
            <span className="text-xs text-muted">No active collections executives</span>
          ) : (
            <>
              <Select
                aria-label="Collections executive"
                className="!mb-0"
                value={officerId}
                onChange={(e) => setOfficerId(e.target.value)}
              >
                <option value="" disabled>
                  {kase?.assignedOfficerId != null ? "Reassign to…" : "Assign to…"}
                </option>
                {officers.map((s) => (
                  <option key={s.id} value={s.id}>
                    {s.name}
                  </option>
                ))}
              </Select>
              <button
                onClick={() => assign.mutate(Number.parseInt(officerId, 10))}
                disabled={assign.isPending || !officerId}
                className="btn btn-sm btn-navy disabled:opacity-50"
                title="Assign this loan to a collections executive (opens the collection case if there isn't one yet)"
              >
                {assign.isPending ? <Loader2 size={14} className="animate-spin" /> : <UserPlus size={14} />}{" "}
                {kase?.assignedOfficerId != null ? "Reassign" : "Assign"}
              </button>
            </>
          )}
        </>
      )}

      {(assign.error || start.error) && (
        <span className="text-xs text-error-700">{errMessage(assign.error ?? start.error)}</span>
      )}
    </div>
  );
}
