"use client";

import * as React from "react";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { Loader2, RefreshCw, Mail, Copy, Check } from "lucide-react";
import { EmptyState, ErrorState, Input, Select, Skeleton } from "@/components/ui";
import { PageHeader } from "@/components/staff/staff-ui";
import { errMessage, useStaffMe, useCan, NoAccessNotice } from "@/components/staff/live-pipeline";
import { ExportMenu } from "@/components/staff/export-menu";
import { adminApi, type StaffRoleName, type InviteResponse } from "@/lib/api/applications";
import { formatDateTime } from "@/lib/utils";
import { PaginationBar } from "@/components/staff/pipeline/pagination";
import { type FilterColumn } from "@/components/staff/column-filter";
import { istDay } from "@/lib/staff/column-filter-match";
import { useRegisterQuery } from "@/lib/staff/register-query";
import { useClientRegister } from "@/lib/staff/use-client-register";
import { Th, thBag, describeRegister } from "@/lib/staff/register-th";

const ROLES: StaffRoleName[] = [
  "CREDIT_EXECUTIVE", "CREDIT_HEAD", "DISBURSEMENT_HEAD",
  "ACCOUNTANT", "COLLECTION_HEAD", "COLLECTION_EXECUTIVE", "TELECALLER", "DSA", "ADMIN",
];

/** Email and token are identity (and the token is a secret), so only role and expiry filter. */
const SORT_KEYS = ["role", "expiresAt"] as const;

const COLUMN_LABEL: Record<string, string> = { role: "Role", expiresAt: "Expires" };

/** Admin · invites — issue an invite (returns a one-time token) and list invites. ADMIN only. */
export default function AdminInvitesPage() {
  const me = useStaffMe().data;
  const myRole = me?.role;
  const can = useCan();
  const qc = useQueryClient();
  const q = useQuery({ queryKey: ["admin-invites"], queryFn: adminApi.listInvites });
  const [email, setEmail] = React.useState("");
  const [role, setRole] = React.useState<StaffRoleName>("CREDIT_EXECUTIVE");

  const create = useMutation({
    mutationFn: () => adminApi.createInvite({ email: email.trim(), role }),
    onSuccess: () => {
      setEmail("");
      qc.invalidateQueries({ queryKey: ["admin-invites"] });
    },
  });

  const rows = React.useMemo(() => q.data ?? [], [q.data]);

  const rq = useRegisterQuery({
    registerId: "invites",
    staffId: me?.id,
    sortKeys: SORT_KEYS,
    filterKeys: SORT_KEYS,
    defaults: { sort: { key: "expiresAt", dir: "desc" } },
  });

  const filterColumns = React.useMemo<Array<FilterColumn<InviteResponse>>>(
    () => [
      { key: "role", value: (i) => i.role, raw: (i) => i.role },
      { key: "expiresAt", kind: "date", value: (i) => istDay(i.expiresAt) ?? "—", date: (i) => istDay(i.expiresAt) },
    ],
    [],
  );

  const cr = useClientRegister(rows, filterColumns, rq);
  const { pageRows, total, pageCount } = cr;
  const th = thBag(rq, cr, filterColumns, COLUMN_LABEL);
  const page = rq.state.page;
  const pageSize = rq.state.size;

  if (myRole && !can("staff:manage")) {
    return <NoAccessNotice message="Admin access only." />;
  }

  return (
    <div>
      <PageHeader
        title="Staff invites"
        subtitle={["Issue a one-time invite token; the invitee activates on /staff/activate.", describeRegister(th)]
          .filter(Boolean)
          .join(" · ")}
      >
        <ExportMenu
          title="Staff invites"
          fileBase="dhanboost-invites"
          columns={[
            { header: "Email", value: (i: InviteResponse) => i.email },
            { header: "Role", value: (i) => i.role },
            { header: "Token", value: (i) => i.token },
            { header: "Expires", value: (i) => (i.expiresAt ? formatDateTime(i.expiresAt) : "") },
          ]}
          rows={cr.rows}
          scope="all"
        />
        <button
          onClick={() => q.refetch()}
          className="flex items-center gap-1.5 rounded border border-line px-3 py-1.5 text-xs text-muted hover:bg-grey-100 hover:text-ink"
        >
          {q.isFetching ? <Loader2 size={13} className="animate-spin" /> : <RefreshCw size={13} />} Refresh
        </button>
      </PageHeader>

      <div className="mb-6 rounded border border-line bg-white p-5 shadow-sm">
        <div className="mb-3 flex items-center gap-2 text-sm font-semibold text-navy"><Mail size={16} /> Invite a staff member</div>
        <div className="flex flex-wrap items-end gap-3">
          <Input label="Email" type="email" value={email} onChange={(e) => setEmail(e.target.value)}
            placeholder="new.hire@dhanboost.com" className="!mb-0" inputClassName="w-64" />
          <Select label="Role" value={role} onChange={(e) => setRole(e.target.value as StaffRoleName)}
            options={ROLES.map((r) => ({ value: r, label: r }))} className="!mb-0" />
          <button onClick={() => create.mutate()} disabled={create.isPending || !email.trim()}
            className="btn btn-gold disabled:opacity-50">
            {create.isPending ? <Loader2 size={14} className="animate-spin" /> : null} Send invite
          </button>
        </div>
        {create.error && <p className="mt-2 text-sm text-error-700">{errMessage(create.error)}</p>}
        {create.data && (
          <div className="mt-3 rounded border border-success-100 bg-success-50 p-3 text-sm text-success-800">
            Invite created for <strong>{create.data.email}</strong>. Share this one-time token:
            <TokenChip token={create.data.token} />
          </div>
        )}
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
                <th>Email</th>
                <Th th={th} col="role" />
                <th>Token</th>
                <Th th={th} col="expiresAt" />
              </tr>
            </thead>
            <tbody className="divide-y divide-line">
              {pageRows.map((inv: InviteResponse, i) => (
                <tr key={inv.id}>
                  <td className="text-muted">{(page - 1) * pageSize + i + 1}</td>
                  <td className="text-ink">{inv.email}</td>
                  <td className="text-muted">{inv.role}</td>
                  <td><TokenChip token={inv.token} /></td>
                  <td className="text-muted">{inv.expiresAt ? formatDateTime(inv.expiresAt) : "—"}</td>
                </tr>
              ))}
              {total === 0 && (
                // In-table so the headers survive an emptying filter and it can still be cleared.
                <EmptyState
                  title={rq.activeCount > 0 && rows.length > 0 ? "No invites match the column filters." : "No invites yet."}
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

function TokenChip({ token }: { token: string }) {
  const [copied, setCopied] = React.useState(false);
  const copy = async () => {
    try {
      await navigator.clipboard.writeText(token);
      setCopied(true);
      setTimeout(() => setCopied(false), 1500);
    } catch {
      /* clipboard unavailable */
    }
  };
  return (
    <button onClick={copy} className="ml-1 inline-flex items-center gap-1.5 rounded bg-grey-100 px-2 py-1 font-mono text-xs text-ink hover:bg-grey-200">
      {copied ? <Check size={12} className="text-success-600" /> : <Copy size={12} />}
      <span className="max-w-[14rem] truncate">{token}</span>
    </button>
  );
}
