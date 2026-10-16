"use client";

import { TrustStars } from "@/components/staff/trust-stars";
import * as React from "react";
import { useQueryClient } from "@tanstack/react-query";
import { Loader2, RefreshCw, ArrowRight, FilterX } from "lucide-react";
import { EmptyState, ErrorState, Select, Skeleton, StatusBadge } from "@/components/ui";
import { humaniseStatus } from "@/components/ui/status-badge";
import { PageHeader } from "@/components/staff/staff-ui";
import { SearchBar } from "@/components/staff/search-bar";
import { useStaffMe, useCan, NoAccessNotice } from "@/components/staff/live-pipeline";
import { ApplicationDetailDialog } from "@/components/staff/application-detail-dialog";
import { ExportMenu } from "@/components/staff/export-menu";
import { bureauStateLabel } from "@/components/staff/bureau-state";
import type { ExportColumn } from "@/lib/export/exporters";
import {
  staffApi,
  paiseToINR,
  statusLabel,
  type AdminApplicationView,
  type ApplicationStatus,
} from "@/lib/api/applications";
import { type FilterColumn } from "@/components/staff/column-filter";
import { Th, thBag, describeRegister } from "@/lib/staff/register-th";
import { useRegisterQuery } from "@/lib/staff/register-query";
import { useServerRegister } from "@/lib/staff/use-server-register";
import { PaginationBar } from "@/components/staff/pipeline/pagination";
import { formatDateTime } from "@/lib/utils";
import { StageSignal } from "@/components/staff/stage-signal";
import { retryUnlessClientError, type CompletenessFilter } from "@/lib/staff/admin-all-applications";

const QUERY_KEY = ["admin-all-applications"] as const;

/** Every status, so the dropdown can name a raw value the facet returns. A Record, so a status added to
 *  the union is a compile error here instead of a raw enum in the checklist. */
const STATUS_KEYS: Record<ApplicationStatus, true> = {
  DRAFT: true, KYC_PENDING: true, KYC_APPROVED: true, KYC_REJECTED: true, PRE_APPROVED: true,
  REVIEW_PENDING: true, CREDIT_EXEC_PENDING: true, CREDIT_EXEC_APPROVED: true, CREDIT_HEAD_PENDING: true,
  CREDIT_HEAD_APPROVED: true, SANCTIONED: true, DISBURSEMENT_PENDING: true, ACCOUNTANT_PENDING: true,
  DISBURSEMENT_FAILED: true, DISBURSED: true, ACTIVE: true, OVERDUE: true, DEFAULTED: true, CLOSED: true,
  WRITTEN_OFF: true, REJECTED: true, CANCELLED: true,
};
const STATUS_LABELS = new Map(Object.keys(STATUS_KEYS).map((k) => [k, humaniseStatus(k)] as const));
const NO_LABELS = new Map<string, string>();

/** Header text per column key — the headers, the filter panels and the export's "Filtered:" line. */
const COLUMN_LABEL: Record<string, string> = {
  name: "Customer",
  pan: "PAN",
  account: "Account",
  ifsc: "IFSC",
  mobile: "Mobile",
  status: "Status",
  amount: "Amount",
  creditScore: "Credit",
  risk: "Risk",
};

/**
 * What each header offers. Keys are the server's `AdminApplicationService.COLUMNS`; `raw` is the stored
 * value behind a label, so a filter never travels as the words on screen, and `num` is in RUPEES (the
 * server scales the bound to paise). Identity columns are text-only: no checklist of thousands of PANs.
 */
/**
 * Every column here carries `sortable: false`.
 *
 * This register answers in one order — newest id first — and nothing else: the server has no sort
 * parameter, and adding one is a change of its own. A header that showed a sort chevron, took a
 * click and reordered nothing would be worse than one that never offered, so the chevron is simply
 * not rendered. Declared per column rather than by blanking `onToggle`, which left the control on
 * screen and only made it inert.
 */
