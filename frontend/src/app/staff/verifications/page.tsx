"use client";

import * as React from "react";
import { useQuery, useMutation } from "@tanstack/react-query";
import { Loader2, RefreshCw, Search, Bell } from "lucide-react";
import { Input } from "@/components/ui";
import { PageHeader } from "@/components/staff/staff-ui";
import { PermissionGate, NoAccessNotice, errMessage } from "@/components/staff/live-pipeline";
import { staffApi, type VerificationOverviewRow, type CheckStatus } from "@/lib/api/applications";
import { formatDateTime } from "@/lib/utils";

const STATUS_TABS: { key: string; label: string }[] = [
  { key: "", label: "All" },
  { key: "PENDING", label: "Pending" },
  { key: "FAIL", label: "Failed" },
  { key: "REVIEW", label: "In review" },
  { key: "PASS", label: "Passed" },
];

const PILL: Record<CheckStatus, string> = {
  PASS: "bg-success-100 text-success-700",
  REVIEW: "bg-warning-100 text-warning-800",
  FAIL: "bg-error-100 text-error-700",
  PENDING: "bg-grey-100 text-muted",
};

function humanize(t: string): string {
  const s = t.toLowerCase().replace(/_/g, " ");
  return s.length ? s.charAt(0).toUpperCase() + s.slice(1) : s;
}

/**
 * Pending-API dashboard (Phase 3.3): every verification check across all customers — pending,
 * failed, never-run, passed — with status tallies, filters, and a per-row borrower reminder.
 */
export default function VerificationsDashboardPage() {
  const [status, setStatus] = React.useState("");
  const [search, setSearch] = React.useState("");
  const [debounced, setDebounced] = React.useState("");
  React.useEffect(() => {
    const t = setTimeout(() => setDebounced(search.trim()), 300);
    return () => clearTimeout(t);
  }, [search]);

  const q = useQuery({
    queryKey: ["staff-verif-overview", status, debounced],
    queryFn: () => staffApi.verificationOverview({ status: status || undefined, q: debounced || undefined }),
    refetchInterval: 15_000,
  });
  const data = q.data;
  const rows = data?.rows ?? [];

  return (
    <div>
      <PageHeader title="Verification dashboard" subtitle="Every verification check across all customers — pending, failed, never-run and passed.">
        <button
          onClick={() => q.refetch()}
          className="flex items-center gap-1.5 rounded border border-line px-3 py-1.5 text-xs text-muted hover:bg-grey-100 hover:text-ink"
        >
          {q.isFetching ? <Loader2 size={13} className="animate-spin" /> : <RefreshCw size={13} />} Refresh
        </button>
      </PageHeader>

      <PermissionGate permission="kyc:approve" fallback={<NoAccessNotice />}>
        <div className="mb-4 grid grid-cols-2 gap-3 sm:grid-cols-5">
          <Tile label="Pending" value={data?.pending} valueClass="text-muted" />
          <Tile label="Failed" value={data?.failed} valueClass="text-error-700" />
          <Tile label="In review" value={data?.review} valueClass="text-warning-800" />
          <Tile label="Passed" value={data?.passed} valueClass="text-success-700" />
          <Tile label="Never run" value={data?.neverRun} valueClass="text-muted" />
        </div>

        <div className="mb-4 flex flex-wrap items-center justify-between gap-3">
          <div className="flex flex-wrap items-center gap-1 rounded-full border border-line bg-white p-1">
            {STATUS_TABS.map((s) => (
              <button
                key={s.key}
                onClick={() => setStatus(s.key)}
                className={`rounded-full px-3 py-1 text-sm font-semibold transition ${status === s.key ? "bg-navy text-white" : "text-muted hover:text-navy"}`}
              >
                {s.label}
              </button>
            ))}
          </div>
          <Input
            aria-label="Search by borrower / application / customer id"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            placeholder="Search borrower / app # / customer #"
            leftIcon={<Search size={15} />}
            className="!mb-0"
            inputClassName="w-72"
          />
        </div>

        <div className="rounded border border-line bg-white shadow-sm">
          {q.isLoading ? (
            <div className="h-40 animate-pulse rounded bg-grey-100" />
          ) : q.error ? (
            <p className="px-5 py-4 text-sm text-error-700">{errMessage(q.error)}</p>
          ) : rows.length === 0 ? (
            <p className="px-5 py-8 text-center text-sm text-muted">No verification checks{debounced ? ` for “${debounced}”` : ""}.</p>
          ) : (
            <div className="overflow-x-auto">
              <table className="w-full text-sm">
                <thead>
                  <tr className="border-b border-line text-left text-xs uppercase tracking-wide text-muted">
                    <th className="px-4 py-2.5 font-semibold">Check</th>
                    <th className="px-4 py-2.5 font-semibold">Status</th>
                    <th className="px-4 py-2.5 font-semibold">Borrower</th>
                    <th className="px-4 py-2.5 font-semibold">Application</th>
                    <th className="px-4 py-2.5 font-semibold">Message</th>
                    <th className="px-4 py-2.5 font-semibold">Updated</th>
                    <th className="px-4 py-2.5 font-semibold text-right">Action</th>
                  </tr>
                </thead>
                <tbody>
                  {rows.map((r, i) => (
                    <VerifRow key={`${r.applicationId}-${r.checkType}-${i}`} r={r} />
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>
      </PermissionGate>
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

function VerifRow({ r }: { r: VerificationOverviewRow }) {
  const m = useMutation({ mutationFn: () => staffApi.sendReminder(r.applicationId) });
  return (
    <tr className="border-b border-line/60">
      <td className="px-4 py-2.5 font-medium text-ink">{humanize(r.checkType)}</td>
      <td className="px-4 py-2.5">
        <span className={`rounded-full px-2 py-0.5 text-xs font-semibold ${PILL[r.status]}`}>{r.status}</span>
      </td>
      <td className="px-4 py-2.5 text-ink">{r.borrowerName ?? (r.customerId != null ? `#${r.customerId}` : "—")}</td>
      <td className="px-4 py-2.5 text-muted">#{r.applicationId}</td>
      <td className="px-4 py-2.5 text-muted"><span className="block max-w-[22rem] truncate" title={r.message ?? ""}>{r.message || "—"}</span></td>
      <td className="px-4 py-2.5 text-muted">{r.updatedAt ? formatDateTime(r.updatedAt) : "—"}</td>
      <td className="px-4 py-2.5 text-right">
        <button
          onClick={() => m.mutate()}
          disabled={m.isPending || m.isSuccess}
          title="Send the borrower a reminder of their pending steps"
          className="inline-flex items-center gap-1 rounded border border-line px-2 py-1 text-xs font-semibold text-navy hover:bg-navy-tint disabled:opacity-50"
        >
          {m.isPending ? <Loader2 size={12} className="animate-spin" /> : <Bell size={12} />}
          {m.isSuccess ? (m.data?.sent ? "Reminded" : "Nothing pending") : "Remind"}
        </button>
      </td>
    </tr>
  );
}
