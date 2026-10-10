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
import { EmptyState, ErrorState, InfoTooltip, Skeleton } from "@/components/ui";
import { staffApi, type ApplicationStatus, type ApplicationView } from "@/lib/api/applications";
import { AppRow } from "@/components/staff/pipeline/app-row";
import { AssignActions, CreditDecisionActions } from "@/components/staff/pipeline/actions";
import { useStaffMe } from "@/components/staff/pipeline/hooks";
import { useQueueRange, useQueueQuery } from "@/components/staff/pipeline/queue-date-filter";
import { usePagination, PaginationBar } from "@/components/staff/pipeline/pagination";
import { useBulkQueue, BulkActionBar, type QueueSelection, type RejectMode } from "@/components/staff/pipeline/bulk-actions";
import { bulkSelectionHint } from "@/lib/staff/queue-bulk";

export function StatusQueue({
  title,
  status,
  actions,
  info,
  filter,
  withLoanHistory,
  hideWhenEmpty,
  bulk,
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
  /**
   * Enable bulk select + assign/reject (see `pipeline/bulk-actions.tsx`). Omit for a queue that
   * offers neither — the table then renders exactly as it did before bulk actions existed.
   */
  bulk?: { rejectMode?: RejectMode; allowAssign?: boolean };
}) {
  const range = useQueueRange();
  const query = useQueueQuery();
  const q = useQuery({
    queryKey: ["staff-queue", status, range.from ?? "", range.to ?? "", query],
    queryFn: () => staffApi.listByStatus(status, range, query || undefined),
    refetchInterval: 8000,
  });

  const apps = React.useMemo(() => (filter ? (q.data ?? []).filter(filter) : q.data ?? []), [q.data, filter]);
  // Hook order must not depend on `bulk` being passed — call unconditionally with an empty config
  // when the caller opted out, which resolves to `selection: undefined` (no checkbox column).
  const { selection, dialogs } = useBulkQueue(
    React.useMemo(() => apps.map((a) => a.id), [apps]),
    bulk ?? {},
  );

  if (hideWhenEmpty && !q.isLoading && !q.error && apps.length === 0) return null;

  return (
    <>
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
        selection={selection}
      />
      {dialogs}
    </>
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
      actions={(app) => <AssignActions app={app} compact />}
      info="Submitted intakes awaiting a credit review. Assign each to an ACTIVE Credit Executive — their decision is the final one."
    />
  );
}

export function CreditWorkbench() {
  const me = useStaffMe();
  const range = useQueueRange();
  const query = useQueueQuery();
  const unallocatedQ = useQuery({
    queryKey: ["staff-queue", "credit-queue", range.from ?? "", range.to ?? "", query],
    queryFn: () => staffApi.creditQueue(range, query || undefined),
    refetchInterval: 8000,
  });
  const assignedQ = useQuery({
    queryKey: ["staff-queue", "CREDIT_EXEC_PENDING", range.from ?? "", range.to ?? "", query],
    queryFn: () => staffApi.listByStatus("CREDIT_EXEC_PENDING", range, query || undefined),
    refetchInterval: 8000,
  });
  // The active-executive roster is static reference data — it changes when an admin activates or
  // deactivates someone, not between visits to this page — so it is not refetched on every mount.
  // The panel's Refresh button still refetches it explicitly (below).
  const execQ = useQuery({
    queryKey: ["staff-executives"],
    queryFn: () => staffApi.creditExecutives(),
    staleTime: 15 * 60_000,
  });
  const refresh = () => {
    void unallocatedQ.refetch();
    void assignedQ.refetch();
    void execQ.refetch();
  };
  const assigned = assignedQ.data ?? [];
  const myId = Number(me.data?.id);
  // Keyed by staff id, never by name: two active executives can genuinely share a display name,
  // and keying on the title made React collapse their panels into one ("two children with the
  // same key") — one executive's queue silently disappeared.
  // Unallocated rows get no row-level control. `AssignActions compact` renders nothing (assignment
  // happens in the detail dialog), but mounting it per row also mounts its own ["staff-executives"]
  // observer at a 60s staleTime, which refetches the roster on every workbench mount and defeats the
  // 15-minute staleTime above. The rendered row is identical.
  const groups: {
    key: string;
    title: string;
    apps: ApplicationView[];
    actions: (app: ApplicationView) => React.ReactNode;
  }[] = [
    { key: "unallocated", title: "Unallocated", apps: unallocatedQ.data ?? [], actions: () => null },
    { key: "mine", title: "Assigned to me", apps: assigned.filter((app) => app.assignedExecutiveId === myId), actions: (app: ApplicationView) => <CreditDecisionActions app={app} compact /> },
    ...(execQ.data ?? []).map((executive) => ({
      key: `exec-${executive.id}`,
      title: executive.name,
      apps: assigned.filter((app) => app.assignedExecutiveId === executive.id),
      actions: (app: ApplicationView) => <CreditDecisionActions app={app} compact />,
    })),
  ];
  const knownIds = new Set([myId, ...(execQ.data ?? []).map((executive) => executive.id)]);
  const unknown = assigned.filter((app) => app.assignedExecutiveId == null || !knownIds.has(app.assignedExecutiveId));
  if (unknown.length) groups.push({ key: "other", title: "Other assignees", apps: unknown, actions: (app) => <CreditDecisionActions app={app} compact /> });

  return (
    <div className="space-y-5">
      {groups.map((group) => (
        <CreditGroupPanel
          key={group.key}
          title={group.title}
          apps={group.apps}
          isLoading={unallocatedQ.isLoading || assignedQ.isLoading || execQ.isLoading}
          error={unallocatedQ.error || assignedQ.error || execQ.error}
          onRefresh={refresh}
          actions={group.actions}
          // Only the unallocated group is an assignment queue — the other groups are already
          // assigned, so bulk-assign there would just be a confusing reassign-in-bulk.
          allowAssign={group.key === "unallocated"}
        />
      ))}
    </div>
  );
}