const FILTER_COLUMNS: Array<FilterColumn<AdminApplicationView>> = [
  { key: "name", sortable: false, kind: "text", value: (a) => a.fullName ?? "" },
  { key: "pan", sortable: false, kind: "text", value: (a) => a.pan ?? "" },
  { key: "account", sortable: false, kind: "text", value: (a) => a.salaryAccountNumber ?? "" },
  { key: "ifsc", sortable: false, kind: "text", value: (a) => a.salaryIfsc ?? "" },
  { key: "mobile", sortable: false, kind: "text", value: (a) => a.mobile ?? "" },
  { key: "status", sortable: false, value: (a) => humaniseStatus(a.status), raw: (a) => a.status, facet: true },
  {
    key: "amount",
    sortable: false,
    kind: "num",
    value: (a) => (a.amountRequestedPaise != null ? paiseToINR(a.amountRequestedPaise) : "—"),
    num: (a) => (a.amountRequestedPaise != null ? a.amountRequestedPaise / 100 : null),
  },
  { key: "creditScore", sortable: false, kind: "num", value: (a) => String(a.creditScore ?? "—"), num: (a) => a.creditScore },
  { key: "risk", sortable: false, value: (a) => a.riskCategory || "—", raw: (a) => a.riskCategory ?? "", facet: true },
];
const FILTER_KEYS = FILTER_COLUMNS.map((c) => c.key);

/** The server's page endpoint caps `size` at 100; "Download all" walks it. */
const EXPORT_CHUNK = 100;

/** Paise -> plain rupee string (2 dp) for the export columns; "" when null. */
const rupees = (p: number | null) => (p == null ? "" : (p / 100).toFixed(2));

/** Every field, for the CSV / PDF (the on-screen table shows the headline columns). */
const EXPORT_COLUMNS: ExportColumn<AdminApplicationView>[] = [
  { header: "App ID", value: (a) => a.id },
  { header: "Customer ID", value: (a) => a.customerId },
  { header: "Status", value: (a) => statusLabel(a.status) },
  { header: "Complete", value: (a) => (a.complete ? "yes" : "no") },
  { header: "Steps", value: (a) => `${a.stepsCompleted}/${a.stepsRequired}` },
  { header: "Agreement", value: (a) => (a.agreementAccepted ? "yes" : "no") },
  { header: "Name", value: (a) => a.fullName ?? "" },
  { header: "PAN", value: (a) => a.pan ?? "" },
  { header: "Mobile", value: (a) => a.mobile ?? "" },
  { header: "Email", value: (a) => a.email ?? "" },
  { header: "DOB", value: (a) => a.dob ?? "" },
  { header: "Address", value: (a) => a.address ?? "" },
  { header: "Employer", value: (a) => a.employer ?? "" },
  { header: "Employment", value: (a) => a.employmentStatus ?? "" },
  { header: "Monthly salary (₹)", value: (a) => rupees(a.monthlySalaryPaise) },
  { header: "Salary bank", value: (a) => a.salaryBank ?? "" },
  { header: "Salary account", value: (a) => a.salaryAccountNumber ?? "" },
  { header: "Salary IFSC", value: (a) => a.salaryIfsc ?? "" },
  { header: "Amount requested (₹)", value: (a) => rupees(a.amountRequestedPaise) },
  { header: "Eligible limit (₹)", value: (a) => rupees(a.eligibleLimitPaise) },
  { header: "Purpose", value: (a) => a.purpose ?? "" },
  { header: "Salary day", value: (a) => a.salaryCreditDay ?? "" },
  { header: "Loan ID", value: (a) => a.loanId ?? "" },
  { header: "Assigned exec", value: (a) => a.assignedExecutiveName ?? "" },
  { header: "Stage since", value: (a) => a.currentStageEnteredAt ?? "" },
  { header: "Credit score", value: (a) => a.creditScore ?? "" },
  { header: "Star rating", value: (a) => (a.starRating != null ? a.starRating.toFixed(1) : "") },
  { header: "Recommendation", value: (a) => a.recommendation ?? "" },
  { header: "Risk", value: (a) => a.riskCategory ?? "" },
  { header: "KYC captured", value: (a) => (a.kycCapturedAt ? a.kycCapturedAt.slice(0, 10) : "") },
];

/**
 * Admin · all applications — EVERY application, complete and incomplete (DRAFT / partially filled),
 * with full KYC detail and an onboarding-completeness flag. Search, column filters and a completeness
 * filter all run server-side (`/api/applications/all/page`), so only the page on screen is loaded;
 * "Download all" walks the same filtered query. ADMIN only.
 */
