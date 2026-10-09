"use client";

import * as React from "react";
import { useMutation, useMutationState, useQuery, useQueryClient } from "@tanstack/react-query";
import { Loader2, RefreshCw, Bell, UserPlus, Send, AlertTriangle } from "lucide-react";
import { ConfirmDialog, EmptyState, ErrorState, Skeleton, StatusBadge, toast } from "@/components/ui";
import { PageHeader } from "@/components/staff/staff-ui";
import { NoAccessNotice, errMessage, useStaffMe, useCan } from "@/components/staff/live-pipeline";
import { ApplicationDetailDialog } from "@/components/staff/application-detail-dialog";
import { CustomerOwnerPicker } from "@/components/staff/customer-owner-picker";
import { customersApi, staffApi, type TelecallingView } from "@/lib/api/applications";
import { usePagination, PaginationBar } from "@/components/staff/pipeline/pagination";
import {
  assignedToOthersLabel,
  completenessPercent,
  countAssignedToOthers,
  isPageFullySelected,
  togglePageSelection,
} from "@/lib/telecalling/telecalling-queue";

const TELECALLER_ONLY = ["TELECALLER"] as const;

// Keys so every in-flight call can be read back (useMutationState), not just the latest one.
const ASSIGN_MUTATION_KEY = ["telecalling-assign-to-me"] as const;
const REMIND_MUTATION_KEY = ["telecalling-send-reminder"] as const;

/**
 * The ids with a call of this kind still in flight. `useMutation`'s own `variables` only tracks the
 * latest call, so a second click on another row re-enabled the first row's button mid-request.
 */
function usePendingIds(mutationKey: readonly string[]): ReadonlySet<number> {
  const ids = useMutationState({
    filters: { mutationKey: [...mutationKey], status: "pending" },
    select: (m) => m.state.variables as number,
  });
  return React.useMemo(() => new Set(ids), [ids]);
}

/**
 * Telecaller queue — every pre-`SANCTIONED` application, split into Unallocated (no
 * `customer_owner` row) and My customers (owned by the signed-in staffer), stale-first by default.
 * Distinct from the older DSA-style `/staff/leads` intake — this queue chases live applications
 * stuck in the funnel, not fresh walk-in leads.
 */
