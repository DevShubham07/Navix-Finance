"use client";

import * as React from "react";
import { useQuery } from "@tanstack/react-query";
import { Loader2, RefreshCw, ArrowRight, Info, FilterX } from "lucide-react";
import { EmptyState, ErrorState, Select, Skeleton, StatusBadge } from "@/components/ui";
import { PageHeader } from "@/components/staff/staff-ui";
import { SearchBar } from "@/components/staff/search-bar";
import { useStaffMe, useCan, NoAccessNotice } from "@/components/staff/live-pipeline";
import { ApplicationDetailDialog } from "@/components/staff/application-detail-dialog";
import { ApplicationInfoDialog } from "@/components/staff/application-info-dialog";
import { ExportMenu } from "@/components/staff/export-menu";
import { bureauStateLabel } from "@/components/staff/bureau-state";
import type { ExportColumn } from "@/lib/export/exporters";
import { staffApi, paiseToINR, statusLabel, type AdminApplicationView } from "@/lib/api/applications";
import { usePagination, PaginationBar } from "@/components/staff/pipeline/pagination";
import { formatDateTime } from "@/lib/utils";
import {
  filterAdminApplications,
  isAdminApplicationsFilterActive,
  retryUnlessClientError,
  type CompletenessFilter,
} from "@/lib/staff/admin-all-applications";

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
 * with full KYC detail and an onboarding-completeness flag. Search + completeness filter; full CSV /
 * PDF export. Live `/api/applications/all`. ADMIN only.
 */
