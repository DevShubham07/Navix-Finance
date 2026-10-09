"use client";

import * as React from "react";
import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { Loader2, RefreshCw, X, ChevronRight, ChevronDown } from "lucide-react";
import { Dialog } from "@/components/ui/dialog";
import { PageHeader } from "@/components/staff/staff-ui";
import { SearchBar } from "@/components/staff/search-bar";
import { PermissionGate, NoAccessNotice } from "@/components/staff/live-pipeline";
import { VerificationChecksPanel } from "@/components/staff/verification-checks";
import { staffApi } from "@/lib/api/applications";
import { PaginationBar } from "@/components/staff/pipeline/pagination";
import { EmptyState, ErrorState, Skeleton, StatusBadge } from "@/components/ui";
import { formatDateTime } from "@/lib/utils";
import {
  buildVerificationCards,
  formatCheckedAgo,
  groupVerificationCards,
  type VerificationAppCard as AppCard,
  type VerificationBucket as Bucket,
} from "@/lib/staff/verification-dashboard";

/**
 * The four application-wise buckets, in triage priority order. The roll-up rules (which checks gate,
 * which statuses count) live in `lib/staff/verification-dashboard.ts`, mirrored by the backend.
 */
const BUCKETS: { key: Bucket; label: string; accent: string }[] = [
  { key: "failures", label: "Has failures", accent: "text-error-700" },
  { key: "awaiting", label: "Awaiting borrower steps", accent: "text-warning-800" },
  { key: "passed", label: "All checks passed", accent: "text-success-700" },
  { key: "notStarted", label: "Not started", accent: "text-muted" },
];

/**
 * Verification dashboard: every application that needs a KYC decision, grouped **application-wise**
 * into triage buckets (has failures → awaiting borrower → all passed → not started). Each card opens
 * the shared {@link VerificationChecksPanel}, where a KYC approver can override a check with remarks.
 * "Not started" comes from the server (`notStarted`), computed over the whole undecided queue.
 */