export default function TelecallingPage() {
  const me = useStaffMe().data;
  const can = useCan();
  const qc = useQueryClient();
  const q = useQuery({
    // Two minutes, not fifteen seconds. A lead queue's staleness is measured in days — an
    // application sits here until somebody phones the borrower — so a 15s poll re-fetched the same
    // rows ~480 times an hour to show nothing new. Worse, this endpoint answers in 13-15s, so the
    // next poll fired as the previous one landed and the page was never once idle. The Refresh
    // button below and the assign mutation's invalidation cover the moments that actually change
    // something.
    queryKey: ["staff-telecalling"],
    queryFn: () => staffApi.telecalling(),
    refetchInterval: 120_000,
  });

  const [infoId, setInfoId] = React.useState<number | null>(null);
  const [results, setResults] = React.useState<Record<number, string>>({});
  // A failed "Assign to me", by customer id, shown beside that row's button. It used to fail
  // silently: no onError, and the mutation's error was never rendered.
  const [assignErrors, setAssignErrors] = React.useState<Record<number, string>>({});

  const myId = me?.id != null ? Number(me.id) : null;

  const assign = useMutation({
    mutationKey: ASSIGN_MUTATION_KEY,
    mutationFn: (customerId: number) => customersApi.assignOwner(customerId, myId),
    onMutate: (customerId) => {
      setAssignErrors((r) => {
        if (!(customerId in r)) return r;
        const next = { ...r };
        delete next[customerId];
        return next;
      });
    },
    onSuccess: () => {
      toast.success("Customer assigned to you");
      // Returned, as before, so the button keeps spinning until the queue has refetched.
      return qc.invalidateQueries({ queryKey: ["staff-telecalling"] });
    },
    onError: (err, customerId) => setAssignErrors((r) => ({ ...r, [customerId]: errMessage(err) })),
  });
  const assigning = usePendingIds(ASSIGN_MUTATION_KEY);

  const remind = useMutation({
    mutationKey: REMIND_MUTATION_KEY,
    mutationFn: (id: number) => staffApi.sendReminder(id),
    onSuccess: (res, id) => {
      // The outcome used to be an inline line that never cleared; it is a toast now, and only a
      // failure stays on the row. The endpoint publishes the notification event rather than
      // delivering it, so a success is "queued", not "sent".
      setResults((r) => {
        if (!(id in r)) return r;
        const next = { ...r };
        delete next[id];
        return next;
      });
      if (res.sent) toast.success(`Reminder queued for #${id}`);
      else toast.info(`Nothing pending for #${id}`);
    },
    onError: (err, id) => setResults((r) => ({ ...r, [id]: errMessage(err) })),
  });
  const reminding = usePendingIds(REMIND_MUTATION_KEY);

  if (me && !can("leads:manage")) {
    return <NoAccessNotice message="Telecalling access only (TELECALLER / ADMIN)." />;
  }

  const rows = q.data ?? [];
  const unallocated = rows
    .filter((r) => r.ownerStaffId == null)
    .sort((a, b) => b.staleDays - a.staleDays);
  const mine = rows
    .filter((r) => myId != null && r.ownerStaffId === myId)
    .sort((a, b) => b.staleDays - a.staleDays);
  // Neither section lists rows another staffer owns; the operator should still know they exist.
  const assignedToOthers = countAssignedToOthers(rows, myId);

  return (
    <div>
      <PageHeader
        title="Telecalling"
        subtitle="Pre-sanction applications that need a chase-up call — stalest first."
      >
        <button
          onClick={() => q.refetch()}
          className="flex items-center gap-1.5 rounded border border-line px-3 py-1.5 text-xs text-muted hover:bg-grey-100 hover:text-ink"
        >
          {q.isFetching ? <Loader2 size={13} className="animate-spin" /> : <RefreshCw size={13} />} Refresh
        </button>
      </PageHeader>

      {q.isLoading ? (
        <div className="rounded border border-line bg-white shadow-sm">
          <Skeleton variant="table" rows={8} cols={12} />
        </div>
      ) : q.error ? (
        <ErrorState error={q.error} onRetry={() => void q.refetch()} />
      ) : (
        <div className="space-y-6">
          <TelecallingSection
            title="Unallocated"
            info="Pre-sanction applications with no assigned telecaller. Assign to yourself to start chasing them."
            rows={unallocated}
            assignedToOthers={assignedToOthers}
            onOpen={setInfoId}
            onAssignToMe={myId != null ? (customerId) => assign.mutate(customerId) : undefined}
            assigning={assigning}
            assignErrors={assignErrors}
            onRemind={(id) => remind.mutate(id)}
            reminding={reminding}
            results={results}
          />
          <TelecallingSection
            title="My customers"
            info="Applications currently assigned to you."
            rows={mine}
            assignedToOthers={assignedToOthers}
            onOpen={setInfoId}
            onRemind={(id) => remind.mutate(id)}
            reminding={reminding}
            results={results}
          />
        </div>
      )}

      <ApplicationDetailDialog applicationId={infoId} initialTab="customer" onClose={() => setInfoId(null)} />
    </div>
  );
}

const NO_IDS: ReadonlySet<number> = new Set();
const NO_ERRORS: Record<number, string> = {};

