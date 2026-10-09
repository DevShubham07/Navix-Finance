"use client";

/**
 * Decision history (revamp.md Phase 2, decision 32).
 *
 * Everyone sees their own decisions; a Head can switch to a team member's; ADMIN can open anyone's.
 * The switcher only lists staff the server will actually allow — it's a convenience over the same
 * rule the backend enforces, not the gate itself.
 */

import { TrustStars } from "@/components/staff/trust-stars";
import * as React from "react";
import { usePathname, useRouter } from "next/navigation";
import { useQuery } from "@tanstack/react-query";
import { Loader2 } from "lucide-react";
import { PageHeader, StatCard } from "@/components/staff/staff-ui";
import { EmptyState, ErrorState, Select, Skeleton, StatusBadge } from "@/components/ui";
import { InfoTooltip } from "@/components/ui/tooltip";
import Link from "next/link";
import { PeriodPicker } from "@/components/staff/period-picker";
import { SearchBar } from "@/components/staff/search-bar";
import { useTableSort, SortableTh } from "@/components/staff/sortable-table";
import { ApplicationDetailDialog } from "@/components/staff/application-detail-dialog";
import { rangeFor, presetMatching, type Range } from "@/lib/period";
import { staffApi, paiseToINR, type DecisionView } from "@/lib/api/applications";
import { useStaffMe, errMessage } from "@/components/staff/pipeline/hooks";
import { customerPageHref } from "@/lib/customers/customer-page";
import { formatDate } from "@/lib/utils";
import { usePagination, PaginationBar } from "@/components/staff/pipeline/pagination";
import {
  decisionMatchesSearch,
  placeholderForSameIdentity,
  toDecisionRow,
  withDecisionHistoryParams,
  type DecisionRow,
} from "@/lib/staff/decision-history";