/**
 * One {@link CreditWorkbench} group, wrapped so each gets its own `useBulkQueue` (hooks can't run
 * inside the `.map` above — the group list's length itself changes as executives come and go).
 */
function CreditGroupPanel({
  title,
  apps,
  isLoading,
  error,
  onRefresh,
  actions,
  allowAssign,
}: {
  title: string;
  apps: ApplicationView[];
  isLoading: boolean;
  error: unknown;
  onRefresh: () => void;
  actions: (app: ApplicationView) => React.ReactNode;
  allowAssign: boolean;
}) {
  const { selection, dialogs } = useBulkQueue(
    React.useMemo(() => apps.map((a) => a.id), [apps]),
    { rejectMode: "credit", allowAssign },
  );
  return (
    <>
      <QueuePanel
        title={title}
        countBadge={title}
        apps={apps}
        isLoading={isLoading}
        error={error}
        onRefresh={onRefresh}
        actions={actions}
        info="Credit review remains one stage. The Credit Head may decide any file or reassign it; executives can decide only their own files."
        selection={selection}
        caption={`Credit review — ${title}`}
      />
      {dialogs}
    </>
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
  selection,
  caption,
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
  /** Bulk select + assign/reject — see `pipeline/bulk-actions.tsx`. Omit for the plain table. */
  selection?: QueueSelection;
  /** The table's visually-hidden caption; defaults to `title`. */
  caption?: string;
}) {
  // Only while nothing is ticked and there is something to tick; once rows are selected the bulk
  // bar (count + buttons) takes its place. Names only the bulk actions this queue offers the role.
  const bulkHint =
    selection && selection.selected.size === 0 && apps.length > 0
      ? bulkSelectionHint({ canAssign: selection.onAssign != null, canReject: selection.onReject != null })
      : null;
  return (
    <section className="rounded border border-line bg-white shadow-sm">
      <header className="flex flex-wrap items-center justify-between gap-3 border-b border-line px-5 py-3">
        <div className="flex items-center gap-2">
          <h2 className="font-serif text-lg font-semibold text-navy">{title}</h2>
          {info && <InfoTooltip content={info} />}
          {isLoading && <Loader2 size={15} className="animate-spin text-muted" />}
          <span data-redact className="rounded-full bg-navy-tint px-2.5 py-0.5 text-xs font-semibold text-navy">{apps.length}</span>
        </div>
        <div className="flex items-center gap-3">
          {bulkHint && <span className="text-xs text-muted">{bulkHint}</span>}
          {selection && (
            <BulkActionBar count={selection.selected.size} onAssign={selection.onAssign} onReject={selection.onReject} />
          )}
          <button
            onClick={onRefresh}
            className="flex items-center gap-1.5 rounded border border-line px-3 py-1.5 text-xs text-muted hover:bg-grey-100 hover:text-ink"
          >
            <RefreshCw size={13} /> Refresh
          </button>
        </div>
      </header>

      {/* First load only (`isLoading`, never a background poll), so the 8s refetch leaves the rows
          in place. Five rows, not a full page: several of these panels stack on one console and
          most queues are short, so a taller placeholder would only collapse on arrival. Before
          this, the panel claimed "Nothing in the … queue" while it was still loading. */}
      {isLoading ? (
        <Skeleton variant="table" rows={5} cols={selection ? 19 : 18} />
      ) : error ? (
        <ErrorState error={error} onRetry={onRefresh} />
      ) : apps.length === 0 ? (
        <EmptyState title={`Nothing in the ${countBadge} queue.`} />
      ) : (
        <QueueTable apps={apps} actions={actions} withLoanHistory={withLoanHistory} selection={selection} caption={caption ?? title} />
      )}
    </section>
  );
}

