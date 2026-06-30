"use client";

import * as React from "react";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { Loader2, Save, UserCog, Lock } from "lucide-react";
import { Input } from "@/components/ui";
import { PageHeader } from "@/components/staff/staff-ui";
import { errMessage } from "@/components/staff/live-pipeline";
import { STAFF_ROLE_LABELS } from "@/lib/auth/rbac";
import { adminApi } from "@/lib/api/applications";

/**
 * Staff self-profile (Phase 2.2): a staffer reads + edits their own display name, department and
 * designation. Role and status are managed by an admin and shown read-only here.
 */
export default function StaffProfilePage() {
  const qc = useQueryClient();
  const q = useQuery({ queryKey: ["staff-my-profile"], queryFn: () => adminApi.myProfile() });
  const me = q.data;

  const [name, setName] = React.useState("");
  const [department, setDepartment] = React.useState("");
  const [designation, setDesignation] = React.useState("");

  React.useEffect(() => {
    if (!me) return;
    setName(me.name ?? "");
    setDepartment(me.department ?? "");
    setDesignation(me.designation ?? "");
  }, [me]);

  const save = useMutation({
    mutationFn: () =>
      adminApi.updateMyProfile({
        name: name.trim() || undefined,
        department: department.trim() || null,
        designation: designation.trim() || null,
      }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["staff-my-profile"] }),
  });

  return (
    <div>
      <PageHeader title="My profile" subtitle="Your account details. Update your name, department and designation." />

      {q.isLoading ? (
        <div className="h-48 animate-pulse rounded border border-line bg-white" />
      ) : q.error || !me ? (
        <p className="text-sm text-error-700">{q.error ? errMessage(q.error) : "Could not load your profile."}</p>
      ) : (
        <div className="max-w-xl rounded border border-line bg-white p-6 shadow-sm">
          <div className="mb-4 flex items-center gap-2 font-serif text-base font-semibold text-navy">
            <UserCog size={18} /> Account
          </div>

          <div className="mb-4 grid grid-cols-2 gap-3 rounded bg-grey-50 p-3 text-sm">
            <div>
              <div className="text-xs text-muted">Email</div>
              <div className="flex items-center gap-1.5 text-ink">{me.email} <Lock size={11} className="text-muted" /></div>
            </div>
            <div>
              <div className="text-xs text-muted">Role</div>
              <div className="flex items-center gap-1.5 text-ink">{STAFF_ROLE_LABELS[me.role]} <Lock size={11} className="text-muted" /></div>
            </div>
            <div>
              <div className="text-xs text-muted">Status</div>
              <div className="text-ink">{me.status}</div>
            </div>
          </div>

          <Input label="Display name" value={name} onChange={(e) => setName(e.target.value)} className="!mb-3" />
          <Input label="Department" value={department} onChange={(e) => setDepartment(e.target.value)} placeholder="e.g. Credit Operations" className="!mb-3" />
          <Input label="Designation" value={designation} onChange={(e) => setDesignation(e.target.value)} placeholder="e.g. Senior Analyst" className="!mb-4" />

          {save.error && <p className="mb-2 text-sm text-error-700">{errMessage(save.error)}</p>}
          {save.isSuccess && <p className="mb-2 text-sm text-success-700">Saved.</p>}

          <button onClick={() => save.mutate()} disabled={save.isPending} className="btn btn-navy disabled:opacity-50">
            {save.isPending ? <Loader2 size={15} className="animate-spin" /> : <Save size={15} />} Save changes
          </button>
          <p className="mt-3 text-xs text-muted">Role and status are managed by an administrator.</p>
        </div>
      )}
    </div>
  );
}