export default function MyDecisionsPage() {
  const me = useStaffMe();
  const router = useRouter();
  const pathname = usePathname();
  const [staffId, setStaffId] = React.useState("");
  const [preset, setPreset] = React.useState("all");
  const [custom, setCustom] = React.useState<Range>({});
  /** Client-side search over the rows already loaded — application id, customer id, name, PAN. */
  const [search, setSearch] = React.useState("");
  /** The application whose detail dialog is open in place (there is no `?open=` deep link to use). */
  const [openAppId, setOpenAppId] = React.useState<number | null>(null);
  /** Set once the URL has been read, so the write-back below cannot clobber it with the defaults. */
  const [seeded, setSeeded] = React.useState(false);

  // Seeded from `?staffId=&from=&to=` so the staff-performance table can link straight into one
  // person's history *for the period it was showing* — land on a different window and the totals
  // there would look wrong here. Read off `location` in an effect rather than via
  // `useSearchParams`, which would drag this page behind a Suspense boundary for static
  // prerendering; the controls below take over afterwards. The server still enforces who may be
  // opened.
  React.useEffect(() => {
    const params = new URLSearchParams(window.location.search);
    const requested = params.get("staffId");
    if (requested) setStaffId(requested);
    const from = params.get("from") ?? undefined;
    const to = params.get("to") ?? undefined;
    if (from || to) {
      setCustom({ from, to });
      setPreset(presetMatching({ from, to }));
    }
    setSeeded(true);
  }, []);

  const range: Range = React.useMemo(() => rangeFor(preset, custom), [preset, custom]);

  // …and written back on every change, so a reload or a shared link reopens the same person and
  // window. `replace`, not `push`: flipping the period five times must not cost five Back presses.
  React.useEffect(() => {
    if (!seeded) return;
    const current = new URLSearchParams(window.location.search).toString();
    const qs = withDecisionHistoryParams(current, { staffId, from: range.from, to: range.to });
    if (qs !== current) router.replace(qs ? `${pathname}?${qs}` : pathname, { scroll: false });
  }, [seeded, staffId, range.from, range.to, pathname, router]);

  // Only the roles that can actually inspect someone else's history need this list: it exists
  // solely to populate the "whose decisions" picker below, which renders only when it comes back
  // non-empty. For a Credit Executive, an Accountant, a Telecaller — everyone else — the request
  // was fired on every visit to return a list that could never be shown.
  // Working role, so an ADMIN working as a Head can; as an executive the server scopes to self.
  const canInspectOthers = me.data?.role === "CREDIT_HEAD" || me.data?.role === "COLLECTION_HEAD";
  const team = useQuery({
    queryKey: ["decisions-inspectable"],
    queryFn: () => staffApi.inspectableStaff(),
    staleTime: 60_000,
    enabled: canInspectOthers,
  });

  const q = useQuery({
    queryKey: ["decisions", staffId, range.from ?? "", range.to ?? ""],
    queryFn: () => staffApi.decisions(staffId ? Number(staffId) : undefined, range.from, range.to),
    // Keep the rows on screen across a PERIOD change only. Index 1 is whose history this is: a
    // different person clears to the skeleton, so nobody's decisions ever sit under another's name.
    placeholderData: placeholderForSameIdentity<DecisionView[]>(1, staffId),
  });

  // Whose totals the cards show: the picker's choice, or — with nothing picked — the caller's own
  // id. Sending NO staffId makes the backend aggregate the caller's whole visible roster
  // (DecisionHistoryService.rosterFor(null) → listEveryone() for ADMIN, the team for a Head), so
  // the cards would carry an arbitrary colleague's numbers under "your decisions" while the table
  // below stayed scoped to the caller. That whole-roster default is what /staff/performance is
  // built on, so the narrowing belongs here, on the page, not in the service.
  const summaryStaffId = staffId || me.data?.id || "";

  // The same aggregate the performance dashboard renders, narrowed to this one person, so the two
  // pages can never disagree about what someone did.
  const summaryQ = useQuery({
    // The resolved id is part of the key, so the cards refetch against the right person once
    // `me` lands rather than keeping whatever the first run produced.
    queryKey: ["decisions-summary", summaryStaffId, range.from ?? "", range.to ?? ""],
    queryFn: () => staffApi.performance(range.from, range.to, Number(summaryStaffId)),
    // `me` is itself a query: until it resolves there is no own-id to send, and firing without
    // one is precisely the roster-wide request this page must never make.
    enabled: !!summaryStaffId,
    retry: false,
  });
  // A named staffId narrows the roster server-side to that one person, so a successful response
  // holds exactly one row. Anything else is not the person on screen — render the cards as
  // unavailable rather than someone else's totals.
  const summaryRows = summaryQ.data?.rows;
  const stats = summaryRows?.length === 1 ? summaryRows[0] : undefined;
  const callTrackingSince = summaryQ.data?.callTrackingSince;
  const callsUntracked = !!callTrackingSince && !!range.to && range.to < callTrackingSince;
  const callsNote = callTrackingSince
    ? `Telecaller + collections calls. Calls have only been attributed to a person since ${callTrackingSince}; anything earlier isn't counted.`
    : undefined;

  const rows = React.useMemo(() => q.data ?? [], [q.data]);
  const others = (team.data ?? []).filter((s) => String(s.id) !== String(me.data?.id));
  // Search and sort are client-side: the whole window is already in memory, so a round-trip per
  // keystroke or header click would be slower and no more correct.
  const matched = React.useMemo(
    () => rows.filter((r) => decisionMatchesSearch(r, search)).map(toDecisionRow),
    [rows, search],
  );
  // Newest first by default — the order the server returns, so an untouched page reads as before.
  const { sorted, sortKey, dir, toggle } = useTableSort<DecisionRow>(matched, "atMs", "desc");
  const { pageRows, page, setPage, pageSize, setPageSize, pageCount, total } = usePagination(sorted);

  // Name the person on screen: arriving from the performance table you have picked someone
  // specific, and a page headed only "Decision history" gives no confirmation you opened the
  // right one.
  const whose = staffId ? others.find((s) => String(s.id) === staffId)?.name : null;

  return (
    <div className="space-y-6">
      <PageHeader
        title="Decision history"
        subtitle={whose
          ? `${whose} — every lifecycle decision, newest first.`
          : "Every lifecycle decision you've made, newest first."}
      />

      {others.length > 0 && (
        <div className="flex items-center gap-3">
          <Select
            aria-label="Whose decisions to show"
            className="!mb-0"
            value={staffId}
            onChange={(e) => setStaffId(e.target.value)}
          >
            <option value="">My decisions</option>
            {others.map((s) => (
              <option key={s.id} value={s.id}>
                {s.name}
              </option>
            ))}
          </Select>
          {q.isFetching && <Loader2 size={15} className="animate-spin text-muted" />}
        </div>
      )}

      <div className="flex flex-wrap items-end justify-between gap-3">
        <PeriodPicker preset={preset} onPreset={setPreset} custom={custom} onCustom={setCustom} />
        <SearchBar
          initialValue={search}
          onSearch={(term) => {
            setSearch(term);
            setPage(1);
          }}
          placeholder="Application, customer ID, name or PAN"
          ariaLabel="Search decisions by application, customer ID, customer name or PAN"
          inputClassName="w-72"
        />
      </div>

      {/* Totals for whoever is selected. Deliberately the same figures as the performance
          dashboard: this page is what that page links into, so a reader arrives expecting them. */}
      <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-5">
        <StatCard label="Approved" value={stats ? stats.accepted : "—"} accent="success" />
        <StatCard label="Rejected" value={stats ? stats.rejected : "—"} accent="error" />
        <StatCard
          label="Total actions"
          value={stats ? stats.totalActions : "—"}
          info="Every logged action in this period. The list below shows decisions only, so it can be shorter than this."
        />
        <StatCard
          label="In queue now"
          value={stats ? stats.pendingNow : "—"}
          accent="gold"
          info={`Files sitting with ${staffId ? "them" : "you"} right now. A live snapshot — it does not change with the selected period.`}
        />
        <StatCard
          label="Calls"
          value={stats && !callsUntracked ? stats.callsMade : "—"}
          info={callsNote}
        />
      </div>
      {summaryQ.isError ? (
        <p className="text-sm text-error-700">{errMessage(summaryQ.error)}</p>
      ) : summaryQ.data && !stats ? (
        <p className="text-sm text-muted">
          Totals aren&apos;t available for the selected person.
        </p>
      ) : null}

      <section
        className={`rounded border border-line bg-white shadow-sm transition-opacity ${q.isPlaceholderData ? "opacity-60" : ""}`}
        aria-busy={q.isPlaceholderData}
      >
        {q.error ? (
          <ErrorState error={q.error} onRetry={() => void q.refetch()} />
        ) : q.isLoading ? (
          <Skeleton variant="table" rows={8} cols={14} />
        ) : rows.length === 0 ? (
          <EmptyState title="No decisions recorded yet." />
        ) : matched.length === 0 ? (
          <EmptyState
            title={`No decisions match “${search}”.`}
            hint={`Search covers application ID, customer ID, customer name and PAN. Clear it to see all ${rows.length}.`}
          />
        ) : (
          <>
          {/* `staff-register-scroll` pins the header; PaginationBar sits after the scroller so it
              does not scroll away with the rows. The offset clears the shell header, PageHeader,
              the period row and the stat-card row. */}
          <div
            className="staff-table-scroll staff-register-scroll"
            style={{ "--register-offset": "30rem" } as React.CSSProperties}
          >
            <table className="staff-data-table">
              <caption className="sr-only">Decision history register</caption>
              <thead>
                {/* What was decided, broken out of the raw event payload. The backend used to hand
                    this page strings like "amountPaise=500000 salaryCreditDay=1" and they were
                    rendered verbatim; DecisionNotes now parses them, so each value gets a real
                    column and "Remark" carries only what a human actually typed. */}
                <tr>
                  <th scope="col">S.No.</th>
                  <SortableTh label="When" sortKey="atMs" active={sortKey} dir={dir} onToggle={toggle} />
                  <th scope="col">Application</th>
                  <th scope="col">Customer ID</th>
                  <th scope="col">Customer</th>
                  <th scope="col">Signals</th>
                  <th scope="col">PAN</th>
                  <SortableTh label="Decision" sortKey="decisionLabel" active={sortKey} dir={dir} onToggle={toggle} />
                  <th scope="col">Outcome</th>
                  <SortableTh
                    label="Amount"
                    sortKey="amountPaise"
                    active={sortKey}
                    dir={dir}
                    onToggle={toggle}
                    className="num text-right"
                  />
                  <th scope="col">Repayment date</th>
                  <th scope="col">Assignee</th>
                  <th scope="col">Txn ref</th>
                  <th scope="col">Remark</th>
                </tr>
              </thead>
              <tbody>
                {pageRows.map((r, i) => (
                  <tr key={`${r.applicationId}-${r.at}-${i}`}>
                    <td className="text-muted">{(page - 1) * pageSize + i + 1}</td>
                    <td className="whitespace-nowrap text-muted">{formatDate(r.at)}</td>
                    <td>
                      {/* Opens the file in place: /staff/applications has no `?open=` deep link. */}
                      <button
                        type="button"
                        onClick={() => setOpenAppId(r.applicationId)}
                        aria-label={`Open application #${r.applicationId}`}
                        className="font-mono font-semibold text-navy hover:underline"
                      >
                        #{r.applicationId}
                      </button>
                    </td>
                    <td className="font-mono text-muted">
                      {r.customerId != null ? `#${r.customerId}` : "—"}
                    </td>
                    <td className="staff-cell">
                      {r.customerId != null && r.customerName ? (
                        <Link href={customerPageHref(r.customerId)} className="font-semibold text-navy hover:underline">
                          {r.customerName}
                        </Link>
                      ) : (
                        (r.customerName ?? "—")
                      )}
                    </td>
                    <td><TrustStars trust={r.trust} /></td>
                    <td className="font-mono text-ink">{r.pan || "—"}</td>
                    <td className="font-semibold text-navy">{r.decisionLabel}</td>
                    <td>
                      <StatusBadge kind="application" value={r.toStatus} />
                    </td>
                    <td className="num whitespace-nowrap text-right font-mono text-ink">
                      {r.amountPaise != null ? paiseToINR(r.amountPaise) : "—"}
                    </td>
                    <td className="whitespace-nowrap">
                      {r.repaymentDate ? formatDate(r.repaymentDate) : "—"}
                    </td>
                    <td className="staff-cell">
                      {r.assigneeName ?? (r.assigneeId != null ? `#${r.assigneeId}` : "—")}
                    </td>
                    <td className="font-mono text-muted">{r.txnRef || "—"}</td>
                    {/* The remark truncates rather than widening the row; the full remark and the raw
                        event payload (audit trail, not a column) sit in a keyboard-reachable ⓘ. */}
                    <td className="staff-cell text-muted">
                      <span className="flex max-w-[18rem] items-center gap-1">
                        <span className="min-w-0 truncate">{r.remark ?? "—"}</span>
                        {(r.remark || r.notes) && (
                          <InfoTooltip
                            label="Full remark and audit notes"
                            content={
                              <>
                                {r.remark && (
                                  <span className="block whitespace-pre-wrap break-words">{r.remark}</span>
                                )}
                                {r.notes && (
                                  <span className={`block ${r.remark ? "mt-2 border-t border-line pt-2" : ""}`}>
                                    <span className="block font-semibold text-muted">Audit notes</span>
                                    <span className="block whitespace-pre-wrap break-words font-mono">{r.notes}</span>
                                  </span>
                                )}
                              </>
                            }
                          />
                        )}
                      </span>
                    </td>
                  </tr>
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
          </>
        )}
      </section>

      <ApplicationDetailDialog applicationId={openAppId} onClose={() => setOpenAppId(null)} />
    </div>
  );
}
