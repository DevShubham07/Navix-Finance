"use client";

/**
 * Decision history (revamp.md Phase 2, decision 32).
 *
 * Everyone sees their own decisions; a Head can switch to a team member's; ADMIN can open anyone's.
 * The switcher only lists staff the server will actually allow — it's a convenience over the same
 * rule the backend enforces, not the gate itself.
 */

import * as React from "react";
import { useQuery } from "@tanstack/react-query";
import { Loader2 } from "lucide-react";
import { PageHeader } from "@/components/staff/staff-ui";
import { Select } from "@/components/ui";
import { staffApi, statusLabel, type ApplicationStatus } from "@/lib/api/applications";
import { useStaffMe, errMessage } from "@/components/staff/pipeline/hooks";
import { formatDate } from "@/lib/utils";

/** Verb per audited action, so the log reads as a sentence rather than an enum dump. */
const ACTION_LABEL: Record<string, string> = {
  ASSIGN: "Assigned to an executive",
  SANCTION: "Accepted lead (sanctioned)",
  REJECT_LEAD: "Rejected lead",
  MARK_PENDING: "Marked lead pending",
  KYC_APPROVE: "Cleared KYC",
  KYC_REJECT: "Rejected KYC",
  // Retired with the credit maker-checker (V45) — historical rows still carry them.
  EXEC_APPROVE: "Recommended (legacy)",
  EXEC_REJECT: "Rejected at credit review (legacy)",
  HEAD_APPROVE: "Approved as Credit Head (legacy)",
  HEAD_REJECT: "Rejected as Credit Head (legacy)",
  DISB_ACCEPT: "Released for disbursal",
  DISB_REJECT: "Rejected at disbursement",
  VALIDATE_SUCCESS: "Confirmed transfer",
  VALIDATE_FAIL: "Marked transfer failed",
  RETRY: "Retried disbursement",
  CANCEL: "Cancelled",
};

export default function MyDecisionsPage() {
  const me = useStaffMe();
  const [staffId, setStaffId] = React.useState("");

  const team = useQuery({
    queryKey: ["decisions-inspectable"],
    queryFn: () => staffApi.inspectableStaff(),
    staleTime: 60_000,
  });

  const q = useQuery({
    queryKey: ["decisions", staffId],
    queryFn: () => staffApi.decisions(staffId ? Number(staffId) : undefined),
  });

  const rows = q.data ?? [];
  const others = (team.data ?? []).filter((s) => String(s.id) !== String(me.data?.id));

  return (
    <div className="space-y-6">
      <PageHeader
        title="Decision history"
        subtitle="Every lifecycle decision you've made, newest first."
      />

      {others.length > 0 && (
        <div className="flex items-center gap-3">
          <Select
            aria-label="Whose decisions to show"
            className="!mb-0"
            value={staffId}
            onChange={(e) => setStaffId(e.target.value)}
          >
            <option value="">My decisions</option>
            {others.map((s) => (
              <option key={s.id} value={s.id}>
                {s.name}
              </option>
            ))}
          </Select>
          {q.isFetching && <Loader2 size={15} className="animate-spin text-muted" />}
        </div>
      )}

      <section className="rounded border border-line bg-white shadow-sm">
        {q.error ? (
          <p className="px-5 py-4 text-sm text-error-700">{errMessage(q.error)}</p>
        ) : q.isLoading ? (
          <p className="px-5 py-6 text-center text-sm text-muted">Loading…</p>
        ) : rows.length === 0 ? (
          <p className="px-5 py-6 text-center text-sm text-muted">No decisions recorded yet.</p>
        ) : (
          <table className="w-full text-[13px]">
            <thead className="border-b border-line text-left text-xs uppercase tracking-wide text-muted">
              <tr>
                <th className="px-5 py-2 font-semibold">When</th>
                <th className="px-5 py-2 font-semibold">Application</th>
                <th className="px-5 py-2 font-semibold">Customer</th>
                <th className="px-5 py-2 font-semibold">Decision</th>
                <th className="px-5 py-2 font-semibold">Outcome</th>
                <th className="px-5 py-2 font-semibold">Notes</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-line">
              {rows.map((r, i) => (
                <tr key={`${r.applicationId}-${r.at}-${i}`}>
                  <td className="whitespace-nowrap px-5 py-2 text-muted">{formatDate(r.at)}</td>
                  <td className="px-5 py-2 font-mono">#{r.applicationId}</td>
                  <td className="px-5 py-2">{r.customerName ?? "—"}</td>
                  <td className="px-5 py-2 font-semibold text-navy">
                    {ACTION_LABEL[r.action] ?? r.action}
                  </td>
                  <td className="px-5 py-2">{statusLabel(r.toStatus as ApplicationStatus)}</td>
                  <td className="px-5 py-2 text-muted">{r.notes ?? "—"}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>
    </div>
  );
}
