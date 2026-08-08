"use client";

/**
 * Generic status-backed queue panels for the staff back office.
 *
 * {@link StatusQueue} lists applications at one {@link ApplicationStatus} (8s poll,
 * optional client-side split filter); {@link CreditQueuePanel} lists the KYC-approved
 * *applied* queue the Credit Head assigns from. Both render through {@link QueuePanel}
 * → {@link AppRow}. Moved verbatim from the former `live-pipeline.tsx` god-file.
 */

import * as React from "react";
import { useQuery } from "@tanstack/react-query";
import { Loader2, RefreshCw } from "lucide-react";
import { InfoTooltip } from "@/components/ui";
import { staffApi, type ApplicationStatus, type ApplicationView } from "@/lib/api/applications";
import { errMessage } from "@/components/staff/pipeline/hooks";
import { AppRow } from "@/components/staff/pipeline/app-row";
import { AssignActions, CreditDecisionActions } from "@/components/staff/pipeline/actions";
import { useStaffMe } from "@/components/staff/pipeline/hooks";

export function StatusQueue({
  title,
  status,
  actions,
  info,
  filter,
  withLoanHistory,
  hideWhenEmpty,
}: {
  title: string;
  status: ApplicationStatus;
  /** Omit for a read-only queue (e.g. SANCTIONED, which nobody on staff actions). */
  actions?: (app: ApplicationView) => React.ReactNode;
  /** Optional ⓘ explanation shown beside the queue title. */
  info?: string;
  /** Optional client-side filter to split one status into sections (e.g. fast-track disbursement). */
  filter?: (app: ApplicationView) => boolean;
  /** Inline loan-history on each row — the reborrow-review queue's documented exception (see AppRow). */
  withLoanHistory?: boolean;
  /**
   * Render nothing at all when the queue is empty, instead of an "nothing here" panel. For a status
   * nothing routes to any more (ACCOUNTANT_PENDING since V47): a permanent empty box on a desk that
   * will never receive work again is just noise.
   */
  hideWhenEmpty?: boolean;
}) {
  const q = useQuery({
    queryKey: ["staff-queue", status],
    queryFn: () => staffApi.listByStatus(status),
    refetchInterval: 8000,
  });

  const apps = filter ? (q.data ?? []).filter(filter) : q.data ?? [];

  if (hideWhenEmpty && !q.isLoading && !q.error && apps.length === 0) return null;

  return (
    <QueuePanel
      title={title}
      countBadge={status}
      apps={apps}
      isLoading={q.isLoading}
      error={q.error}
      onRefresh={() => q.refetch()}
      actions={actions ?? (() => null)}
      info={info}
      withLoanHistory={withLoanHistory}
    />
  );
}

export function CreditQueuePanel() {
  const q = useQuery({
    queryKey: ["staff-queue", "credit-queue"],
    queryFn: () => staffApi.creditQueue(),
    refetchInterval: 8000,
  });

  return (
    <QueuePanel
      title="Credit queue — assign an executive"
      countBadge="credit-queue"
      apps={q.data ?? []}
      isLoading={q.isLoading}
      error={q.error}
      onRefresh={() => q.refetch()}
      actions={(app) => <AssignActions app={app} />}
      info="Submitted intakes awaiting a credit review. Assign each to an ACTIVE Credit Executive — their decision is the final one."
    />
  );
}

export function CreditWorkbench() {
  const me = useStaffMe();
  const unallocatedQ = useQuery({
    queryKey: ["staff-queue", "credit-queue"],
    queryFn: () => staffApi.creditQueue(),
    refetchInterval: 8000,
  });
  const assignedQ = useQuery({
    queryKey: ["staff-queue", "CREDIT_EXEC_PENDING"],
    queryFn: () => staffApi.listByStatus("CREDIT_EXEC_PENDING"),
    refetchInterval: 8000,
  });
  const execQ = useQuery({
    queryKey: ["staff-executives"],
    queryFn: () => staffApi.creditExecutives(),
    staleTime: 60_000,
  });
  const refresh = () => {
    void unallocatedQ.refetch();
    void assignedQ.refetch();
    void execQ.refetch();
  };
  const assigned = assignedQ.data ?? [];
  const myId = Number(me.data?.id);
  const groups = [
    { title: "Unallocated", apps: unallocatedQ.data ?? [], actions: (app: ApplicationView) => <AssignActions app={app} /> },
    { title: "Assigned to me", apps: assigned.filter((app) => app.assignedExecutiveId === myId), actions: (app: ApplicationView) => <CreditDecisionActions app={app} /> },
    ...(execQ.data ?? []).map((executive) => ({
      title: executive.name,
      apps: assigned.filter((app) => app.assignedExecutiveId === executive.id),
      actions: (app: ApplicationView) => <CreditDecisionActions app={app} />,
    })),
  ];
  const knownIds = new Set([myId, ...(execQ.data ?? []).map((executive) => executive.id)]);
  const unknown = assigned.filter((app) => app.assignedExecutiveId == null || !knownIds.has(app.assignedExecutiveId));
  if (unknown.length) groups.push({ title: "Other assignees", apps: unknown, actions: (app) => <CreditDecisionActions app={app} /> });

  return (
    <div className="space-y-5">
      {groups.map((group) => (
        <QueuePanel
          key={group.title}
          title={group.title}
          countBadge={group.title}
          apps={group.apps}
          isLoading={unallocatedQ.isLoading || assignedQ.isLoading || execQ.isLoading}
          error={unallocatedQ.error || assignedQ.error || execQ.error}
          onRefresh={refresh}
          actions={group.actions}
          info="Credit review remains one stage. The Credit Head may decide any file or reassign it; executives can decide only their own files."
        />
      ))}
    </div>
  );
}

export function QueuePanel({
  title,
  countBadge,
  apps,
  isLoading,
  error,
  onRefresh,
  actions,
  info,
  withLoanHistory,
}: {
  title: string;
  countBadge: string;
  apps: ApplicationView[];
  isLoading: boolean;
  error: unknown;
  onRefresh: () => void;
  actions: (app: ApplicationView) => React.ReactNode;
  info?: string;
  withLoanHistory?: boolean;
}) {
  return (
    <section className="rounded border border-line bg-white shadow-sm">
      <header className="flex items-center justify-between gap-3 border-b border-line px-5 py-3">
        <div className="flex items-center gap-2">
          <h2 className="font-serif text-lg font-semibold text-navy">{title}</h2>
          {info && <InfoTooltip content={info} />}
          {isLoading && <Loader2 size={15} className="animate-spin text-muted" />}
          <span className="rounded-full bg-navy-tint px-2.5 py-0.5 text-xs font-semibold text-navy">{apps.length}</span>
        </div>
        <button
          onClick={onRefresh}
          className="flex items-center gap-1.5 rounded border border-line px-3 py-1.5 text-xs text-muted hover:bg-grey-100 hover:text-ink"
        >
          <RefreshCw size={13} /> Refresh
        </button>
      </header>

      {error ? (
        <p className="px-5 py-4 text-sm text-error-700">{errMessage(error)}</p>
      ) : apps.length === 0 ? (
        <p className="px-5 py-6 text-center text-sm text-muted">
          Nothing in the <code className="text-xs">{countBadge}</code> queue.
        </p>
      ) : (
        <ul className="divide-y divide-line">
          {apps.map((app) => (
            <AppRow key={app.id} app={app} actions={actions} withLoanHistory={withLoanHistory} />
          ))}
        </ul>
      )}
    </section>
  );
}
