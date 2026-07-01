"use client";

import * as React from "react";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { Loader2, RefreshCw, UserPlus, UserX } from "lucide-react";
import { Input, Select } from "@/components/ui";
import { PageHeader } from "@/components/staff/staff-ui";
import { errMessage, useStaffMe, NoAccessNotice } from "@/components/staff/live-pipeline";
import { ExportMenu } from "@/components/staff/export-menu";
import { hasPermission } from "@/lib/auth/rbac";
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

/** Admin · staff accounts — list, change role/status, disable (live /api/staff). ADMIN only. */
export default function AdminStaffPage() {
  const role = useStaffMe().data?.role;
  const q = useQuery({ queryKey: ["admin-staff"], queryFn: adminApi.listStaff });

  if (role && !hasPermission(role, "staff:manage")) {
    return <NoAccessNotice message="Admin access only." />;
  }

  return (
    <div>
      <PageHeader title="Staff accounts" subtitle="Manage staff roles and access status.">
        <ExportMenu
          title="Staff accounts"
          fileBase="navix-staff"
          columns={[
            { header: "ID", value: (s: StaffResponse) => s.id },
            { header: "Name", value: (s) => s.name },
            { header: "Email", value: (s) => s.email },
            { header: "Role", value: (s) => s.role },
            { header: "Status", value: (s) => s.status },
          ]}
          rows={q.data ?? []}
        />
        <button
          onClick={() => q.refetch()}
          className="flex items-center gap-1.5 rounded border border-line px-3 py-1.5 text-xs text-muted hover:bg-grey-100 hover:text-ink"
        >
          {q.isFetching ? <Loader2 size={13} className="animate-spin" /> : <RefreshCw size={13} />} Refresh
        </button>
      </PageHeader>

      <CreateStaffForm />

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

/** Create a staff account with an email + password so they can sign in (ADMIN only). */
function CreateStaffForm() {
  const qc = useQueryClient();
  const [email, setEmail] = React.useState("");
  const [name, setName] = React.useState("");
  const [role, setRole] = React.useState<StaffRoleName>("KYC_APPROVER");
  const [password, setPassword] = React.useState("");

  const canSubmit =
    email.trim().length > 0 && name.trim().length > 0 && password.length >= 4;

  const create = useMutation({
    mutationFn: () =>
      adminApi.createStaff({ email: email.trim(), name: name.trim(), role, password }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["admin-staff"] });
      setEmail("");
      setName("");
      setPassword("");
      setRole("KYC_APPROVER");
    },
  });

  return (
    <div className="mb-4 rounded border border-line bg-white p-4 shadow-sm">
      <h2 className="mb-3 text-sm font-semibold text-ink">Create staff account</h2>
      <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
        <Input
          label="Email" type="email" autoComplete="off" value={email}
          onChange={(e) => setEmail(e.target.value)} placeholder="person@navix.example"
        />
        <Input
          label="Name" value={name}
          onChange={(e) => setName(e.target.value)} placeholder="Full name"
        />
        <Select
          label="Role" value={role}
          onChange={(e) => setRole(e.target.value as StaffRoleName)}
          options={ROLES.map((r) => ({ value: r, label: r }))}
        />
        <Input
          label="Password" type="password" autoComplete="new-password" value={password}
          onChange={(e) => setPassword(e.target.value)} placeholder="Set a password (min 4)"
        />
      </div>
      <div className="mt-3 flex items-center gap-3">
        <button
          onClick={() => create.mutate()}
          disabled={!canSubmit || create.isPending}
          className="btn btn-sm btn-navy disabled:opacity-50"
        >
          {create.isPending ? <Loader2 size={13} className="animate-spin" /> : <UserPlus size={13} />} Create staff
        </button>
        {create.error ? (
          <span className="text-xs text-error-700">{errMessage(create.error)}</span>
        ) : create.isSuccess ? (
          <span className="text-xs text-success-700">Staff account created.</span>
        ) : null}
      </div>
    </div>
  );
}

function StaffRow({ staff }: { staff: StaffResponse }) {
  const qc = useQueryClient();
  const [role, setRole] = React.useState<StaffRoleName>(staff.role);
  const [status, setStatus] = React.useState<StaffStatus>(staff.status);
  // Re-sync the dropdowns to server truth whenever the persisted row changes (e.g. after a
  // successful Save/Disable + refetch). Without this the local state stays stale, so the row keeps
  // showing the old status AND `dirty` wrongly flips true — re-enabling Save, whose click would
  // silently re-activate the account just disabled.
  React.useEffect(() => {
    setRole(staff.role);
    setStatus(staff.status);
  }, [staff.role, staff.status]);
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
