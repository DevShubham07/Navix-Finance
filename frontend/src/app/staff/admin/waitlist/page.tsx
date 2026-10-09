"use client";

import * as React from "react";
import { useQuery } from "@tanstack/react-query";
import { Download } from "lucide-react";
import { EmptyState, ErrorState, Input, Skeleton } from "@/components/ui";
import { PageHeader } from "@/components/staff/staff-ui";
import { useStaffMe, useCan, NoAccessNotice } from "@/components/staff/live-pipeline";
import { usePagination, PaginationBar } from "@/components/staff/pipeline/pagination";
import { adminApi, type WaitlistEntry } from "@/lib/api/applications";
import { exportCsv } from "@/lib/export/exporters";
import { useDebouncedValue } from "@/hooks/use-debounced-value";

const fmtDate = (iso: string) => new Date(iso).toLocaleDateString("en-IN", { day: "2-digit", month: "short", year: "numeric" });

/** Admin · onboarding waitlist — submissions captured while onboarding is paused (full PAN/Aadhaar). ADMIN only. */
export default function AdminWaitlistPage() {
  const myRole = useStaffMe().data?.role;
  const can = useCan();
  const [search, setSearch] = React.useState("");
  // Seed from `?q=` after mount (reading it during render would break hydration).
  React.useEffect(() => {
    setSearch(new URLSearchParams(window.location.search).get("q") ?? "");
  }, []);
  const q = useDebouncedValue(search.trim(), 300);
  React.useEffect(() => {
    const url = new URL(window.location.href);
    if (q) url.searchParams.set("q", q);
    else url.searchParams.delete("q");
    window.history.replaceState(null, "", url);
  }, [q]);

  const list = useQuery({ queryKey: ["admin-waitlist", q], queryFn: () => adminApi.waitlist(q || undefined) });
  const rows = React.useMemo(() => list.data ?? [], [list.data]);
  const { pageRows, page, setPage, pageSize, setPageSize, pageCount, total } = usePagination(rows);

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
      rows,
    );

  return (
    <div className="space-y-4">
      <PageHeader title="Onboarding waitlist" subtitle="Details left by borrowers while new onboarding is paused.">
        <button type="button" className="btn btn-outline" onClick={download} disabled={rows.length === 0}>
          <Download size={16} /> Export CSV
        </button>
      </PageHeader>

      <Input label="Search" value={search} onChange={(e) => setSearch(e.target.value)} placeholder="Name, mobile, email, PAN or Aadhaar" />

      {list.isLoading ? (
        <Skeleton className="h-40 w-full" />
      ) : list.error ? (
        <ErrorState error={list.error} onRetry={() => void list.refetch()} />
      ) : (
        <div className="card overflow-x-auto p-0">
          <table className="staff-data-table">
            <thead>
              <tr>
                <th>Submitted</th>
                <th>Name</th>
                <th>Mobile</th>
                <th>Email</th>
                <th>PAN</th>
                <th>Aadhaar</th>
              </tr>
            </thead>
            <tbody>
              {pageRows.length === 0 ? (
                <EmptyState title="No submissions yet." inTable={6} />
              ) : (
                pageRows.map((r) => (
                  <tr key={r.id}>
                    <td>{fmtDate(r.createdAt)}</td>
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
          <PaginationBar page={page} pageCount={pageCount} setPage={setPage} total={total} pageSize={pageSize} setPageSize={setPageSize} />
        </div>
      )}
    </div>
  );
}
