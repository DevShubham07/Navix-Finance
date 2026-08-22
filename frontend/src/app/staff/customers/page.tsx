"use client";

import * as React from "react";
import Link from "next/link";
import { useSearchParams, useRouter, usePathname } from "next/navigation";
import { useQuery } from "@tanstack/react-query";
import { Loader2, RefreshCw, Search, ArrowRight, Contact, Info, ChevronDown, ChevronRight as ChevronRightIcon } from "lucide-react";
import { usePagination, PaginationBar } from "@/components/staff/pipeline/pagination";
import { Input } from "@/components/ui";
import { PageHeader } from "@/components/staff/staff-ui";
import { PermissionGate, NoAccessNotice, errMessage, useStaffMe } from "@/components/staff/live-pipeline";
import { ExportMenu } from "@/components/staff/export-menu";
import { CreditBadge } from "@/components/staff/credit-badge";
import { bureauStateLabel } from "@/components/staff/bureau-state";
import { CustomerDetailDialog } from "@/components/staff/customer-detail-dialog";
import { ApplicationInfoDialog } from "@/components/staff/application-info-dialog";
import { customersApi, staffApi, paiseToINR, statusLabel, type CustomerSummary, type ApplicationStatus } from "@/lib/api/applications";
import { AmountCell, DueCell, dpdFor } from "@/components/staff/pipeline/cells";
import {
  QueueDateFilter,
  rangeFor,
  type QueuePeriod,
  type QueueRange,
} from "@/components/staff/pipeline/queue-date-filter";
import { hasPermission } from "@/lib/auth/rbac";
import { formatDate, formatDateTime } from "@/lib/utils";
import {
  SEGMENTS,
  SEGMENT_LABEL,
  inSegment,
  segmentCounts,
  type CustomerSegment,
} from "@/lib/customers/segments";
import { isMine, decidedCustomerIds } from "@/lib/customers/mine";

/**
 * Customers — a borrower-centric roll-up across the loan aggregate. Segment chips filter
 * client-side (?seg=); search matches name, PAN, mobile or customer id (server-side).
 */
export default function CustomersPage() {
  return (
    <React.Suspense fallback={<div className="h-40 animate-pulse rounded bg-grey-100" />}>
      <CustomersPageInner />
    </React.Suspense>
  );
}

