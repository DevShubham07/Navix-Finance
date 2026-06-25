"use client";

import * as React from "react";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { Loader2, RefreshCw, UserX } from "lucide-react";
import { Select } from "@/components/ui";
import { PageHeader } from "@/components/staff/staff-ui";
import { errMessage } from "@/components/staff/live-pipeline";
import {
  adminApi,
  type StaffResponse,
  type StaffRoleName,
  type StaffStatus,
} from "@/lib/api/applications";

const ROLES: StaffRoleName[] = [
  "KYC_APPROVER", "CREDIT_EXECUTIVE", "CREDIT_HEAD", "DISBURSEMENT_HEAD",
  "ACCOUNTANT", "COLLECTION_HEAD", "COLLECTION_EXECUTIVE", "ADMIN", "DEVELOPER",
];
const STATUSES: StaffStatus[] = ["INVITED", "ACTIVE", "DISABLED"];

/** Admin · staff accounts — list, change role/status, disable (live /api/staff). */
export default function AdminStaffPage() {
  const q = useQuery({ queryKey: ["admin-staff"], queryFn: adminApi.listStaff });

  return (
    <div>
      <PageHeader title="Staff accounts" subtitle="Manage staff roles and access status.">
        <button
          onClick={() => q.refetch()}
          className="flex items-center gap-1.5 rounded border border-line px-3 py-1.5 text-xs text-muted hover:bg-grey-100 hover:text-ink"
        >
          {q.isFetching ? <Loader2 size={13} className="animate-spin" /> : <RefreshCw size={13} />} Refresh
        </button>
      </PageHeader>

      {q.isLoading ? (
        <div className="h-40 animate-pulse rounded border border-line bg-white" />
      ) : q.error ? (
        <p className="text-sm text-error-700">{errMessage(q.error)}</p>
      ) : (
        <div className="overflow-hidden rounded border border-line bg-white shadow-sm">
          <table className="w-full text-sm">
            <thead className="border-b border-line bg-grey-50 text-left text-xs uppercase tracking-wide text-muted">
              <tr>
                <th className="px-4 py-2.5">Staff</th>
                <th className="px-4 py-2.5">Role</th>
                <th className="px-4 py-2.5">Status</th>
                <th className="px-4 py-2.5 text-right">Actions</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-line">
              {(q.data ?? []).map((s) => <StaffRow key={s.id} staff={s} />)}
              {(q.data ?? []).length === 0 && (
                <tr><td colSpan={4} className="px-4 py-6 text-center text-muted">No staff accounts.</td></tr>
              )}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}

function StaffRow({ staff }: { staff: StaffResponse }) {
  const qc = useQueryClient();
  const [role, setRole] = React.useState<StaffRoleName>(staff.role);
  const [status, setStatus] = React.useState<StaffStatus>(staff.status);
  const dirty = role !== staff.role || status !== staff.status;

  const invalidate = () => qc.invalidateQueries({ queryKey: ["admin-staff"] });
  const save = useMutation({
    mutationFn: () => adminApi.updateStaff(staff.id, { role, status }),
    onSuccess: invalidate,
  });
  const disable = useMutation({
    mutationFn: () => adminApi.disableStaff(staff.id),
    onSuccess: invalidate,
  });

  return (
    <tr>
      <td className="px-4 py-3">
        <div className="font-semibold text-ink">{staff.name}</div>
        <div className="text-xs text-muted">{staff.email} · #{staff.id}</div>
      </td>
      <td className="px-4 py-3">
        <Select className="!mb-0" value={role} onChange={(e) => setRole(e.target.value as StaffRoleName)}
          options={ROLES.map((r) => ({ value: r, label: r }))} />
      </td>
      <td className="px-4 py-3">
        <Select className="!mb-0" value={status} onChange={(e) => setStatus(e.target.value as StaffStatus)}
          options={STATUSES.map((s) => ({ value: s, label: s }))} />
      </td>
      <td className="px-4 py-3">
        <div className="flex items-center justify-end gap-2">
          {(save.error || disable.error) && (
            <span className="text-xs text-error-700">{errMessage(save.error || disable.error)}</span>
          )}
          <button
            onClick={() => save.mutate()}
            disabled={!dirty || save.isPending}
            className="btn btn-sm btn-navy disabled:opacity-50"
          >
            {save.isPending ? <Loader2 size={13} className="animate-spin" /> : null} Save
          </button>
          <button
            onClick={() => disable.mutate()}
            disabled={disable.isPending || staff.status === "DISABLED"}
            className="btn btn-sm btn-outline disabled:opacity-50"
          >
            <UserX size={13} /> Disable
          </button>
        </div>
      </td>
    </tr>
  );
}