export default function VerificationsDashboardPage() {
  const [query, setQuery] = React.useState("");
  const [selected, setSelected] = React.useState<AppCard | null>(null);
  // Paging is the SERVER's now: it pages by application (an application's checks are never split
  // across pages), and by default returns only the ones that still need a reviewer.
  const [page, setPage] = React.useState(1);
  const [pageSize, setPageSize] = React.useState(25);
  const [includeCleared, setIncludeCleared] = React.useState(false);
  // Collapsed buckets — this visit only, deliberately not persisted.
  const [collapsed, setCollapsed] = React.useState<ReadonlySet<Bucket>>(() => new Set());
  const toggleBucket = (key: Bucket) =>
    setCollapsed((prev) => {
      const next = new Set(prev);
      if (next.has(key)) next.delete(key);
      else next.add(key);
      return next;
    });

  // Any change to what is being asked for puts you back at the first page — page 4 of the old
  // filter is a meaningless offset into the new one.
  React.useEffect(() => {
    setPage(1);
  }, [includeCleared]);

  const q = useQuery({
    queryKey: ["staff-verif-overview", query, page, pageSize, includeCleared],
    queryFn: () =>
      staffApi.verificationOverview({
        q: query || undefined,
        // `undefined` is the server's default (needs-attention only); only ask for the whole
        // undecided queue when the reviewer opts in.
        needsAttention: includeCleared ? false : undefined,
        page,
        size: pageSize,
      }),
    // 45s: this is a triage board, not a maker-checker desk — a verification result arrives from a
    // provider callback minutes after the borrower acts, so a 15s poll re-fetched the same board
    // three times for every change it could possibly show.
    refetchInterval: 45_000,
    // Paging swaps the query key, so without this every Next/Prev blanked the buckets back to the
    // loading skeleton.
    placeholderData: keepPreviousData,
  });
  const data = q.data;
  // `total` counts matching APPLICATIONS — the unit the server pages by — so the bar can't be
  // derived from `rows.length` (one application contributes many rows).
  const total = data?.total ?? 0;
  const pageCount = Math.max(1, Math.ceil(total / pageSize));

  // "Not started" comes from the server, computed over the whole undecided queue — never derived
  // from this page's rows, which once showed a file whose checks were on another page (or that was
  // fully cleared and so filtered out) as "never verified".
  const notStarted = data?.notStarted;
  const notStartedTotal = data?.notStartedTotal ?? 0;
  const cards = React.useMemo<AppCard[]>(
    () => buildVerificationCards(data?.rows ?? [], notStarted ?? []),
    [data?.rows, notStarted],
  );
  const grouped = React.useMemo(() => groupVerificationCards(cards), [cards]);
  const searchTerm = query.trim();
  // "Last checked … ago" is measured against when this board was fetched (a pure value that moves
  // with every 45s poll), not a clock read during render.
  const fetchedAt = q.dataUpdatedAt;

  return (
    <div>
      <PageHeader
        title="Verification dashboard"
        subtitle={
          includeCleared
            ? "Every application still awaiting a KYC decision, grouped by where it stands — failures first, then awaiting the borrower, then cleared."
            : "Applications that need attention — the ones with a failed check or still waiting on the borrower. Tick “Include cleared” to see the fully-passed files too."
        }
      >
        <button
          onClick={() => void q.refetch()}
          className="flex items-center gap-1.5 rounded border border-line px-3 py-1.5 text-xs text-muted hover:bg-grey-100 hover:text-ink"
        >
          {q.isFetching ? <Loader2 size={13} className="animate-spin" /> : <RefreshCw size={13} />} Refresh
        </button>
      </PageHeader>

      <PermissionGate permission={["kyc:approve", "verification:retry"]} fallback={<NoAccessNotice />}>
        {/* The tallies are queue-wide by design; say so while a search narrows the cards below. */}
        {searchTerm && (
          <p className="mb-1.5 text-xs text-muted">Tallies (all undecided files) — the search does not narrow these.</p>
        )}
        <div className="mb-4 grid grid-cols-2 gap-3 sm:grid-cols-5">
          <Tile label="Pending" value={data?.pending} valueClass="text-muted" />
          <Tile label="Failed" value={data?.failed} valueClass="text-error-700" />
          <Tile label="In review" value={data?.review} valueClass="text-warning-800" />
          <Tile label="Passed" value={data?.passed} valueClass="text-success-700" />
          <Tile label="Never run" value={data?.neverRun} valueClass="text-muted" />
        </div>

        <div className="mb-4 flex flex-wrap items-center justify-end gap-3">
          <label className="flex items-center gap-2 text-xs text-muted">
            <input
              type="checkbox"
              checked={includeCleared}
              onChange={(e) => setIncludeCleared(e.target.checked)}
            />
            Include cleared
          </label>
          <SearchBar
            initialValue={query}
            onSearch={(t) => {
              setQuery(t);
              setPage(1);
            }}
            placeholder="Search borrower / app # / customer #"
            ariaLabel="Search by borrower / application / customer id"
            inputClassName="w-72"
          />
        </div>

        {q.isLoading ? (
          // The board is a grid of application cards, so the placeholder is too.
          <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
            {Array.from({ length: 6 }).map((_, i) => (
              <Skeleton key={i} variant="row" />
            ))}
          </div>
        ) : q.error ? (
          <div className="rounded border border-line bg-white shadow-sm">
            <ErrorState error={q.error} onRetry={() => void q.refetch()} className="py-4" />
          </div>
        ) : cards.length === 0 ? (
          <div className="rounded border border-line bg-white shadow-sm">
            <EmptyState
              title={`No applications need attention${searchTerm ? ` for “${searchTerm}”` : ""}.`}
              hint={!includeCleared ? "Tick “Include cleared” to see the files that have already passed." : undefined}
            />
          </div>
        ) : (
          <div className="space-y-6">
            {BUCKETS.map((b) => {
              const list = grouped[b.key];
              if (list.length === 0) return null;
              const isCollapsed = collapsed.has(b.key);
              const panelId = `verif-bucket-${b.key}`;
              const capped = b.key === "notStarted" && notStartedTotal > list.length;
              return (
                <section key={b.key}>
                  <h2 className="mb-2 text-sm font-semibold text-navy">
                    <button
                      type="button"
                      onClick={() => toggleBucket(b.key)}
                      aria-expanded={!isCollapsed}
                      aria-controls={panelId}
                      className="flex items-center gap-2 rounded pr-1 hover:bg-grey-100"
                    >
                      <ChevronDown
                        size={14}
                        aria-hidden
                        className={`text-muted transition-transform ${isCollapsed ? "-rotate-90" : ""}`}
                      />
                      <span className={b.accent}>{b.label}</span>
                      <span className="rounded-full bg-grey-100 px-2 py-0.5 text-xs font-semibold text-muted">{list.length}</span>
                    </button>
                  </h2>
                  <div id={panelId} hidden={isCollapsed}>
                    {capped && (
                      <p className="mb-2 text-xs text-muted">
                        Showing {list.length} of {notStartedTotal.toLocaleString("en-IN")}, newest first. Search to find an older one.
                      </p>
                    )}
                    <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
                      {list.map((c) => (
                        <AppCardTile key={c.applicationId} card={c} fetchedAt={fetchedAt} onOpen={() => setSelected(c)} />
                      ))}
                    </div>
                  </div>
                </section>
              );
            })}
            {total > 0 && (
              <div className="rounded border border-line bg-white shadow-sm">
                <PaginationBar
                  page={page}
                  pageCount={pageCount}
                  setPage={setPage}
                  total={total}
                  pageSize={pageSize}
                  unitLabel="applications"
                  setPageSize={(size) => {
                    setPageSize(size);
                    setPage(1);
                  }}
                />
              </div>
            )}
          </div>
        )}
      </PermissionGate>

      {selected && (
        <Dialog
          open
          onClose={() => setSelected(null)}
          className="!max-w-3xl !w-[min(52rem,94vw)]"
          aria-label="Verification checks"
        >
          <div className="flex items-center justify-between gap-3 border-b border-line pb-3">
            <div>
              <h3 className="font-serif text-lg text-navy">
                {selected.borrowerName ?? (selected.customerId != null ? `Customer #${selected.customerId}` : "Application")}{" "}
                <span className="text-sm font-normal text-muted">app #{selected.applicationId}</span>
              </h3>
              {selected.customerId != null && (
                <p className="text-xs text-muted">
                  Customer #{selected.customerId}
                  {selected.borrowerMobile ? ` · ${selected.borrowerMobile}` : ""}
                </p>
              )}
            </div>
            <button
              onClick={() => setSelected(null)}
              className="rounded p-1 text-muted hover:bg-grey-100 hover:text-ink"
              aria-label="Close"
            >
              <X size={18} />
            </button>
          </div>
          <div className="max-h-[70vh] overflow-y-auto pr-1">
            <VerificationChecksPanel applicationId={selected.applicationId} />
          </div>
        </Dialog>
      )}
    </div>
  );
}