export default function AdminAllApplicationsPage() {
  const me = useStaffMe();
  const myRole = me.data?.role;
  const can = useCan();
  // `/api/applications/all` is ADMIN-only server-side. The register must not fire before `/me` has
  // answered — every other role would send a request it can only be refused — so the register hooks
  // live in a child that mounts only once the role is known to be ADMIN.
  const isAdmin = me.data?.role === "ADMIN";

  if (myRole && !can("staff:manage")) {
    return <NoAccessNotice message="Admin access only." />;
  }
  if (isAdmin) return <AllApplicationsRegister staffId={me.data!.id} />;

  return (
    <div>
      <PageHeader title="All applications" subtitle="Every application — complete and incomplete — with full KYC detail. Admin only.">
        <button
          type="button"
          disabled
          className="flex items-center gap-1.5 rounded border border-line px-3 py-1.5 text-xs text-muted disabled:cursor-not-allowed disabled:opacity-40"
        >
          <RefreshCw size={13} /> Refresh
        </button>
      </PageHeader>
      {!me.isPending ? (
        // `/me` answered without a session (or failed): saying "Admin access only" to a signed-out
        // admin would be wrong.
        <ErrorState error={me.error} title="Couldn't confirm your staff session." onRetry={() => void me.refetch()} />
      ) : (
        <Skeleton variant="table" rows={8} cols={16} className="rounded border border-line bg-white" />
      )}
    </div>
  );
}

