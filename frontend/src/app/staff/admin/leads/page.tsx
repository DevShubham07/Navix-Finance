"use client";

import * as React from "react";
import { useQuery } from "@tanstack/react-query";
import { Loader2, RefreshCw } from "lucide-react";
import { EmptyState, ErrorState, Input, Select, Skeleton } from "@/components/ui";
import { PageHeader } from "@/components/staff/staff-ui";
import { SearchBar } from "@/components/staff/search-bar";
import { useStaffMe, useCan, NoAccessNotice } from "@/components/staff/live-pipeline";
import { ExportMenu } from "@/components/staff/export-menu";
import { LeadsTracker } from "@/components/staff/leads-tracker";
import { OutcomeChip, OUTCOME_LABEL } from "@/components/staff/lead-outcome";
import {
  leadsApi,
  adminApi,
  paiseToINR,
  type LeadCallStatus,
  type LeadOutcome,
  type LeadSource,
  type LeadView,
} from "@/lib/api/applications";
import { PaginationBar } from "@/components/staff/pipeline/pagination";
import { istDay } from "@/lib/staff/column-filter-match";
import { useRegisterQuery } from "@/lib/staff/register-query";
import { useServerRegister } from "@/lib/staff/use-server-register";
import { retryUnlessClientError } from "@/lib/staff/admin-all-applications";
import { type FilterColumn } from "@/components/staff/column-filter";
import { Th, thBag, describeRegister } from "@/lib/staff/register-th";

const CALL_STATUSES: LeadCallStatus[] = [
  "NOT_CALLED",
  "CALLED",
  "CALLBACK",
  "NO_ANSWER",
  "NOT_INTERESTED",
  "WRONG_NUMBER",
  "CONNECTED",
];

const SOURCES: LeadSource[] = ["DSA", "REFERRAL", "WALK_IN", "OTHER"];

function daysAgoIso(n: number): string {
  const d = new Date();
  d.setDate(d.getDate() - n);
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;
}

function todayIso(): string {
  const d = new Date();
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;
}

const SORT_KEYS = ["createdAt"] as const;

/**
 * What each header offers — the server's `LeadBookQuery.FILTER_COLUMNS`. Name, mobile and pincode are
 * text-only (identity columns: never a dropdown of tens of thousands of them); source and status are
 * small enums, so checklists; ★ is a number in stars. The server answers in id order only, so none of
 * them sorts. Outcome stays a select above rather than a header filter: the outcome shown overlays a
 * derived CONFIRMED the stored column cannot filter on. The date window and Telecaller selects also
 * stay selects, because the tracker graphs follow them too — one control each, not a select AND a header.
 */
const FILTER_COLUMNS: Array<FilterColumn<LeadView>> = [
  { key: "name", kind: "text", sortable: false, value: (r) => r.name },
  { key: "mobile", kind: "text", sortable: false, value: (r) => r.mobile },
  { key: "pincode", kind: "text", sortable: false, value: (r) => r.pincode ?? "" },
  { key: "source", facet: true, sortable: false, value: (r) => r.source?.replace(/_/g, " ") ?? "—", raw: (r) => r.source ?? "" },
  { key: "callStatus", facet: true, sortable: false, value: (r) => r.callStatus.replace(/_/g, " "), raw: (r) => r.callStatus },
  { key: "rating", kind: "num", sortable: false, value: (r) => (r.qualityRating ? `${r.qualityRating}★` : "—"), num: (r) => r.qualityRating },
];
const FILTER_KEYS = FILTER_COLUMNS.map((c) => c.key);
const COLUMN_LABEL: Record<string, string> = {
  name: "Name",
  mobile: "Mobile",
  pincode: "Pincode",
  source: "Source",
  callStatus: "Status",
  rating: "★",
};
/** The server's RAW values → the words on screen. A filter travels as the raw value, never the label. */
const FACET_LABELS: Record<string, Record<string, string>> = {
  source: Object.fromEntries(SOURCES.map((s) => [s, s.replace(/_/g, " ")])),
  callStatus: Object.fromEntries(CALL_STATUSES.map((s) => [s, s.replace(/_/g, " ")])),
};
/** The server caps a page at 100 (`LeadService.MAX_PAGE_SIZE`); "Download all" walks it. */
const EXPORT_CHUNK = 100;