function CustomersPageInner() {
  const searchParams = useSearchParams();
  const router = useRouter();
  const pathname = usePathname();
  const segParam = (searchParams.get("seg") ?? "all") as CustomerSegment;
  const seg: CustomerSegment =
    segParam === "all" || SEGMENTS.includes(segParam) ? segParam : "all";
  const mine = searchParams.get("mine") === "1";
  const me = useStaffMe().data;

  const [search, setSearch] = React.useState("");
  const [debounced, setDebounced] = React.useState("");
  const [openId, setOpenId] = React.useState<number | null>(null);
  const [infoCustomerId, setInfoCustomerId] = React.useState<number | null>(null);
  const [period, setPeriod] = React.useState<QueuePeriod>("ALL");
  const [custom, setCustom] = React.useState<QueueRange>({});
  const range = React.useMemo(() => rangeFor(period, custom), [period, custom]);

  // Heads + ADMIN see the whole book; everyone else is scoped server-side by CustomerService to
  // customers assigned to them or that they've decided on. This only drives the notice below —
  // the enforcement is the backend's.
  const fullView = me?.role ? hasPermission(me.role, "customer:view:all") : true;

  React.useEffect(() => {
    const t = setTimeout(() => setDebounced(search.trim()), 300);
    return () => clearTimeout(t);
  }, [search]);

  const q = useQuery({
    // The range MUST be in the key — without it React Query serves the previous window's rows
    // when the filter changes. Normalised to "" because undefined is not a stable key boundary.
    queryKey: ["customers", debounced, range.from ?? "", range.to ?? ""],
    queryFn: () => customersApi.list(debounced || undefined, range),
  });

  // Only fetched when "mine" is active — needed so a customer this staffer decided on (but isn't
  // the owner of) still counts as "mine", matching the dashboard's "your book" tile exactly.
  const myDecisionsQ = useQuery({
    queryKey: ["customers-mine-decisions"],
    queryFn: () => staffApi.decisions(),
    enabled: mine && me?.id != null,
  });

  const rows = React.useMemo(() => q.data ?? [], [q.data]);
  const scoped = React.useMemo(() => {
    let list = rows;
    if (mine) {
      // Fail CLOSED when the staff id will not resolve: an unfiltered company list under the
      // "My customers" chip is worse than an empty one, and it would also disagree with the
      // dashboard's "borrowers in your book" tile, which links here.
      const sid = me?.id != null ? Number(me.id) : NaN;
      const decided = decidedCustomerIds(myDecisionsQ.data ?? []);
      list = Number.isFinite(sid) ? list.filter((c) => isMine(c, sid, decided)) : [];
    }
    return list;
  }, [rows, mine, me?.id, myDecisionsQ.data]);

  const counts = React.useMemo(() => segmentCounts(scoped), [scoped]);
  const filtered = React.useMemo(
    () => scoped.filter((c) => inSegment(c, seg)),
    [scoped, seg],
  );

  const { pageRows, page, setPage, pageSize, setPageSize, pageCount, total } = usePagination(filtered);
  const [collapsedDates, setCollapsedDates] = React.useState<Set<string>>(new Set());
  const toggleDate = (key: string) =>
    setCollapsedDates((prev) => {
      const next = new Set(prev);
      if (next.has(key)) next.delete(key);
      else next.add(key);
      return next;
    });
  // Consecutive rows on the current page grouped by their stage-change (statusChangedAt) date —
  // the same key the API sorts by, so a run of consecutive rows really does share one date.
  const dateGroups = React.useMemo(() => {
    const list: { key: string; rows: CustomerSummary[] }[] = [];
    for (const c of pageRows) {
      const key = c.statusChangedAt ? c.statusChangedAt.slice(0, 10) : "unknown";
      const last = list[list.length - 1];
      if (last && last.key === key) last.rows.push(c);
      else list.push({ key, rows: [c] });
    }
    return list;
  }, [pageRows]);

  function setSeg(next: CustomerSegment) {
    const p = new URLSearchParams(searchParams.toString());
    if (next === "all") p.delete("seg");
    else p.set("seg", next);
    const qs = p.toString();
    router.replace(qs ? `${pathname}?${qs}` : pathname);
  }

  return (
    <div>
      <PageHeader title="Customers" subtitle="Every borrower — search by name or ID, then open to see loans, payments and KYC.">
        <ExportMenu
          title="Customers"
          fileBase="dhanboost-customers"
          columns={[
            { header: "Customer ID", value: (c: CustomerSummary) => c.customerId },
            { header: "Date", value: (c) => (c.createdAt ? formatDateTime(c.createdAt) : "") },
            { header: "Name", value: (c) => c.name ?? "" },
            { header: "PAN", value: (c) => c.pan ?? "" },
            { header: "Mobile", value: (c) => c.mobile ?? "" },
            { header: "Account", value: (c) => c.accountNumber ?? "" },
            { header: "IFSC", value: (c) => c.ifsc ?? "" },
            { header: "Loan", value: (c) => c.latestLoanId ?? "" },
            { header: "Amount (₹)", value: (c) => (c.amountPaise != null ? (c.amountPaise / 100).toFixed(2) : "") },
            { header: "Amount type", value: (c) => (c.amountPaise == null ? "" : c.amountIsRequested ? "requested" : "eligible limit") },
            { header: "Due date", value: (c) => (c.loanDueDate ? formatDate(c.loanDueDate) : "") },
            { header: "DPD", value: (c) => (dpdFor(c.loanDueDate) || "") },
            { header: "Owner", value: (c) => c.ownerName ?? "Unallocated" },
            { header: "Applications", value: (c) => c.applicationCount },
            { header: "Loans", value: (c) => c.loanCount },
            { header: "Latest status", value: (c) => (c.latestStatus ? statusLabel(c.latestStatus as ApplicationStatus) : "") },
            { header: "Stage date", value: (c) => (c.statusChangedAt ? formatDateTime(c.statusChangedAt) : "") },
            { header: "Loan status", value: (c) => c.loanStatus ?? "" },
            { header: "Outstanding (₹)", value: (c) => (c.totalOutstandingPaise / 100).toFixed(2) },
            { header: "Credit score", value: (c) => c.creditScore ?? "" },
            { header: "Credit rating", value: (c) => (c.starRating != null ? c.starRating.toFixed(1) : "") },
          ]}
          rows={filtered}
        />
        <button
          onClick={() => q.refetch()}
          className="flex items-center gap-1.5 rounded border border-line px-3 py-1.5 text-xs text-muted hover:bg-grey-100 hover:text-ink"
        >
          {q.isFetching ? <Loader2 size={13} className="animate-spin" /> : <RefreshCw size={13} />} Refresh
        </button>
      </PageHeader>

      <PermissionGate permission="customer:view" fallback={<NoAccessNotice />}>
        <div className="mb-3 flex flex-wrap gap-1.5">
          <button
            type="button"
            className={`cal-preset${seg === "all" ? " on" : ""}`}
            onClick={() => setSeg("all")}
          >
            {SEGMENT_LABEL.all} ({counts.all})
          </button>
          {SEGMENTS.map((s) => (
            <button
              key={s}
              type="button"
              className={`cal-preset${seg === s ? " on" : ""}`}
              onClick={() => setSeg(s)}
            >
              {SEGMENT_LABEL[s]} ({counts[s]})
            </button>
          ))}
        </div>

        <div className="mb-4 flex flex-wrap items-center gap-2">
          <Input
            aria-label="Search customers"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            placeholder="Name, PAN, mobile or customer ID"
            leftIcon={<Search size={15} />}
            className="!mb-0"
            inputClassName="w-72"
          />
          <QueueDateFilter period={period} setPeriod={setPeriod} custom={custom} setCustom={setCustom} />
          {mine && (
            <span className="rounded-full bg-navy-tint px-2.5 py-0.5 text-xs font-semibold text-navy">
              My customers
            </span>
          )}
        </div>

        {!fullView && (
          // Without this a scoped staffer reads a short list as a bug and raises a ticket.
          <p className="mb-3 rounded border border-line bg-grey-50 px-3 py-2 text-xs text-muted">
            Showing only customers assigned to you or that you have recorded a decision on.
          </p>
        )}

        <div className="staff-table-scroll rounded border border-line bg-white shadow-sm">
          {q.isLoading ? (
            <div className="h-40 animate-pulse rounded bg-grey-100" />
          ) : q.error ? (
            <p className="px-5 py-4 text-sm text-error-700">{errMessage(q.error)}</p>
          ) : filtered.length === 0 ? (
            <p className="px-5 py-8 text-center text-sm text-muted">
              No customers{debounced ? ` for “${debounced}”` : ""}{seg !== "all" ? ` in ${SEGMENT_LABEL[seg]}` : ""}
              {period !== "ALL" ? " in the selected date range" : ""}.
            </p>
          ) : (
            <table className="staff-data-table">
              <thead>
                {/* Column set deliberately mirrors the live-applications queue (identity, date,
                    contact, PAN, bank, loan, amount, due, credit) so a staffer reads the same row
                    shape on both screens. Account/IFSC/amount/due describe the customer's LATEST
                    application; the roll-up columns (Owner/Loans/Outstanding) are customer-only. */}
                <tr>
                  <th>S.No.</th>
                  <th className="staff-sticky-identity">Customer</th>
                  <th title="Signup / application start date">Date</th>
                  <th>Mobile</th>
                  <th>PAN</th>
                  <th>Account</th>
                  <th>IFSC</th>
                  <th>Loan</th>
                  <th>Amount</th>
                  <th>Due</th>
                  <th>Owner</th>
                  <th>Loans</th>
                  <th>Outstanding</th>
                  <th>CIBIL</th>
                  <th>Latest status</th>
                  <th title="When this customer entered their current status">Stage date</th>
                  <th className="staff-sticky-actions text-right">Open</th>
                </tr>
              </thead>
              <tbody>
                {(() => {
                  let running = (page - 1) * pageSize;
                  return dateGroups.map((group) => {
                    const isCollapsed = collapsedDates.has(group.key);
                    const groupRows = group.rows.map((c) => {
                      running += 1;
                      return { c, sno: running };
                    });
                    return (
                      <React.Fragment key={group.key}>
                        <tr>
                          <td colSpan={17} className="bg-grey-50 px-3 py-2">
                            <button
                              type="button"
                              onClick={() => toggleDate(group.key)}
                              className="flex items-center gap-1.5 font-semibold text-ink"
                            >
                              {isCollapsed ? <ChevronRightIcon size={14} /> : <ChevronDown size={14} />}
                              {group.key === "unknown" ? "Date unknown" : formatDate(group.key)} · {group.rows.length} customer
                              {group.rows.length === 1 ? "" : "s"}
                            </button>
                          </td>
                        </tr>
                        {!isCollapsed &&
                          groupRows.map(({ c, sno }) => (
                  <tr key={c.customerId} className="hover:bg-grey-50">
                    <td className="text-muted">{sno}</td>
                    <td className="staff-cell staff-sticky-identity">
                      <button
                        onClick={() => setOpenId(c.customerId)}
                        className="flex max-w-full items-center gap-2 text-left"
                        title={c.name ?? undefined}
                      >
                        <span className="grid h-6 w-6 flex-shrink-0 place-items-center rounded-full bg-navy-tint text-navy">
                          <Contact size={13} />
                        </span>
                        <span className="min-w-0">
                          <span className="block truncate font-semibold text-ink hover:underline">{c.name ?? "—"}</span>
                          <span className="block truncate text-xs text-muted">
                            <Link
                              href={`/staff/customers/${c.customerId}`}
                              onClick={(e) => e.stopPropagation()}
                              className="hover:underline"
                            >
                              #{c.customerId}
                            </Link>
                          </span>
                        </span>
                      </button>
                    </td>
                    <td className="whitespace-nowrap text-muted">
                      {c.createdAt ? formatDateTime(c.createdAt) : "—"}
                    </td>
                    <td className="font-mono text-muted">{c.mobile ?? "—"}</td>
                    <td className="font-mono text-ink">{c.pan || "—"}</td>
                    <td className="font-mono text-ink">{c.accountNumber || "—"}</td>
                    <td className="font-mono text-ink">{c.ifsc || "—"}</td>
                    <td className="font-mono text-muted">{c.latestLoanId != null ? `#${c.latestLoanId}` : "—"}</td>
                    <td>
                      <span className="font-semibold text-ink">
                        <AmountCell amountPaise={c.amountPaise} isRequested={c.amountIsRequested === true} />
                      </span>
                    </td>
                    <td className="whitespace-nowrap">
                      <DueCell dueDate={c.loanDueDate} markedPendingAt={c.markedPendingAt} />
                    </td>
                    <td className="staff-cell text-ink">{c.ownerName ?? <span className="text-muted">Unallocated</span>}</td>
                    <td className="text-ink">{c.loanCount} <span className="text-xs text-muted">/ {c.applicationCount} apps</span></td>
                    <td className="font-semibold text-ink">{paiseToINR(c.totalOutstandingPaise)}</td>
                    <td>
                      {c.starRating != null || c.creditScore != null ? (
                        <CreditBadge starRating={c.starRating} creditScore={c.creditScore} />
                      ) : c.bureauState === "NO_RECORD" ? (
                        <span className="text-xs text-muted">{bureauStateLabel("NO_RECORD", "long")}</span>
                      ) : (
                        <span className="text-xs text-muted">{bureauStateLabel("NOT_FETCHED", "short")}</span>
                      )}
                    </td>
                    <td>
                      {c.latestStatus ? (
                        <span className="rounded-full bg-grey-100 px-2 py-0.5 text-xs font-semibold text-ink">
                          {statusLabel(c.latestStatus as ApplicationStatus)}
                        </span>
                      ) : "—"}
                    </td>
                    <td className="whitespace-nowrap text-muted">
                      {c.statusChangedAt ? formatDateTime(c.statusChangedAt) : "—"}
                    </td>
                    <td className="staff-sticky-actions text-right">
                      <div className="flex items-center justify-end gap-1.5">
                        <button
                          onClick={() => setInfoCustomerId(c.customerId)}
                          className="btn btn-sm btn-outline btn-icon"
                          aria-label="Quick summary"
                          title="Quick summary"
                        >
                          <Info size={14} />
                        </button>
                        <button onClick={() => setOpenId(c.customerId)} className="inline-flex items-center gap-1 text-navy hover:underline">
                          Open <ArrowRight size={14} />
                        </button>
                      </div>
                    </td>
                  </tr>
                          ))}
                      </React.Fragment>
                    );
                  });
                })()}
              </tbody>
            </table>
          )}
          <PaginationBar
            page={page}
            pageCount={pageCount}
            setPage={setPage}
            total={total}
            pageSize={pageSize}
            setPageSize={setPageSize}
          />
        </div>
      </PermissionGate>

      <CustomerDetailDialog customerId={openId} onClose={() => setOpenId(null)} />
      <ApplicationInfoDialog customerId={infoCustomerId} onClose={() => setInfoCustomerId(null)} />
    </div>
  );
}
