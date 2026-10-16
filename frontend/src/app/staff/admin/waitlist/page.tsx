"use client";

import * as React from "react";
import { useQuery } from "@tanstack/react-query";
import { Download } from "lucide-react";
import { EmptyState, ErrorState, Input, Skeleton } from "@/components/ui";
import { PageHeader } from "@/components/staff/staff-ui";
import { useStaffMe, useCan, NoAccessNotice } from "@/components/staff/live-pipeline";
import { PaginationBar } from "@/components/staff/pipeline/pagination";
import { type FilterColumn } from "@/components/staff/column-filter";
import { istDay } from "@/lib/staff/column-filter-match";
import { useRegisterQuery } from "@/lib/staff/register-query";
import { useClientRegister } from "@/lib/staff/use-client-register";
import { Th, thBag, describeRegister } from "@/lib/staff/register-th";
import { adminApi, type WaitlistEntry } from "@/lib/api/applications";
import { exportCsv } from "@/lib/export/exporters";
import { useDebouncedValue } from "@/hooks/use-debounced-value";
import { formatDate } from "@/lib/utils";

/** Submitted date and name filter. Mobile, email, PAN and Aadhaar are identity, so they do not. */
const SORT_KEYS = ["createdAt", "fullName"] as const;

const COLUMN_LABEL: Record<string, string> = { createdAt: "Submitted", fullName: "Name" };

/** Admin · onboarding waitlist — submissions captured while onboarding is paused (full PAN/Aadhaar). ADMIN only. */
export default function AdminWaitlistPage() {
  const me = useStaffMe().data;
  const myRole = me?.role;
  const can = useCan();

  const rq = useRegisterQuery({
    registerId: "waitlist",
    staffId: me?.id,
    sortKeys: SORT_KEYS,
    filterKeys: SORT_KEYS,
    defaults: { sort: { key: "createdAt", dir: "desc" } },
  });
  // The search stays server-side (it matches PAN/Aadhaar, which the page never filters on), but the
  // register view now owns the string — and with it the `?q=` URL sync this page used to hand-roll.
  const q = useDebouncedValue(rq.state.q.trim(), 300);

  const list = useQuery({ queryKey: ["admin-waitlist", q], queryFn: () => adminApi.waitlist(q || undefined) });
  const rows = React.useMemo(() => list.data ?? [], [list.data]);

  const filterColumns = React.useMemo<Array<FilterColumn<WaitlistEntry>>>(
    () => [
      { key: "createdAt", kind: "date", value: (r) => formatDate(r.createdAt), date: (r) => istDay(r.createdAt) },
      { key: "fullName", kind: "text", value: (r) => r.fullName },
    ],
    [],
  );

  const cr = useClientRegister(rows, filterColumns, rq);
  const { pageRows, total, pageCount } = cr;
  const th = thBag(rq, cr, filterColumns, COLUMN_LABEL);
  const page = rq.state.page;
  const pageSize = rq.state.size;

  if (myRole && !can("waitlist:view")) {
    return <NoAccessNotice message="Admin access only." />;
  }

  const download = () =>
    exportCsv<WaitlistEntry>(
      "dhanboost-onboarding-waitlist",
      [
        { header: "Submitted", value: (r) => r.createdAt },
        { header: "Name", value: (r) => r.fullName },
        { header: "Mobile", value: (r) => r.mobile },
        { header: "Email", value: (r) => r.email },
        { header: "PAN", value: (r) => r.pan },
        { header: "Aadhaar", value: (r) => r.aadhaar },
      ],
      cr.rows,
    );

  return (
    <div className="space-y-4">
      <PageHeader
        title="Onboarding waitlist"
        subtitle={["Details left by borrowers while new onboarding is paused.", describeRegister(th)]
          .filter(Boolean)
          .join(" · ")}
      >
        <button type="button" className="btn btn-outline" onClick={download} disabled={cr.rows.length === 0}>
          <Download size={16} /> Export CSV
        </button>
      </PageHeader>

      <Input label="Search" value={rq.state.q} onChange={(e) => rq.setQ(e.target.value)} placeholder="Name, mobile, email, PAN or Aadhaar" />

      {list.isLoading ? (
        <Skeleton className="h-40 w-full" />
      ) : list.error ? (
        <ErrorState error={list.error} onRetry={() => void list.refetch()} />
      ) : (
        <div className="card overflow-x-auto p-0">
          <table className="staff-data-table">
            <thead>
              <tr>
                <Th th={th} col="createdAt" />
                <Th th={th} col="fullName" />
                <th>Mobile</th>
                <th>Email</th>
                <th>PAN</th>
                <th>Aadhaar</th>
              </tr>
            </thead>
            <tbody>
              {total === 0 ? (
                // In-table so the headers survive an emptying filter and it can still be cleared.
                <EmptyState
                  title={rq.activeCount > 0 && rows.length > 0 ? "No submissions match the column filters." : "No submissions yet."}
                  inTable={6}
                />
              ) : (
                pageRows.map((r) => (
                  <tr key={r.id}>
                    <td>{formatDate(r.createdAt)}</td>
                    <td>{r.fullName}</td>
                    <td>{r.mobile}</td>
                    <td>{r.email}</td>
                    <td className="font-mono">{r.pan}</td>
                    <td className="font-mono">{r.aadhaar}</td>
                  </tr>
                ))
              )}
            </tbody>
          </table>
          <PaginationBar page={page} pageCount={pageCount} setPage={rq.setPage} total={total} pageSize={pageSize} setPageSize={rq.setSize} />
        </div>
      )}
    </div>
  );
}
