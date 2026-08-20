"use client";

import * as React from "react";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { Loader2, RefreshCw, ShieldAlert, Trash2 } from "lucide-react";
import { Input, Select } from "@/components/ui";
import { PageHeader } from "@/components/staff/staff-ui";
import { errMessage, useStaffMe, NoAccessNotice } from "@/components/staff/live-pipeline";
import { ExportMenu } from "@/components/staff/export-menu";
import { hasPermission } from "@/lib/auth/rbac";
import { adminApi, type BlocklistType, type BlocklistResponse } from "@/lib/api/applications";
import { usePagination, PaginationBar } from "@/components/staff/pipeline/pagination";

const TYPES: { value: BlocklistType; label: string }[] = [
  { value: "PAN", label: "PAN" },
  { value: "AADHAAR_REF", label: "Aadhaar ref" },
  { value: "PHONE", label: "Phone" },
  { value: "DEVICE", label: "Device" },
  { value: "BANK_ACCOUNT", label: "Bank account" },
];

/** Admin · fraud blocklist — list / add / remove blocked identifiers (live /api/admin/blocklist). ADMIN only. */
export default function AdminBlocklistPage() {
  const myRole = useStaffMe().data?.role;
  const qc = useQueryClient();
  const q = useQuery({ queryKey: ["admin-blocklist"], queryFn: adminApi.listBlocklist });
  const [type, setType] = React.useState<BlocklistType>("PAN");
  const [value, setValue] = React.useState("");
  const [reason, setReason] = React.useState("");

  const invalidate = () => qc.invalidateQueries({ queryKey: ["admin-blocklist"] });
  const add = useMutation({
    mutationFn: () => adminApi.addBlocklist({ type, value: value.trim(), reason: reason.trim() || undefined }),
    onSuccess: () => { setValue(""); setReason(""); invalidate(); },
  });
  const remove = useMutation({
    mutationFn: (id: number) => adminApi.removeBlocklist(id),
    onSuccess: invalidate,
  });

  const rows = q.data ?? [];
  const { pageRows, page, setPage, pageSize, setPageSize, pageCount, total } = usePagination(rows);

  if (myRole && !hasPermission(myRole, "staff:manage")) {
    return <NoAccessNotice message="Admin access only." />;
  }

  return (
    <div>
      <PageHeader title="Fraud blocklist" subtitle="Blocked PAN, Aadhaar ref, phone, device and bank account identifiers.">
        <ExportMenu
          title="Fraud blocklist"
          fileBase="dhanboost-blocklist"
          columns={[
            { header: "Type", value: (b: BlocklistResponse) => b.type },
            { header: "Value", value: (b) => b.value },
            { header: "Reason", value: (b) => b.reason ?? "" },
            { header: "Active", value: (b) => (b.active ? "yes" : "no") },
          ]}
          rows={rows}
        />
        <button
          onClick={() => q.refetch()}
          className="flex items-center gap-1.5 rounded border border-line px-3 py-1.5 text-xs text-muted hover:bg-grey-100 hover:text-ink"
        >
          {q.isFetching ? <Loader2 size={13} className="animate-spin" /> : <RefreshCw size={13} />} Refresh
        </button>
      </PageHeader>

      <div className="mb-6 rounded border border-line bg-white p-5 shadow-sm">
        <div className="mb-3 flex items-center gap-2 text-sm font-semibold text-navy"><ShieldAlert size={16} /> Add to blocklist</div>
        <div className="flex flex-wrap items-end gap-3">
          <Select label="Type" value={type} onChange={(e) => setType(e.target.value as BlocklistType)} options={TYPES} className="!mb-0" />
          <Input label="Value" value={value} onChange={(e) => setValue(e.target.value)} placeholder="ABCDE1234F" className="!mb-0" inputClassName="w-48" />
          <Input label="Reason (optional)" value={reason} onChange={(e) => setReason(e.target.value)} placeholder="Confirmed fraud" className="!mb-0" inputClassName="w-56" />
          <button onClick={() => add.mutate()} disabled={add.isPending || !value.trim()} className="btn btn-gold disabled:opacity-50">
            {add.isPending ? <Loader2 size={14} className="animate-spin" /> : null} Add
          </button>
        </div>
        {add.error && <p className="mt-2 text-sm text-error-700">{errMessage(add.error)}</p>}
      </div>

      {q.isLoading ? (
        <div className="h-32 animate-pulse rounded border border-line bg-white" />
      ) : q.error ? (
        <p className="text-sm text-error-700">{errMessage(q.error)}</p>
      ) : (
        <div className="rounded border border-line bg-white shadow-sm">
          <div className="staff-table-scroll">
            <table className="staff-data-table">
              <thead>
                <tr>
                  <th>S.No.</th>
                  <th>Type</th>
                  <th>Value</th>
                  <th>Reason</th>
                  <th className="text-right">Action</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-line">
                {pageRows.map((b: BlocklistResponse, i) => (
                  <tr key={b.id}>
                    <td className="text-muted">{(page - 1) * pageSize + i + 1}</td>
                    <td><span className="rounded-full bg-navy-tint text-xs font-semibold text-navy">{b.type}</span></td>
                    <td className="font-mono text-ink">{b.value}</td>
                    <td className="text-muted">{b.reason || "—"}</td>
                    <td className="text-right">
                      <button onClick={() => remove.mutate(b.id)} disabled={remove.isPending}
                        className="btn btn-sm btn-outline disabled:opacity-50">
                        <Trash2 size={13} /> Remove
                      </button>
                    </td>
                  </tr>
                ))}
                {rows.length === 0 && (
                  <tr><td colSpan={5} className="text-center text-muted">No active blocklist entries.</td></tr>
                )}
              </tbody>
            </table>
          </div>
          <PaginationBar page={page} pageCount={pageCount} setPage={setPage} total={total} pageSize={pageSize} setPageSize={setPageSize} />
        </div>
      )}
    </div>
  );
}