function TelecallingSection({
  title,
  info,
  rows,
  assignedToOthers,
  onOpen,
  onAssignToMe,
  assigning = NO_IDS,
  assignErrors = NO_ERRORS,
  onRemind,
  reminding,
  results,
}: {
  title: string;
  info: string;
  rows: TelecallingView[];
  /** Rows owned by other staff, hidden from both sections; `null` while the staffer is unknown. */
  assignedToOthers: number | null;
  onOpen: (id: number) => void;
  onAssignToMe?: (customerId: number) => void;
  /** Customer ids with an "Assign to me" in flight. */
  assigning?: ReadonlySet<number>;
  /** A failed "Assign to me", by customer id. */
  assignErrors?: Record<number, string>;
  onRemind: (id: number) => void;
  /** Application ids with a reminder in flight. */
  reminding: ReadonlySet<number>;
  results: Record<number, string>;
}) {
  const [selected, setSelected] = React.useState<Set<number>>(new Set());
  const [bulkBusy, setBulkBusy] = React.useState(false);
  const [bulkDone, setBulkDone] = React.useState(0);
  // The ids the confirm dialog is asking about, snapshotted when it opens — so the run sends exactly
  // the count the dialog stated, even if a poll resets the live selection while it is open.
  const [confirmIds, setConfirmIds] = React.useState<number[] | null>(null);
  const { pageRows, page, setPage, pageSize, setPageSize, pageCount, total } = usePagination(rows);

  React.useEffect(() => setSelected(new Set()), [rows.length]);

  const toggle = (id: number) => {
    setSelected((s) => {
      const next = new Set(s);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  };
  // The visible page only. It used to tick every row in the section across all pages, so a
  // reminder run could reach borrowers the operator had never seen on screen.
  const pageIds = pageRows.map((r) => r.id);
  const pageAllSelected = isPageFullySelected(selected, pageIds);
  const togglePage = () => setSelected((s) => togglePageSelection(s, pageIds));

  const sendToSelected = async (ids: number[]) => {
    setBulkBusy(true);
    setBulkDone(0);
    let queued = 0;
    let nothingPending = 0;
    let failed = 0;
    for (const id of ids) {
      try {
        const res = await staffApi.sendReminder(id);
        if (res.sent) queued += 1;
        else nothingPending += 1;
      } catch {
        /* per-row failure is fine — keep going, no batch abort */
        failed += 1;
      }
      setBulkDone((n) => n + 1);
    }
    setBulkBusy(false);
    setSelected(new Set());
    setConfirmIds(null);
    // Only what the loop observed: a resolved call that published the event is "queued" (never
    // "sent" — delivery is async), `sent: false` means no verification step was pending.
    const parts = [`Reminders queued for ${queued} of ${ids.length}`];
    if (nothingPending > 0) parts.push(`${nothingPending} had nothing pending`);
    if (failed > 0) parts.push(`${failed} failed`);
    const summary = parts.join(" — ");
    if (failed > 0) toast.error(summary);
    else toast.success(summary);
  };
  const confirmCount = confirmIds?.length ?? 0;
  // Rows ticked one by one stay ticked across paging, so say how many of the run are out of view.
  const confirmOffPage = confirmIds
    ? confirmIds.filter((id) => !pageRows.some((r) => r.id === id)).length
    : 0;

  return (
    <section className="rounded border border-line bg-white shadow-sm">
      <header className="flex flex-wrap items-center justify-between gap-3 border-b border-line px-5 py-3">
        <div>
          <div className="flex items-center gap-2">
            <h2 className="font-serif text-lg font-semibold text-navy">{title}</h2>
            <span className="rounded-full bg-navy-tint px-2.5 py-0.5 text-xs font-semibold text-navy">{rows.length}</span>
            <span className="hidden text-xs text-muted sm:inline" title={info}>
              {info}
            </span>
          </div>
          {assignedToOthers != null && assignedToOthers > 0 && (
            <p className="m-0 mt-0.5 text-xs text-muted">{assignedToOthersLabel(assignedToOthers)}</p>
          )}
        </div>
        {selected.size > 0 && (
          <button
            type="button"
            onClick={() => setConfirmIds([...selected])}
            disabled={bulkBusy}
            className="btn btn-sm btn-navy disabled:opacity-50"
          >
            <Send size={13} />
            {bulkBusy ? `Sending… (${bulkDone}/${selected.size})` : `Send to selected (${selected.size})`}
          </button>
        )}
      </header>

      {rows.length === 0 ? (
        <EmptyState title="Nothing here." />
      ) : (
        <div>
          <div className="staff-table-scroll">
          <table className="staff-data-table">
            <caption className="sr-only">{title}</caption>
            <thead>
              <tr>
                <th scope="col">S.No.</th>
                <th scope="col" className="staff-sticky-identity">
                  <input
                    type="checkbox"
                    checked={pageAllSelected}
                    onChange={togglePage}
                    aria-label="Select all on this page"
                  />
                </th>
                <th scope="col">Application</th>
                <th scope="col">Customer ID</th>
                <th scope="col">Customer</th>
                <th scope="col">Mobile</th>
                <th scope="col">Email</th>
                <th scope="col">PAN</th>
                <th scope="col">Status</th>
                <th scope="col" className="num">Completeness</th>
                <th scope="col" className="num">Stale (days)</th>
                <th scope="col" className="staff-sticky-actions">Actions</th>
              </tr>
            </thead>
            <tbody>
              {pageRows.map((r, i) => (
                <tr key={r.id}>
                  <td className="text-muted">{(page - 1) * pageSize + i + 1}</td>
                  <td className="staff-sticky-identity">
                    <input
                      type="checkbox"
                      checked={selected.has(r.id)}
                      onChange={() => toggle(r.id)}
                      aria-label={`Select application #${r.id}`}
                    />
                  </td>
                  <td>
                    <button type="button" onClick={() => onOpen(r.id)} className="font-semibold text-navy hover:underline">
                      #{r.id}
                    </button>
                  </td>
                  <td className="font-mono text-muted">#{r.customerId}</td>
                  <td className="staff-cell">
                    <button type="button" onClick={() => onOpen(r.id)} className="text-left font-semibold text-navy hover:underline">
                      {r.customerName || `Customer #${r.customerId}`}
                    </button>
                  </td>
                  <td className="font-mono text-muted">{r.mobile || "—"}</td>
                  <td className="text-muted">
                    {r.email || (
                      <span className="inline-flex items-center gap-1" title="No email on file">
                        <span aria-hidden="true">—</span>
                        <AlertTriangle size={12} className="text-warning-700" aria-hidden="true" />
                        <span className="sr-only">No email on file</span>
                      </span>
                    )}
                  </td>
                  <td className="font-mono text-ink">{r.pan || "—"}</td>
                  <td>
                    <StatusBadge kind="application" value={r.status} />
                  </td>
                  <td className="num whitespace-nowrap text-ink">
                    <span className="inline-flex items-center gap-2">
                      <span aria-hidden="true" className="relative h-1.5 w-[60px] overflow-hidden rounded-full bg-navy/10">
                        <span
                          className="absolute inset-y-0 left-0 rounded-full bg-navy"
                          style={{ width: `${completenessPercent(r.stepsCompleted, r.stepsRequired)}%` }}
                        />
                      </span>
                      <span>
                        {r.stepsCompleted}/{r.stepsRequired}
                      </span>
                    </span>
                  </td>
                  <td className="num">
                    <span className={r.staleDays >= 3 ? "font-semibold text-error-700" : "text-ink"}>
                      {r.staleDays}
                    </span>
                  </td>
                  <td className="staff-sticky-actions">
                    <div className="flex flex-wrap items-center gap-1.5">
                      {onAssignToMe && (
                        <button
                          type="button"
                          onClick={() => onAssignToMe(r.customerId)}
                          disabled={assigning.has(r.customerId)}
                          className="btn btn-sm btn-outline"
                          title="Assign this customer to me"
                        >
                          {assigning.has(r.customerId) ? (
                            <Loader2 size={13} className="animate-spin" />
                          ) : (
                            <UserPlus size={13} />
                          )}
                          Assign to me
                        </button>
                      )}
                      {onAssignToMe && assignErrors[r.customerId] && (
                        <span role="alert" className="text-xs text-error-700">
                          {assignErrors[r.customerId]}
                        </span>
                      )}
                      <CustomerOwnerPicker
                        customerId={r.customerId}
                        ownerStaffId={r.ownerStaffId}
                        allowedRoles={TELECALLER_ONLY}
                        compact
                      />
                      <button
                        type="button"
                        onClick={() => onRemind(r.id)}
                        disabled={reminding.has(r.id)}
                        className="btn btn-sm btn-outline"
                        title="Send a reminder for outstanding steps"
                      >
                        {reminding.has(r.id) ? <Loader2 size={13} className="animate-spin" /> : <Bell size={13} />}
                        Send reminder
                      </button>
                      {results[r.id] && <span className="text-xs text-muted">{results[r.id]}</span>}
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          </div>
          <PaginationBar page={page} pageCount={pageCount} setPage={setPage} total={total} pageSize={pageSize} setPageSize={setPageSize} />
        </div>
      )}

      <ConfirmDialog
        open={confirmIds != null}
        onClose={() => setConfirmIds(null)}
        onConfirm={() => {
          if (confirmIds) void sendToSelected(confirmIds);
        }}
        busy={bulkBusy}
        title={`Send ${confirmCount} reminder${confirmCount === 1 ? "" : "s"}?`}
        body={
          <>
            <p className="m-0">
              Each of the {confirmCount} selected application{confirmCount === 1 ? "" : "s"} gets a real
              reminder to the borrower on four channels — in-app, SMS, email and WhatsApp. There is no
              cooldown: a borrower reminded a minute ago is messaged again. Applications with no
              pending verification steps are skipped.
            </p>
            {confirmOffPage > 0 && (
              <p className="m-0 mt-2">
                {confirmOffPage} of them {confirmOffPage === 1 ? "is" : "are"} on another page of this list.
              </p>
            )}
            {bulkBusy && (
              <p className="m-0 mt-2 text-muted">
                Processed {bulkDone} of {confirmCount}…
              </p>
            )}
          </>
        }
        confirmLabel={`Send ${confirmCount} reminder${confirmCount === 1 ? "" : "s"}`}
      />
    </section>
  );
}
