"use client";

import * as React from "react";
import { useQuery } from "@tanstack/react-query";
import { Loader2, RefreshCw } from "lucide-react";
import { Input, Select } from "@/components/ui";
import { PageHeader } from "@/components/staff/staff-ui";
import { errMessage, useStaffMe, NoAccessNotice } from "@/components/staff/live-pipeline";
import { ExportMenu } from "@/components/staff/export-menu";
import { LeadsTracker } from "@/components/staff/leads-tracker";
import { hasPermission } from "@/lib/auth/rbac";
import {
  leadsApi,
  adminApi,
  paiseToINR,
  type LeadCallStatus,
  type LeadSource,
  type LeadView,
} from "@/lib/api/applications";

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

/**
 * Admin · leads dashboard — tracker graphs + filterable company-wide lead register.
 */
export default function AdminLeadsPage() {
  const myRole = useStaffMe().data?.role;
  const [from, setFrom] = React.useState(() => daysAgoIso(30));
  const [to, setTo] = React.useState(todayIso);
  const [createdBy, setCreatedBy] = React.useState<number | "">("");
  const [q, setQ] = React.useState("");
  const [callStatus, setCallStatus] = React.useState<LeadCallStatus | "">("");
  const [source, setSource] = React.useState<LeadSource | "">("");

  const staffQ = useQuery({
    queryKey: ["admin-staff"],
    queryFn: adminApi.listStaff,
    enabled: !!myRole && hasPermission(myRole, "staff:manage"),
  });

  const telecallers = (staffQ.data ?? []).filter(
    (s) => s.role === "TELECALLER" || s.role === "ADMIN",
  );

  const filter = {
    from: from || undefined,
    to: to || undefined,
    createdBy: createdBy === "" ? undefined : Number(createdBy),
    q: q || undefined,
    callStatus: callStatus || undefined,
    source: source || undefined,
  };

  const stats = useQuery({
    queryKey: ["lead-stats", from, to, createdBy],
    queryFn: () =>
      leadsApi.stats({
        from: from || undefined,
        to: to || undefined,
        createdBy: createdBy === "" ? undefined : Number(createdBy),
      }),
    enabled: !!myRole && hasPermission(myRole, "staff:manage"),
  });

  const list = useQuery({
    queryKey: ["admin-leads", filter],
    queryFn: () => leadsApi.list(filter),
    enabled: !!myRole && hasPermission(myRole, "staff:manage"),
  });

  if (myRole && !hasPermission(myRole, "staff:manage")) {
    return <NoAccessNotice message="Admin access only." />;
  }

  const rows = list.data ?? [];

  return (
    <div>
      <PageHeader
        title="Leads dashboard"
        subtitle="Tracker graphs and company-wide lead results. Filter by date, telecaller, status and source."
      >
        <ExportMenu
          title="Leads"
          subtitle="Telecaller lead register"
          fileBase="dhanboost-leads"
          columns={[
            { header: "Name", value: (r: LeadView) => r.name },
            { header: "Mobile", value: (r) => r.mobile },
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
            { header: "Created by", value: (r) => r.createdByStaffName ?? String(r.createdByStaffId) },
            { header: "Created at", value: (r) => r.createdAt },
          ]}
          rows={rows}
          disabled={rows.length === 0}
        />
        <button
          type="button"
          className="btn btn-sm btn-outline"
          onClick={() => {
            stats.refetch();
            list.refetch();
          }}
          disabled={stats.isFetching || list.isFetching}
        >
          {stats.isFetching || list.isFetching ? (
            <Loader2 size={14} className="animate-spin" />
          ) : (
            <RefreshCw size={14} />
          )}
          Refresh
        </button>
      </PageHeader>

      <div className="mb-4 flex flex-wrap items-end gap-3">
        <Input label="From" type="date" value={from} onChange={(e) => setFrom(e.target.value)} className="!mb-0" />
        <Input label="To" type="date" value={to} onChange={(e) => setTo(e.target.value)} className="!mb-0" />
        <Select
          label="Telecaller"
          className="!mb-0"
          value={createdBy === "" ? "" : String(createdBy)}
          onChange={(e) =>
            setCreatedBy(e.target.value === "" ? "" : Number(e.target.value))
          }
        >
          <option value="">All</option>
          {telecallers.map((s) => (
            <option key={s.id} value={s.id}>
              {s.name} ({s.role})
            </option>
          ))}
        </Select>
        <Input
          label="Search"
          value={q}
          onChange={(e) => setQ(e.target.value)}
          placeholder="Name or mobile"
          className="!mb-0"
        />
        <Select
          label="Status"
          className="!mb-0"
          value={callStatus}
          onChange={(e) => setCallStatus(e.target.value as LeadCallStatus | "")}
        >
          <option value="">All</option>
          {CALL_STATUSES.map((s) => (
            <option key={s} value={s}>
              {s.replace(/_/g, " ")}
            </option>
          ))}
        </Select>
        <Select
          label="Source"
          className="!mb-0"
          value={source}
          onChange={(e) => setSource(e.target.value as LeadSource | "")}
        >
          <option value="">All</option>
          {SOURCES.map((s) => (
            <option key={s} value={s}>
              {s.replace(/_/g, " ")}
            </option>
          ))}
        </Select>
      </div>

      {(stats.isError || list.isError) && (
        <p className="mb-3 text-sm text-red-700">
          {errMessage(stats.error ?? list.error)}
        </p>
      )}

      <section className="mb-8">
        <h2 className="mb-3 font-serif text-xl text-navy">Tracker</h2>
        {stats.isLoading && (
          <div className="flex h-40 items-center justify-center text-navy/40">
            <Loader2 className="animate-spin" />
          </div>
        )}
        {stats.data && <LeadsTracker stats={stats.data} />}
      </section>

      <section>
        <h2 className="mb-3 font-serif text-xl text-navy">Lead register</h2>
        <div className="overflow-x-auto rounded-lg border border-navy/10 bg-white">
          <table className="w-full text-left text-sm">
            <thead className="border-b border-navy/10 bg-ivory text-xs uppercase text-navy/60">
              <tr>
                <th className="px-3 py-2">Name</th>
                <th className="px-3 py-2">Mobile</th>
                <th className="px-3 py-2">Source</th>
                <th className="px-3 py-2">Status</th>
                <th className="px-3 py-2">★</th>
                <th className="px-3 py-2">Remarks</th>
                <th className="px-3 py-2">By</th>
                <th className="px-3 py-2">Created</th>
              </tr>
            </thead>
            <tbody>
              {rows.length === 0 && (
                <tr>
                  <td colSpan={8} className="px-3 py-8 text-center text-navy/40">
                    {list.isLoading ? "Loading…" : "No leads in this filter."}
                  </td>
                </tr>
              )}
              {rows.map((row) => (
                <tr key={row.id} className="border-b border-navy/5">
                  <td className="px-3 py-2 font-medium text-navy">{row.name}</td>
                  <td className="px-3 py-2 font-mono text-xs">{row.mobile}</td>
                  <td className="px-3 py-2">
                    {row.source ?? "—"}
                    {row.sourceDetail ? ` · ${row.sourceDetail}` : ""}
                  </td>
                  <td className="px-3 py-2 text-xs">{row.callStatus.replace(/_/g, " ")}</td>
                  <td className="px-3 py-2">{row.qualityRating ? `${row.qualityRating}★` : "—"}</td>
                  <td className="max-w-[200px] truncate px-3 py-2 text-navy/70">
                    {row.remarks || "—"}
                  </td>
                  <td className="px-3 py-2 text-navy/70">
                    {row.createdByStaffName || row.createdByStaffId}
                  </td>
                  <td className="px-3 py-2 font-mono text-xs text-navy/50">
                    {row.createdAt?.slice(0, 10)}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </section>
    </div>
  );
}