/**
 * Admin · leads dashboard — tracker graphs + filterable company-wide lead register.
 */
export default function AdminLeadsPage() {
  const me = useStaffMe().data;
  const myRole = me?.role;
  const can = useCan();
  const [from, setFrom] = React.useState(() => daysAgoIso(30));
  const [to, setTo] = React.useState(todayIso);
  const [createdBy, setCreatedBy] = React.useState<number | "">("");
  const [leadOutcome, setLeadOutcome] = React.useState<LeadOutcome | "">("");

  const staffQ = useQuery({
    queryKey: ["admin-staff"],
    queryFn: adminApi.listStaff,
    enabled: !!myRole && can("staff:manage"),
  });

  const telecallers = (staffQ.data ?? []).filter(
    (s) => s.role === "TELECALLER" || s.role === "ADMIN",
  );

  // The register view owns search, column filters and paging (and this page's URL), so a change to
  // any of them returns to page 1 in one update. The filters run on the SERVER: a client-side one
  // would narrow 100 rows out of thousands and read as "no match".
  const rq = useRegisterQuery({
    registerId: "admin-leads",
    staffId: me?.id,
    sortKeys: SORT_KEYS,
    filterKeys: FILTER_KEYS,
    defaults: { sort: { key: "createdAt", dir: "desc" } },
  });
  const q = rq.state.q;
  const page = rq.state.page;
  const pageSize = rq.state.size;

  // The page-level narrowing that is not a column filter. It rides beside `rq.queryParams` on every
  // request (page, facet and export alike) and is in the query key, so a change refetches.
  const view = React.useMemo(() => {
    const v: Record<string, string> = {};
    if (from) v.from = from;
    if (to) v.to = to;
    if (createdBy !== "") v.createdBy = String(createdBy);
    if (leadOutcome) v.leadOutcome = leadOutcome;
    return v;
  }, [from, to, createdBy, leadOutcome]);

  const stats = useQuery({
    queryKey: ["lead-stats", from, to, createdBy],
    queryFn: () =>
      leadsApi.stats({
        from: from || undefined,
        to: to || undefined,
        createdBy: createdBy === "" ? undefined : Number(createdBy),
      }),
    enabled: !!myRole && can("staff:manage"),
  });

  const allowed = !!myRole && can("staff:manage");
  // No facet is fetched on mount — a header's panel opening calls `cr.requestFacet`.
  const cr = useServerRegister<LeadView>(rq, {
    key: ["admin-leads", view],
    fetchPage: (params, pg, size) => leadsApi.page({ extra: { ...view, ...params }, page: pg, size }),
    fetchFacet: (column, params) => leadsApi.facet(column, { ...view, ...params }),
    labels: FACET_LABELS,
    // Not before the session says who is signed in; and a 403 is final, so no retries.
    enabled: allowed,
    retry: retryUnlessClientError,
  });
  const th = thBag(rq, cr, FILTER_COLUMNS, COLUMN_LABEL, cr.requestFacet);

  // Server-paged: the register only ever renders one page, and a bulk import can put tens of
  // thousands of leads behind this filter.
  const rows = cr.pageRows;
  const total = cr.total;
  const pageCount = cr.pageCount;
  const pageRows = rows;

  // The "Filtered: …" line in the words on screen, beside the page-level narrowing that is not a column filter.
  const exportSubtitle =
    [
      "Telecaller lead register",
      from || to ? `Created ${from || "…"} to ${to || "…"}` : "",
      createdBy !== "" ? `Telecaller: ${telecallers.find((s) => s.id === createdBy)?.name ?? createdBy}` : "",
      leadOutcome ? `Outcome: ${OUTCOME_LABEL[leadOutcome]}` : "",
      q ? `Search: “${q}”` : "",
      describeRegister(th),
    ]
      .filter(Boolean)
      .join(" · ");

  if (myRole && !can("staff:manage")) {
    return <NoAccessNotice message="Admin access only." />;
  }

  return (
    <div>
      <PageHeader
        title="Leads dashboard"
        subtitle="Tracker graphs and company-wide lead results. Filter by date, telecaller and outcome, or by any column header."
      >
        <ExportMenu
          title="Leads"
          subtitle={exportSubtitle}
          fileBase="dhanboost-leads"
          columns={[
            { header: "Name", value: (r: LeadView) => r.name },
            { header: "Mobile", value: (r) => r.mobile },
            { header: "Pincode", value: (r) => r.pincode ?? "" },
            { header: "Email", value: (r) => r.email ?? "" },
            { header: "City", value: (r) => r.city ?? "" },
            { header: "Employer", value: (r) => r.employer ?? "" },
            {
              header: "Salary",
              value: (r) =>
                r.monthlySalaryPaise != null ? paiseToINR(r.monthlySalaryPaise) : "",
            },
            {
              header: "Amount interested",
              value: (r) =>
                r.loanAmountInterestedPaise != null
                  ? paiseToINR(r.loanAmountInterestedPaise)
                  : "",
            },
            { header: "Source", value: (r) => r.source ?? "" },
            { header: "Source detail", value: (r) => r.sourceDetail ?? "" },
            { header: "Call status", value: (r) => r.callStatus },
            {
              header: "Quality",
              value: (r) => (r.qualityRating != null ? String(r.qualityRating) : ""),
            },
            { header: "Remarks", value: (r) => r.remarks ?? "" },
            { header: "Outcome", value: (r) => r.leadOutcome },
            { header: "Note for DSA", value: (r) => r.dsaNote ?? "" },
            { header: "Created by", value: (r) => r.createdByStaffName ?? String(r.createdByStaffId) },
            { header: "Created at", value: (r) => r.createdAt },
          ]}
          rows={rows}
          scope="page"
          getAllRows={async () => {
            // The register shows one page; an export means the whole filter, so walk the server under
            // the same search, window and column filters the table has.
            const out: LeadView[] = [];
            for (let p = 1; ; p += 1) {
              const chunk = await leadsApi.page({ extra: { ...view, ...rq.queryParams }, page: p, size: EXPORT_CHUNK });
              out.push(...chunk.rows);
              if (chunk.rows.length === 0 || out.length >= chunk.total) break;
            }
            return out;
          }}
          allLabel="Download all leads (CSV)"
          disabled={total === 0}
        />
        <button
          type="button"
          className="btn btn-sm btn-outline"
          onClick={() => {
            stats.refetch();
            cr.refetch();
          }}
          disabled={stats.isFetching || cr.isPlaceholderData}
        >
          {stats.isFetching || cr.isPlaceholderData ? (
            <Loader2 size={14} className="animate-spin" />
          ) : (
            <RefreshCw size={14} />
          )}
          Refresh
        </button>
      </PageHeader>

      <div className="mb-4 flex flex-wrap items-end gap-3">
        <Input label="From" type="date" value={from} onChange={(e) => {
            setFrom(e.target.value);
            rq.apply({});
          }} className="!mb-0" />
        <Input label="To" type="date" value={to} onChange={(e) => {
            setTo(e.target.value);
            rq.apply({});
          }} className="!mb-0" />
        <Select
          label="Telecaller"
          className="!mb-0"
          value={createdBy === "" ? "" : String(createdBy)}
          onChange={(e) => {
            setCreatedBy(e.target.value === "" ? "" : Number(e.target.value));
            rq.apply({});
          }}
        >
          <option value="">All</option>
          {telecallers.map((s) => (
            <option key={s.id} value={s.id}>
              {s.name} ({s.role})
            </option>
          ))}
        </Select>
        <SearchBar initialValue={q} onSearch={rq.setQ} placeholder="Name or mobile" ariaLabel="Search" />
        <Select
          label="Outcome"
          className="!mb-0"
          value={leadOutcome}
          onChange={(e) => {
            setLeadOutcome(e.target.value as LeadOutcome | "");
            rq.apply({});
          }}
        >
          <option value="">All</option>
          {(["NEW", "OUTREACHED", "REJECTED", "CONFIRMED"] as LeadOutcome[]).map((o) => (
            <option key={o} value={o}>
              {OUTCOME_LABEL[o]}
            </option>
          ))}
        </Select>
      </div>

      {(stats.isError || cr.isError) && (
        // One notice for both queries, so its retry refetches both.
        <ErrorState
          error={stats.error ?? cr.error}
          onRetry={() => {
            void stats.refetch();
            cr.refetch();
          }}
          className="mb-3 py-4"
        />
      )}

      <section className="mb-8">
        <h2 className="mb-1 font-serif text-xl text-navy">Tracker</h2>
        {/* Computed server-side from the date range and telecaller alone; the search, outcome and
            column filters narrow the register below, not these graphs. */}
        <p className="mb-3 text-xs text-muted">
          Follows the date range and telecaller only — not the search, outcome or column filters.
        </p>
        {stats.isLoading && (
          <div className="flex h-40 items-center justify-center text-navy/40">
            <Loader2 className="animate-spin" />
          </div>
        )}
        {stats.data && <LeadsTracker stats={stats.data} />}
      </section>

      <section>
        <h2 className="mb-3 font-serif text-xl text-navy">Lead register</h2>
        <div className="staff-table-scroll rounded-lg border border-navy/10 bg-white">
          <table className="staff-data-table">
            <thead>
              <tr>
                <th>S.No.</th>
                <Th th={th} col="name" />
                <Th th={th} col="mobile" />
                <Th th={th} col="pincode" />
                <Th th={th} col="source" />
                <Th th={th} col="callStatus" />
                <th>Outcome</th>
                <Th th={th} col="rating" />
                <th>Remarks</th>
                <th>Note for DSA</th>
                <th>By</th>
                <th>Created</th>
              </tr>
            </thead>
            <tbody>
              {/* 12 = the header's column count (this row used to span only 10). */}
              {rows.length === 0 &&
                (cr.isLoading ? (
                  <tr>
                    <td colSpan={12}>
                      <Skeleton variant="table" rows={8} cols={12} />
                    </td>
                  </tr>
                ) : (
                  <EmptyState title="No leads in this filter." inTable={12} />
                ))}
              {pageRows.map((row, i) => (
                <tr key={row.id} className="border-b border-navy/5">
                  <td className="text-navy/50">{(page - 1) * pageSize + i + 1}</td>
                  <td className="font-medium text-navy">{row.name}</td>
                  <td className="font-mono text-xs">{row.mobile}</td>
                  <td className="font-mono text-xs">{row.pincode ?? "—"}</td>
                  <td>
                    {row.source?.replace(/_/g, " ") ?? "—"}
                    {row.sourceDetail ? ` · ${row.sourceDetail}` : ""}
                  </td>
                  <td className="text-xs">{row.callStatus.replace(/_/g, " ")}</td>
                  <td><OutcomeChip outcome={row.leadOutcome} /></td>
                  <td>{row.qualityRating ? `${row.qualityRating}★` : "—"}</td>
                  <td className="max-w-[200px] truncate text-navy/70" title={row.remarks ?? undefined}>
                    {row.remarks || "—"}
                  </td>
                  <td className="max-w-[200px] truncate text-navy/70" title={row.dsaNote ?? undefined}>
                    {row.dsaNote || "—"}
                  </td>
                  <td className="text-navy/70">
                    {row.createdByStaffName || row.createdByStaffId}
                  </td>
                  <td className="font-mono text-xs text-navy/50">
                    {istDay(row.createdAt) ?? "—"}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        <PaginationBar page={page} pageCount={pageCount} setPage={rq.setPage} total={total} pageSize={pageSize} setPageSize={rq.setSize} />
      </section>
    </div>
  );
}