function Tile({ label, value, valueClass }: { label: string; value: number | undefined; valueClass: string }) {
  return (
    <div className="rounded border border-line bg-white p-4 shadow-sm">
      <div className="text-xs font-semibold uppercase tracking-wide text-muted">{label}</div>
      <div className={`mt-1 font-serif text-2xl font-bold ${valueClass}`}>{value ?? "—"}</div>
    </div>
  );
}

/**
 * The EPFO/UAN outcome as a standalone chip. Deliberately outside the passed/failed/pending counts:
 * this check gates nothing, so it must not move an application between buckets (see
 * `NON_GATING_CHECKS` in lib/staff/verification-dashboard.ts). A REVIEW here reads "we could not confirm employment", not "the borrower
 * still owes us a step" — hence the neutral wording rather than a warning colour. `kind="advisory"`
 * carries the same rule into the colour: PASS/FAIL stay out of the gating green/red, so an "EPFO
 * failed" chip never reads as a blocker on a file that is perfectly sanctionable.
 */
function EmploymentChip({ status }: { status: string | null }) {
  if (!status) return null;
  const label =
    status === "PASS" ? "EPFO ok" : status === "FAIL" ? "EPFO failed" : "EPFO unconfirmed";
  return (
    <StatusBadge kind="advisory" value={status}>
      {label}
    </StatusBadge>
  );
}

function AppCardTile({ card, fetchedAt, onOpen }: { card: AppCard; fetchedAt: number; onOpen: () => void }) {
  const pct = card.total > 0 ? Math.round((card.passed / card.total) * 100) : 0;
  const checkedAgo = fetchedAt > 0 ? formatCheckedAgo(card.lastUpdate, fetchedAt) : null;
  return (
    <button
      onClick={onOpen}
      className="group flex flex-col rounded border border-line bg-white p-4 text-left shadow-sm transition hover:border-navy/40 hover:shadow"
    >
      <div className="flex items-start justify-between gap-2">
        <div className="min-w-0">
          <div className="truncate font-semibold text-navy">
            {card.borrowerName ?? (card.customerId != null ? `Customer #${card.customerId}` : "—")}
          </div>
          <div className="text-xs text-muted">
            app #{card.applicationId}
            {card.customerId != null ? ` · cust #${card.customerId}` : ""}
            {card.borrowerMobile ? ` · ${card.borrowerMobile}` : ""}
          </div>
        </div>
        <ChevronRight size={16} className="mt-0.5 flex-shrink-0 text-muted transition group-hover:text-navy" />
      </div>

      {card.total > 0 ? (
        <>
          <div className="mt-3 flex items-center justify-between text-xs">
            <span className="font-semibold text-navy">{card.passed}/{card.total} passed</span>
            <span className="flex items-center gap-1.5">
              {card.failed > 0 && <span className="rounded-full bg-error-100 px-1.5 py-0.5 font-semibold text-error-700">{card.failed} failed</span>}
              {card.pendingReview > 0 && <span className="rounded-full bg-warning-100 px-1.5 py-0.5 font-semibold text-warning-800">{card.pendingReview} pending</span>}
              <EmploymentChip status={card.employmentStatus} />
            </span>
          </div>
          <div className="mt-1.5 h-1.5 w-full overflow-hidden rounded-full bg-grey-200">
            <div className="h-full rounded-full bg-success-600 transition-all" style={{ width: `${pct}%` }} />
          </div>
          {checkedAgo && <div className="mt-1 text-[8.8px] text-muted">Last checked {checkedAgo}</div>}
        </>
      ) : (
        <div className="mt-3 text-xs text-muted">No verification checks started yet.</div>
      )}

      <div className="mt-3 text-[8.8px] text-muted">
        {card.lastUpdate ? `Updated ${formatDateTime(card.lastUpdate)}` : "Awaiting first check"}
      </div>
    </button>
  );
}
