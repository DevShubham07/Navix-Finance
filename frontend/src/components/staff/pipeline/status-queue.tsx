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
import { AssignActions } from "@/components/staff/pipeline/actions";

export function StatusQueue({
  title,
  status,
  actions,
  info,
  filter,
  withLoanHistory,
}: {
  title: string;
  status: ApplicationStatus;
  actions: (app: ApplicationView) => React.ReactNode;
  /** Optional ⓘ explanation shown beside the queue title. */
  info?: string;
  /** Optional client-side filter to split one status into sections (e.g. fast-track disbursement). */
  filter?: (app: ApplicationView) => boolean;
  /** Inline loan-history on each row — the reborrow-review queue's documented exception (see AppRow). */
  withLoanHistory?: boolean;
}) {
  const q = useQuery({
    queryKey: ["staff-queue", status],
    queryFn: () => staffApi.listByStatus(status),
    refetchInterval: 8000,
  });

  const apps = filter ? (q.data ?? []).filter(filter) : q.data ?? [];

  return (
    <QueuePanel
      title={title}
      countBadge={status}
      apps={apps}
      isLoading={q.isLoading}
      error={q.error}
      onRefresh={() => q.refetch()}
      actions={actions}
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
      info="KYC-approved applications the borrower has applied on. Assign each to an ACTIVE Credit Executive to start the credit review."
    />
  );
}

function QueuePanel({
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
