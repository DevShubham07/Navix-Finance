"use client";

import * as React from "react";
import { useSearchParams } from "next/navigation";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { Loader2, RefreshCw, UserPlus, UserX, UserCheck } from "lucide-react";
import { Input, Select } from "@/components/ui";
import { Badge, type BadgeVariant } from "@/components/ui/badge";
import { Tabs, type TabDef } from "@/components/ui/tabs";
import { PageHeader } from "@/components/staff/staff-ui";
import { errMessage, useStaffMe, NoAccessNotice, ROLE_LABEL } from "@/components/staff/live-pipeline";
import { ExportMenu } from "@/components/staff/export-menu";
import { SearchBar } from "@/components/staff/search-bar";
import { hasPermission } from "@/lib/auth/rbac";
import {
  adminApi,
  type StaffResponse,
  type StaffRoleName,
  type StaffStatus,
} from "@/lib/api/applications";
import { usePagination, PaginationBar } from "@/components/staff/pipeline/pagination";

const ROLES = Object.keys(ROLE_LABEL) as StaffRoleName[];
/** Only these are selectable targets — the backend now rejects a transition into INVITED. */
const EDITABLE_STATUSES: StaffStatus[] = ["ACTIVE", "DISABLED"];
const STATUS_LABEL: Record<StaffStatus, string> = {
  ACTIVE: "Active",
  INVITED: "Invited",
  DISABLED: "Disabled",
};
const STATUS_BADGE: Record<StaffStatus, BadgeVariant> = {
  ACTIVE: "success",
  INVITED: "warning",
  DISABLED: "neutral",
};
const TAB_ORDER: StaffStatus[] = ["ACTIVE", "INVITED", "DISABLED"];

/** Admin · staff accounts — list, change role/status, disable (live /api/staff). ADMIN only. */
export default function AdminStaffPage() {
  return (
    <React.Suspense fallback={<div className="h-40 animate-pulse rounded border border-line bg-white" />}>
      <AdminStaffPageInner />
    </React.Suspense>
  );
}

