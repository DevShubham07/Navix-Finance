"use client";

import * as React from "react";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { Loader2, RefreshCw, UserPlus, UserX, UserCheck } from "lucide-react";
import { EmptyState, ErrorState, Input, Select, Skeleton, toast } from "@/components/ui";
import { Badge, type BadgeVariant } from "@/components/ui/badge";
import { Tabs, type TabDef } from "@/components/ui/tabs";
import { PageHeader } from "@/components/staff/staff-ui";
import { errMessage, useStaffMe, useCan, NoAccessNotice, ROLE_LABEL } from "@/components/staff/live-pipeline";
import { ExportMenu } from "@/components/staff/export-menu";
import { SearchBar } from "@/components/staff/search-bar";
import {
  adminApi,
  type StaffResponse,
  type StaffRoleName,
  type StaffStatus,
} from "@/lib/api/applications";
import { PaginationBar } from "@/components/staff/pipeline/pagination";
import { type FilterColumn } from "@/components/staff/column-filter";
import { useRegisterQuery } from "@/lib/staff/register-query";
import { useClientRegister } from "@/lib/staff/use-client-register";
import { Th, thBag, describeRegister } from "@/lib/staff/register-th";

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

/** Sortable + filterable columns. Status is the tab's dimension, so it deliberately has no column
 *  filter of its own — two controls for one field is a trap, not a feature. */
const SORT_KEYS = ["name", "role"] as const;

const COLUMN_LABEL: Record<string, string> = { name: "Staff", role: "Role" };

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
  const can = useCan();
  const q = useQuery({ queryKey: ["admin-staff"], queryFn: adminApi.listStaff });

  const [tab, setTab] = React.useState<StaffStatus>("ACTIVE");

  // `q` lives in the register's view, which is also where the `?q=` deep link global search sends
  // people is read from — one owner, and it returns to page 1 on its own.
  const rq = useRegisterQuery({
    registerId: "admin-staff",
    staffId: me.data?.id,
    sortKeys: SORT_KEYS,
    filterKeys: SORT_KEYS,
    defaults: { sort: { key: "name", dir: "asc" } },
  });
  const query = rq.state.q;

  const all = React.useMemo(() => q.data ?? [], [q.data]);

  const filtered = React.useMemo(() => {
    const needle = query.trim().toLowerCase();
    return needle
      ? all.filter((s) => s.name.toLowerCase().includes(needle) || s.email.toLowerCase().includes(needle))
      : all;
  }, [all, query]);

  // Tab counts follow the search (they always have) but NOT the column filters: a tab badge that
  // shrank as you filtered inside it would stop being a way to move between tabs.
  const byStatus = React.useMemo(() => {
    const groups: Record<StaffStatus, StaffResponse[]> = { ACTIVE: [], INVITED: [], DISABLED: [] };
    for (const s of filtered) groups[s.status].push(s);
    return groups;
  }, [filtered]);

  const filterColumns = React.useMemo<Array<FilterColumn<StaffResponse>>>(
    () => [
      { key: "name", kind: "text", value: (s) => s.name },
      // The checklist reads "Credit Executive"; the filter travels as CREDIT_EXECUTIVE.
      { key: "role", value: (s) => ROLE_LABEL[s.role], raw: (s) => s.role },
    ],
    [],
  );

  const inTab = React.useCallback((s: StaffResponse) => s.status === tab, [tab]);
  const cr = useClientRegister(filtered, filterColumns, rq, inTab);
  const { pageRows, total, pageCount } = cr;
  const rows = cr.rows;
  const th = thBag(rq, cr, filterColumns, COLUMN_LABEL);
  const page = rq.state.page;
  const pageSize = rq.state.size;

  const tabs: TabDef[] = TAB_ORDER.map((t) => ({
    key: t,
    label: STATUS_LABEL[t],
    badge: byStatus[t].length || undefined,
  }));

  if (me.isLoading) {
    return <div className="h-40 animate-pulse rounded border border-line bg-white" />;
  }

  if (role && !can("staff:manage")) {
    return <NoAccessNotice message="Admin access only." />;
  }

  return (
    <div>
      <PageHeader
        title="Staff accounts"
        subtitle={["Manage staff roles and access status.", describeRegister(th)].filter(Boolean).join(" · ")}
      >
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
          // The tab- and search-filtered set the table renders. `q.data` is the whole roster, so
          // exporting from the Disabled tab with a search active used to return everyone.
          rows={rows}
          scope="all"
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
        <Tabs
          tabs={tabs}
          active={tab}
          onChange={(k) => {
            setTab(k as StaffStatus);
            // "What matches has changed" — back to page 1 in one update, filters untouched.
            if (k !== tab) rq.apply({});
          }}
        />
        <SearchBar initialValue={query} onSearch={rq.setQ} placeholder="Name or email" />
      </div>

      {q.isLoading ? (
        <Skeleton variant="table" rows={8} cols={5} className="rounded border border-line bg-white" />
      ) : q.error ? (
        <ErrorState error={q.error} onRetry={() => void q.refetch()} />
      ) : (
        <div className="staff-table-scroll rounded border border-line bg-white shadow-sm">
          <table className="staff-data-table">
            <thead>
              <tr>
                <th>S.No.</th>
                <Th th={th} col="name" />
                <Th th={th} col="role" />
                <th>Status</th>
                <th className="text-right">Actions</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-line">
              {pageRows.map((s, i) => (
                <StaffRow key={s.id} staff={s} index={(page - 1) * pageSize + i} />
              ))}
              {total === 0 && (
                <EmptyState
                  title={
                    rq.activeCount > 0
                      ? "No staff accounts match the filters."
                      : `No ${STATUS_LABEL[tab].toLowerCase()} staff accounts.`
                  }
                  inTable={5}
                />
              )}
            </tbody>
          </table>
          <PaginationBar
            page={page}
            pageCount={pageCount}
            setPage={rq.setPage}
            total={total}
            pageSize={pageSize}
            setPageSize={rq.setSize}
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
      toast.success("Staff account created");
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
    onSuccess: () => {
      toast.success("Staff account updated");
      // Returned so the mutation stays pending until the refetch lands, as before.
      return invalidate();
    },
  });
  const disable = useMutation({
    mutationFn: () => adminApi.disableStaff(staff.id),
    onSuccess: () => {
      setArmed(false);
      invalidate();
      toast.success("Staff account disabled");
    },
  });
  const enable = useMutation({
    mutationFn: () => adminApi.updateStaff(staff.id, { role, status: "ACTIVE" }),
    onSuccess: () => {
      toast.success("Staff account enabled");
      // Returned so the mutation stays pending until the refetch lands, as before.
      return invalidate();
    },
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