function AllApplicationsRegister({ staffId }: { staffId: string }) {
  // Bumped to remount the SearchBar, whose draft text is its own state, when "Clear" resets the
  // search from outside it.
  const [searchKey, setSearchKey] = React.useState(0);
  // Not a column: completeness is derived from verification evidence, so it is a server param of its
  // own that the server applies on top of the column filters.
  const [filter, setFilter] = React.useState<CompletenessFilter>("ALL");
  const [openId, setOpenId] = React.useState<number | null>(null);
  const [allCount, setAllCount] = React.useState<number | null>(null);

  // A 4xx (refused, signed out) will not change on a second ask; only a 5xx/network blip is retried.
  // The server-register hook owns its queries, so the policy is set on their key prefix.
  const qc = useQueryClient();
  React.useState(() => qc.setQueryDefaults(QUERY_KEY, { retry: retryUnlessClientError }));

  const rq = useRegisterQuery({
    registerId: "all-applications",
    staffId,
    // The register is newest-first by id and the server has no other order.
    sortKeys: ["id"],
    filterKeys: FILTER_KEYS,
    defaults: { sort: { key: "id", dir: "desc" } },
  });

  const cr = useServerRegister<AdminApplicationView>(rq, {
    key: [...QUERY_KEY, filter],
    fetchPage: async (params, page, size) => {
      const res = await staffApi.listAllApplicationsPage({ filters: params, completeness: filter, page, size });
      setAllCount(res.allCount);
      return res;
    },
    fetchFacet: (column, params) => staffApi.allApplicationsFacet(column, params),
    labels: (column) => (column === "status" ? STATUS_LABELS : NO_LABELS),
  });
  // `pageRows` is the one page on screen; `total` is the server's count under the filters.
  const { pageRows, total, pageCount } = cr;
  const page = rq.state.page;
  const pageSize = rq.state.size;
  // Facets are fetched when a panel opens (`cr.requestFacet`), never on mount. The columns declare
  // `sortable: false`, so no header offers a sort this register cannot perform.
  const th = thBag(rq, cr, FILTER_COLUMNS, COLUMN_LABEL, cr.requestFacet);
  const filtered = filter !== "ALL" || rq.activeCount > 0;

  // "Download all" — every row the filters match, fetched page by page through the same endpoint (the
  // server caps a page) with the same view params as the table, so the export cannot drift from it.
  const getAllRows = async () => {
    const out: AdminApplicationView[] = [];
    for (let p = 1; ; p++) {
      const res = await staffApi.listAllApplicationsPage({
        filters: rq.queryParams,
        completeness: filter,
        page: p,
        size: EXPORT_CHUNK,
      });
      out.push(...res.rows);
      if (res.rows.length === 0 || out.length >= res.total) return out;
    }
  };

  const clearFilters = () => {
    rq.clearAll();
    setFilter("ALL");
    setSearchKey((k) => k + 1);
  };

  // "Clear" unmounts itself along with the filter it cleared, which would drop keyboard focus on
  // <body>. Hand it to the (just remounted) search box instead — the next thing a reader is likely
  // to want after clearing a search.
  const toolbarRef = React.useRef<HTMLDivElement>(null);
  React.useEffect(() => {
    if (searchKey === 0) return;
    toolbarRef.current?.querySelector("input")?.focus();
  }, [searchKey]);

  return (
    <div>
      <PageHeader title="All applications" subtitle="Every application — complete and incomplete — with full KYC detail. Admin only.">
        <ExportMenu
          title="All applications"
          // `rows` is one page here, so the menu says "this page"; the filtered whole is `getAllRows`.
          subtitle={["Full application register", filter !== "ALL" ? `Completeness: ${filter.toLowerCase()}` : "", describeRegister(th)]
            .filter(Boolean)
            .join(" · ")}
          fileBase="dhanboost-all-applications"
          columns={EXPORT_COLUMNS}
          rows={pageRows}
          scope="page"
          getAllRows={getAllRows}
        />
        <button
          type="button"
          onClick={() => cr.refetch()}
          className="flex items-center gap-1.5 rounded border border-line px-3 py-1.5 text-xs text-muted hover:bg-grey-100 hover:text-ink"
        >
          {cr.isPlaceholderData ? <Loader2 size={13} className="animate-spin" /> : <RefreshCw size={13} />} Refresh
        </button>
      </PageHeader>

      <div ref={toolbarRef} className="mb-4 flex flex-wrap items-end gap-2">
        <SearchBar
          key={searchKey}
          initialValue={rq.state.q}
          onSearch={rq.setQ}
          placeholder="Search name, PAN, mobile, ID or status"
          ariaLabel="Search applications"
        />
        <Select
          aria-label="Completeness"
          value={filter}
          onChange={(e) => {
            setFilter(e.target.value as CompletenessFilter);
            // Same "what matches changed" signal as a filter edit: back to page 1.
            rq.apply({});
          }}
          className="!mb-0"
          options={[
            { value: "ALL", label: "All applications" },
            { value: "COMPLETE", label: "Complete only" },
            { value: "INCOMPLETE", label: "Incomplete only" },
          ]}
        />
        {filtered && (
          <button
            type="button"
            onClick={clearFilters}
            className="mb-1 flex items-center gap-1.5 rounded border border-line px-3 py-1.5 text-xs font-semibold text-muted hover:bg-grey-100 hover:text-ink"
          >
            <FilterX size={13} /> Clear
          </button>
        )}
        {/* Only once the register has loaded — "0 of 0" before then would read as an empty register. */}
        {allCount != null && !cr.isLoading && (
          <span className="pb-2 text-xs text-muted" aria-live="polite">
            {total} of {allCount}
          </span>
        )}
      </div>

      {cr.isLoading ? (
        <Skeleton variant="table" rows={8} cols={16} className="rounded border border-line bg-white" />
      ) : cr.isError ? (
        <ErrorState error={cr.error} onRetry={() => cr.refetch()} />
      ) : (
        <div className="overflow-hidden rounded border border-line bg-white shadow-sm" aria-busy={cr.isPlaceholderData}>
          {/* `staff-register-scroll` pins the header (globals.css); PaginationBar is already a sibling
              after this scroller, so it stays put. The offset clears the shell header, PageHeader,
              the search/completeness toolbar and the pagination footer. */}
          <div className={`staff-table-scroll staff-register-scroll${cr.isPlaceholderData ? " opacity-60" : ""}`} style={{ "--register-offset": "24rem" } as React.CSSProperties}>
            <table className="staff-data-table">
              <caption className="sr-only">All applications</caption>
              <thead>
                <tr>
                  <th scope="col">S.No.</th>
                  <th scope="col" className="whitespace-nowrap">App</th>
                  <Th th={th} col="name" />
                  <th scope="col">Signals</th>
                  <Th th={th} col="pan" className="whitespace-nowrap" />
                  <Th th={th} col="account" className="whitespace-nowrap" />
                  <Th th={th} col="ifsc" className="whitespace-nowrap" />
                  <Th th={th} col="mobile" className="whitespace-nowrap" />
                  <Th th={th} col="status" className="whitespace-nowrap" />
                  <th scope="col" className="whitespace-nowrap">Stage</th>
                  <th scope="col" className="whitespace-nowrap">Stage since</th>
                  <th scope="col" className="whitespace-nowrap">Completeness</th>
                  <Th th={th} col="amount" className="num whitespace-nowrap text-right" />
                  <Th th={th} col="creditScore" className="whitespace-nowrap" />
                  <Th th={th} col="risk" className="whitespace-nowrap" />
                  <th scope="col" className="text-right">Open</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-line align-top">
                {pageRows.map((a, i) => (
                  <tr key={a.id} className="hover:bg-grey-50">
                    <td className="text-muted">{(page - 1) * pageSize + i + 1}</td>
                    <td className="whitespace-nowrap font-mono text-muted">#{a.id}</td>
                    <td>
                      <span className="block max-w-[14rem] truncate font-semibold text-ink" title={a.fullName ?? ""}>{a.fullName || "—"}</span>
                      <span className="block text-xs text-muted">#{a.customerId}{a.email ? ` · ${a.email}` : ""}</span>
                    </td>
                    <td><TrustStars trust={a.trust} /></td>
                    <td className="whitespace-nowrap font-mono text-ink">{a.pan || "—"}</td>
                    <td className="whitespace-nowrap font-mono text-ink">{a.salaryAccountNumber || "—"}</td>
                    <td className="whitespace-nowrap font-mono text-ink">{a.salaryIfsc || "—"}</td>
                    <td className="whitespace-nowrap font-mono text-muted">{a.mobile || "—"}</td>
                    <td className="whitespace-nowrap">
                      <StatusBadge kind="application" value={a.status} />
                    </td>
                    <td className="whitespace-nowrap">
                      <StageSignal compact status={a.status} journeyStep={a.journeyStep} />
                    </td>
                    {/* When the application entered its CURRENT status (latest application_event.at),
                        formatted like every other "in stage since" in the console. */}
                    <td className="whitespace-nowrap text-muted">
                      {a.currentStageEnteredAt ? formatDateTime(a.currentStageEnteredAt) : "—"}
                    </td>
                    <td className="whitespace-nowrap">
                      {a.complete ? (
                        <span className="rounded-full bg-success-50 px-2.5 py-0.5 text-xs font-semibold text-success-700">Complete</span>
                      ) : (
                        <span className="rounded-full bg-warning-50 px-2.5 py-0.5 text-xs font-semibold text-warning-700"
                          title={a.agreementAccepted ? "" : "Borrower has not accepted the signup terms"}>
                          {a.stepsCompleted}/{a.stepsRequired}
                          {/* `agreementAccepted` is CustomerProfile.termsAcceptedAt — the signup
                              screen-1 T&C tick (AdminApplicationService#listAll), NOT the
                              agreement-documents step and NOT the Aadhaar eSign. */}
                          {a.agreementAccepted ? "" : " · terms & conditions not accepted"}
                        </span>
                      )}
                    </td>
                    <td className="num whitespace-nowrap text-right text-ink">
                      {a.amountRequestedPaise != null ? paiseToINR(a.amountRequestedPaise) : "—"}
                    </td>
                    <td className="whitespace-nowrap text-muted">
                      {a.creditScore != null ? (
                        a.creditScore
                      ) : a.bureauState === "NO_RECORD" ? (
                        <span title={bureauStateLabel("NO_RECORD", "long")}>{bureauStateLabel("NO_RECORD", "short")}</span>
                      ) : (
                        <span title={bureauStateLabel("NOT_FETCHED", "long")}>{bureauStateLabel("NOT_FETCHED", "short")}</span>
                      )}
                      {a.starRating != null ? <span className="text-gold-dark"> · {a.starRating.toFixed(1)}★</span> : null}
                    </td>
                    <td className="whitespace-nowrap text-muted">{a.riskCategory || "—"}</td>
                    <td className="whitespace-nowrap text-right">
                      <div className="flex items-center justify-end gap-1.5">
                        <button onClick={() => setOpenId(a.id)} className="inline-flex items-center gap-1 text-navy hover:underline">
                          Open <ArrowRight size={14} />
                        </button>
                      </div>
                    </td>
                  </tr>
                ))}
                {pageRows.length === 0 && (
                  <EmptyState title="No applications match." inTable={14} />
                )}
              </tbody>
            </table>
          </div>
          <PaginationBar page={page} pageCount={pageCount} setPage={rq.setPage} total={total} pageSize={pageSize} setPageSize={rq.setSize} />
        </div>
      )}

      <ApplicationDetailDialog applicationId={openId} onClose={() => setOpenId(null)} />
    </div>
  );
}