function AdminStaffPageInner() {
  const me = useStaffMe();
  const role = me.data?.role;
  const q = useQuery({ queryKey: ["admin-staff"], queryFn: adminApi.listStaff });

  const [tab, setTab] = React.useState<StaffStatus>("ACTIVE");
  const [query, setQuery] = React.useState(useSearchParams().get("q") ?? "");

  const filtered = React.useMemo(() => {
    const needle = query.trim().toLowerCase();
    const rows = q.data ?? [];
    return needle
      ? rows.filter((s) => s.name.toLowerCase().includes(needle) || s.email.toLowerCase().includes(needle))
      : rows;
  }, [q.data, query]);

  const byStatus = React.useMemo(() => {
    const groups: Record<StaffStatus, StaffResponse[]> = { ACTIVE: [], INVITED: [], DISABLED: [] };
    for (const s of filtered) groups[s.status].push(s);
    for (const t of TAB_ORDER) groups[t].sort((a, b) => a.name.localeCompare(b.name));
    return groups;
  }, [filtered]);

  const rows = byStatus[tab];
  const { pageRows, page, setPage, pageSize, setPageSize, pageCount, total } = usePagination(rows);

  const tabs: TabDef[] = TAB_ORDER.map((t) => ({
    key: t,
    label: STATUS_LABEL[t],
    badge: byStatus[t].length || undefined,
  }));

  if (me.isLoading) {
    return <div className="h-40 animate-pulse rounded border border-line bg-white" />;
  }

  if (role && !hasPermission(role, "staff:manage")) {
    return <NoAccessNotice message="Admin access only." />;
  }

  return (
    <div>
      <PageHeader title="Staff accounts" subtitle="Manage staff roles and access status.">
        <ExportMenu
          title="Staff accounts"
          fileBase="dhanboost-staff"
          columns={[
            { header: "ID", value: (s: StaffResponse) => s.id },
            { header: "Name", value: (s) => s.name },
            { header: "Email", value: (s) => s.email },
            { header: "Role", value: (s) => ROLE_LABEL[s.role] },
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

      <div className="mb-3 flex flex-wrap items-center justify-between gap-3">
        <Tabs tabs={tabs} active={tab} onChange={(k) => { setTab(k as StaffStatus); setPage(1); }} />
        <SearchBar
          initialValue={query}
          onSearch={(t) => { setQuery(t); setPage(1); }}
          placeholder="Name or email"
        />
      </div>

      {q.isLoading ? (
        <div className="h-40 animate-pulse rounded border border-line bg-white" />
      ) : q.error ? (
        <p className="text-sm text-error-700">{errMessage(q.error)}</p>
      ) : (
        <div className="staff-table-scroll rounded border border-line bg-white shadow-sm">
          <table className="staff-data-table">
            <thead>
              <tr>
                <th>S.No.</th>
                <th>Staff</th>
                <th>Role</th>
                <th>Status</th>
                <th className="text-right">Actions</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-line">
              {pageRows.map((s, i) => (
                <StaffRow key={s.id} staff={s} index={(page - 1) * pageSize + i} />
              ))}
              {rows.length === 0 && (
                <tr><td colSpan={5} className="text-center text-muted">No {STATUS_LABEL[tab].toLowerCase()} staff accounts.</td></tr>
              )}
            </tbody>
          </table>
          <PaginationBar
            page={page}
            pageCount={pageCount}
            setPage={setPage}
            total={total}
            pageSize={pageSize}
            setPageSize={setPageSize}
          />
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
  const [role, setRole] = React.useState<StaffRoleName>("CREDIT_EXECUTIVE");
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
      setRole("CREDIT_EXECUTIVE");
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
          options={ROLES.map((r) => ({ value: r, label: ROLE_LABEL[r] }))}
        />
        <Input
          label="Password" type="password" autoComplete="new-password" value={password}
          onChange={(e) => setPassword(e.target.value)} placeholder="Set a password (min 4)"
        />
      </div>
      <div className="mt-3 flex items-center gap-3">
        <button
          onClick={() => {
            create.reset();
            create.mutate();
          }}
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

function StaffRow({ staff, index }: { staff: StaffResponse; index: number }) {
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
  // Deliberately not automatic — disabling a live account ends its session immediately, so the
  // click is armed by a first press and only fires on the second (mirrors case-failure-dialog).
  const [armed, setArmed] = React.useState(false);

  const invalidate = () => qc.invalidateQueries({ queryKey: ["admin-staff"] });
  const save = useMutation({
    mutationFn: () => adminApi.updateStaff(staff.id, { role, status }),
    onSuccess: invalidate,
  });
  const disable = useMutation({
    mutationFn: () => adminApi.disableStaff(staff.id),
    onSuccess: () => {
      setArmed(false);
      invalidate();
    },
  });
  const enable = useMutation({
    mutationFn: () => adminApi.updateStaff(staff.id, { role, status: "ACTIVE" }),
    onSuccess: invalidate,
  });

  const error = save.error || disable.error || enable.error;

  return (
    <tr>
      <td className="text-muted">{index + 1}</td>
      <td>
        <div className="font-semibold text-ink">{staff.name}</div>
        <div className="text-xs text-muted">{staff.email} · #{staff.id}</div>
      </td>
      <td>
        <Select className="!mb-0" value={role} onChange={(e) => setRole(e.target.value as StaffRoleName)}
          options={ROLES.map((r) => ({ value: r, label: ROLE_LABEL[r] }))} />
      </td>
      <td>
        {staff.status === "INVITED" ? (
          <Badge variant={STATUS_BADGE.INVITED}>{STATUS_LABEL.INVITED}</Badge>
        ) : (
          <Select className="!mb-0" value={status} onChange={(e) => setStatus(e.target.value as StaffStatus)}
            options={EDITABLE_STATUSES.map((s) => ({ value: s, label: STATUS_LABEL[s] }))} />
        )}
      </td>
      <td>
        <div className="flex items-center justify-end gap-2">
          {error && <span className="text-xs text-error-700">{errMessage(error)}</span>}
          {staff.status === "DISABLED" ? (
            <button
              onClick={() => {
                save.reset();
                disable.reset();
                enable.mutate();
              }}
              disabled={enable.isPending}
              className="btn btn-sm btn-navy disabled:opacity-50"
            >
              {enable.isPending ? <Loader2 size={13} className="animate-spin" /> : <UserCheck size={13} />} Enable
            </button>
          ) : (
            <>
              <button
                onClick={() => {
                  disable.reset();
                  enable.reset();
                  save.mutate();
                }}
                disabled={!dirty || save.isPending}
                className="btn btn-sm btn-navy disabled:opacity-50"
              >
                {save.isPending ? <Loader2 size={13} className="animate-spin" /> : null} Save
              </button>
              <button
                onClick={() => {
                  save.reset();
                  enable.reset();
                  if (armed) disable.mutate();
                  else setArmed(true);
                }}
                onBlur={() => setArmed(false)}
                disabled={disable.isPending}
                className="btn btn-sm btn-outline disabled:opacity-50"
              >
                <UserX size={13} /> {armed ? "Confirm disable" : "Disable"}
              </button>
            </>
          )}
        </div>
      </td>
    </tr>
  );
}
