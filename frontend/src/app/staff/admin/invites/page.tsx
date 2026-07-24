"use client";

import * as React from "react";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { Loader2, RefreshCw, Mail, Copy, Check } from "lucide-react";
import { Input, Select } from "@/components/ui";
import { PageHeader } from "@/components/staff/staff-ui";
import { errMessage, useStaffMe, NoAccessNotice } from "@/components/staff/live-pipeline";
import { ExportMenu } from "@/components/staff/export-menu";
import { hasPermission } from "@/lib/auth/rbac";
import { adminApi, type StaffRoleName, type InviteResponse } from "@/lib/api/applications";
import { formatDateTime } from "@/lib/utils";

const ROLES: StaffRoleName[] = [
  "KYC_APPROVER", "CREDIT_EXECUTIVE", "CREDIT_HEAD", "DISBURSEMENT_HEAD",
  "ACCOUNTANT", "COLLECTION_HEAD", "COLLECTION_EXECUTIVE", "ADMIN", "DEVELOPER",
];

/** Admin · invites — issue an invite (returns a one-time token) and list invites. ADMIN only. */
export default function AdminInvitesPage() {
  const myRole = useStaffMe().data?.role;
  const qc = useQueryClient();
  const q = useQuery({ queryKey: ["admin-invites"], queryFn: adminApi.listInvites });
  const [email, setEmail] = React.useState("");
  const [role, setRole] = React.useState<StaffRoleName>("KYC_APPROVER");

  const create = useMutation({
    mutationFn: () => adminApi.createInvite({ email: email.trim(), role }),
    onSuccess: () => {
      setEmail("");
      qc.invalidateQueries({ queryKey: ["admin-invites"] });
    },
  });

  if (myRole && !hasPermission(myRole, "staff:manage")) {
    return <NoAccessNotice message="Admin access only." />;
  }

  return (
    <div>
      <PageHeader title="Staff invites" subtitle="Issue a one-time invite token; the invitee activates on /staff/activate.">
        <ExportMenu
          title="Staff invites"
          fileBase="dhanboost-invites"
          columns={[
            { header: "Email", value: (i: InviteResponse) => i.email },
            { header: "Role", value: (i) => i.role },
            { header: "Token", value: (i) => i.token },
            { header: "Expires", value: (i) => (i.expiresAt ? formatDateTime(i.expiresAt) : "") },
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
        <div className="h-32 animate-pulse rounded border border-line bg-white" />
      ) : q.error ? (
        <p className="text-sm text-error-700">{errMessage(q.error)}</p>
      ) : (
        <div className="overflow-hidden rounded border border-line bg-white shadow-sm">
          <table className="w-full text-sm">
            <thead className="border-b border-line bg-grey-50 text-left text-xs uppercase tracking-wide text-muted">
              <tr>
                <th className="px-4 py-2.5">Email</th>
                <th className="px-4 py-2.5">Role</th>
                <th className="px-4 py-2.5">Token</th>
                <th className="px-4 py-2.5">Expires</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-line">
              {(q.data ?? []).map((inv: InviteResponse) => (
                <tr key={inv.id}>
                  <td className="px-4 py-3 text-ink">{inv.email}</td>
                  <td className="px-4 py-3 text-muted">{inv.role}</td>
                  <td className="px-4 py-3"><TokenChip token={inv.token} /></td>
                  <td className="px-4 py-3 text-muted">{inv.expiresAt ? formatDateTime(inv.expiresAt) : "—"}</td>
                </tr>
              ))}
              {(q.data ?? []).length === 0 && (
                <tr><td colSpan={4} className="px-4 py-6 text-center text-muted">No invites yet.</td></tr>
              )}
            </tbody>
          </table>
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