export default function AdminAllApplicationsPage() {
  const me = useStaffMe();
  const myRole = me.data?.role;
  const can = useCan();
  // `/api/applications/all` is ADMIN-only server-side. The register used to fire before `/me` had
  // answered — so every other role sent a request it could only ever have refused (FORBIDDEN_ROLE),
  // retried it once, and only then reached "Admin access only". It now waits for the role.
  const isAdmin = me.data?.realRole === "ADMIN";
  const [query, setQuery] = React.useState("");
  // Bumped to remount the SearchBar, whose draft text is its own state, when "Clear" resets the
  // search from outside it.
  const [searchKey, setSearchKey] = React.useState(0);
  const [filter, setFilter] = React.useState<CompletenessFilter>("ALL");
  const [openId, setOpenId] = React.useState<number | null>(null);
  const [infoId, setInfoId] = React.useState<number | null>(null);
  const q = useQuery({
    queryKey: ["admin-all-applications"],
    queryFn: staffApi.listAllApplications,
    enabled: isAdmin,
    // A 4xx (refused, signed out) will not change on a second ask; only a 5xx/network blip is retried.
    retry: retryUnlessClientError,
  });

  // Memoised so `rows` keeps its identity between renders that change nothing it depends on —
  // usePagination's own memo over `rows` can then hit instead of re-slicing every render.
  const rows = React.useMemo(() => filterAdminApplications(q.data ?? [], filter, query), [q.data, filter, query]);
  const allCount = q.data?.length ?? 0;
  const { pageRows, page, setPage, pageSize, setPageSize, pageCount, total } = usePagination(rows);
  const filtered = isAdminApplicationsFilterActive(filter, query);

  const clearFilters = () => {
    setQuery("");
    setFilter("ALL");
    setSearchKey((k) => k + 1);
    setPage(1);
  };

  // "Clear" unmounts itself along with the filter it cleared, which would drop keyboard focus on
  // <body>. Hand it to the (just remounted) search box instead — the next thing a reader is likely
  // to want after clearing a search.
  const toolbarRef = React.useRef<HTMLDivElement>(null);
  React.useEffect(() => {
    if (searchKey === 0) return;
    toolbarRef.current?.querySelector("input")?.focus();
  }, [searchKey]);

  if (myRole && !can("staff:manage")) {
    return <NoAccessNotice message="Admin access only." />;
  }

  return (
    <div>
      <PageHeader title="All applications" subtitle="Every application — complete and incomplete — with full KYC detail. Admin only.">
        <ExportMenu
          title="All applications"
          subtitle="Full application register"
          fileBase="dhanboost-all-applications"
          columns={EXPORT_COLUMNS}
          rows={rows}
        />
        {/* `refetch()` ignores `enabled`, so the button is held until the role is known to be ADMIN. */}
        <button
          type="button"
          onClick={() => void q.refetch()}
          disabled={!isAdmin}
          className="flex items-center gap-1.5 rounded border border-line px-3 py-1.5 text-xs text-muted hover:bg-grey-100 hover:text-ink disabled:cursor-not-allowed disabled:opacity-40"
        >
          {q.isFetching ? <Loader2 size={13} className="animate-spin" /> : <RefreshCw size={13} />} Refresh
        </button>
      </PageHeader>

      <div ref={toolbarRef} className="mb-4 flex flex-wrap items-end gap-2">
        <SearchBar
          key={searchKey}
          initialValue={query}
          onSearch={(t) => {
            setQuery(t);
            setPage(1);
          }}
          placeholder="Search name, PAN, mobile, ID or status"
          ariaLabel="Search applications"
        />
        <Select
          aria-label="Completeness"
          value={filter}
          onChange={(e) => setFilter(e.target.value as CompletenessFilter)}
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
        {q.data && (
          <span className="pb-2 text-xs text-muted" aria-live="polite">
            {rows.length} of {allCount}
          </span>
        )}
      </div>

      {!isAdmin && !me.isPending ? (
        // `/me` answered without a session (or failed): nothing to ask the register for, and saying
        // "Admin access only" to a signed-out admin would be wrong.
        <ErrorState error={me.error} title="Couldn't confirm your staff session." onRetry={() => void me.refetch()} />
      ) : q.isPending ? (
        // Pending covers both "waiting for /me" (the query is disabled until the role is known) and
        // the fetch itself.
        <Skeleton variant="table" rows={8} cols={14} className="rounded border border-line bg-white" />
      ) : q.error ? (
        <ErrorState error={q.error} onRetry={() => void q.refetch()} />
      ) : (
        <div className="overflow-hidden rounded border border-line bg-white shadow-sm">
          {/* `staff-register-scroll` pins the header (globals.css); PaginationBar is already a sibling
              after this scroller, so it stays put. The offset clears the shell header, PageHeader,
              the search/completeness toolbar and the pagination footer. */}
          <div className="staff-table-scroll staff-register-scroll" style={{ "--register-offset": "24rem" } as React.CSSProperties}>
            <table className="staff-data-table">
              <caption className="sr-only">All applications</caption>
              <thead>
                <tr>
                  <th scope="col">S.No.</th>
                  <th scope="col" className="whitespace-nowrap">App</th>
                  <th scope="col">Customer</th>
                  <th scope="col" className="whitespace-nowrap">PAN</th>
                  <th scope="col" className="whitespace-nowrap">Account</th>
                  <th scope="col" className="whitespace-nowrap">IFSC</th>
                  <th scope="col" className="whitespace-nowrap">Mobile</th>
                  <th scope="col" className="whitespace-nowrap">Status</th>
                  <th scope="col" className="whitespace-nowrap">Stage since</th>
                  <th scope="col" className="whitespace-nowrap">Completeness</th>
                  <th scope="col" className="num whitespace-nowrap text-right">Amount</th>
                  <th scope="col" className="whitespace-nowrap">Credit</th>
                  <th scope="col" className="whitespace-nowrap">Risk</th>
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
                    <td className="whitespace-nowrap font-mono text-ink">{a.pan || "—"}</td>
                    <td className="whitespace-nowrap font-mono text-ink">{a.salaryAccountNumber || "—"}</td>
                    <td className="whitespace-nowrap font-mono text-ink">{a.salaryIfsc || "—"}</td>
                    <td className="whitespace-nowrap font-mono text-muted">{a.mobile || "—"}</td>
                    <td className="whitespace-nowrap">
                      <StatusBadge kind="application" value={a.status} />
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
                        <button
                          onClick={() => setInfoId(a.id)}
                          className="btn btn-sm btn-outline btn-icon"
                          aria-label="Quick summary"
                          title="Quick summary"
                        >
                          <Info size={14} />
                        </button>
                        <button onClick={() => setOpenId(a.id)} className="inline-flex items-center gap-1 text-navy hover:underline">
                          Open <ArrowRight size={14} />
                        </button>
                      </div>
                    </td>
                  </tr>
                ))}
                {rows.length === 0 && (
                  <EmptyState title="No applications match." inTable={14} />
                )}
              </tbody>
            </table>
          </div>
          <PaginationBar page={page} pageCount={pageCount} setPage={setPage} total={total} pageSize={pageSize} setPageSize={setPageSize} />
        </div>
      )}

      <ApplicationDetailDialog applicationId={openId} onClose={() => setOpenId(null)} />
      <ApplicationInfoDialog applicationId={infoId} onClose={() => setInfoId(null)} />
    </div>
  );
}