/**
 * The queue grid itself, shared by every panel that lists applications.
 *
 * Identifiers first (application, customer, mobile, loan), then the triage facts. It fits its panel
 * at laptop widths because the *controls* moved into `ApplicationDetailDialog` rather than because
 * information was dropped; see {@link AppRow}.
 */
export function QueueTable({
  apps,
  actions,
  withLoanHistory,
  showJourney = true,
  selection,
  caption,
}: {
  apps: ApplicationView[];
  actions: (app: ApplicationView) => React.ReactNode;
  withLoanHistory?: boolean;
  showJourney?: boolean;
  /** Bulk select + assign/reject — see `pipeline/bulk-actions.tsx`. Omitted, this table renders
   *  byte-identical to before bulk actions existed: no checkbox column, no layout shift. */
  selection?: QueueSelection;
  /** Names the queue for assistive tech (a visually-hidden `<caption>`). Defaults to a generic name. */
  caption?: string;
}) {
  const { pageRows, page, setPage, pageSize, setPageSize, pageCount, total } = usePagination(apps);

  return (
    <div>
      <div className="staff-table-scroll">
        <table className="staff-data-table">
          <caption className="sr-only">{caption ?? "Applications"}</caption>
          <thead>
            <tr>
              <th scope="col">S.No.</th>
              {selection && (
                <th scope="col" className="staff-sticky-identity">
                  {/* Visible word, not just a bare box: the column is otherwise unlabelled and the
                      bulk actions it unlocks go unnoticed. The aria-label stays the precise name. */}
                  <label className="inline-flex cursor-pointer items-center gap-1.5">
                    <input
                      type="checkbox"
                      checked={selection.allSelected}
                      onChange={selection.toggleAll}
                      aria-label="Select all"
                    />
                    <span aria-hidden>Select</span>
                  </label>
                </th>
              )}
              <th scope="col" className={selection ? undefined : "staff-sticky-identity"}>Application</th>
              <th scope="col">Customer ID</th>
              <th scope="col">Date</th>
              <th scope="col">Customer</th>
              <th scope="col">Signals</th>
              <th scope="col">Mobile</th>
              <th scope="col">PAN</th>
              <th scope="col">Account</th>
              <th scope="col">IFSC</th>
              <th scope="col">Loan</th>
              <th scope="col" className="num">Amount</th>
              <th scope="col">Due</th>
              <th scope="col">Credit</th>
              {/* Who worked the file. "Credit exec" is who DECIDED it — on a reassigned or
                  Head-decided file that is not the assignee. Blank until the stage happens. */}
              <th scope="col" title="The credit executive who decided this file">Credit exec</th>
              <th scope="col" title="Who released the money">Disbursed by</th>
              <th scope="col" title="The assigned collections executive">Collections exec</th>
              <th scope="col" className="staff-sticky-actions">Actions</th>
            </tr>
          </thead>
          <tbody>
            {pageRows.map((app, i) => (
              <AppRow
                key={app.id}
                app={app}
                actions={actions}
                withLoanHistory={withLoanHistory}
                showJourney={showJourney}
                index={(page - 1) * pageSize + i}
                selected={selection?.selected.has(app.id)}
                onToggleSelect={selection ? () => selection.toggle(app.id) : undefined}
              />
            ))}
          </tbody>
        </table>
      </div>
      <PaginationBar
        page={page}
        pageCount={pageCount}
        setPage={setPage}
        total={total}
        pageSize={pageSize}
        setPageSize={setPageSize}
      />
    </div>
  );
}
