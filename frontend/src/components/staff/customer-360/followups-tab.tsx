"use client";

import * as React from "react";
import { useQuery } from "@tanstack/react-query";
import { CalendarClock, Check, Plus } from "lucide-react";
import { EmptyState, ErrorState, Skeleton, StatusBadge } from "@/components/ui";
import { Section } from "@/components/staff/detail-parts";
import { LogCallForm } from "@/components/staff/customer-360/communication-tab";
import type { TabCtx } from "@/components/staff/customer-360/types";
import { customersApi, leadsApi, type CallLogView } from "@/lib/api/applications";
import { useStaffSession } from "@/lib/auth/staff-session";
import { hasPermission } from "@/lib/auth/rbac";
import { istCalendarToday } from "@/lib/customers/customer-360";
import { formatDate, formatDateTime } from "@/lib/utils";

function ymd(d: Date): string {
  const p = (n: number) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`;
}

function FollowupTable({ rows, past, onDone }: { rows: CallLogView[]; past?: boolean; onDone?: () => void }) {
  return (
    <table className="staff-data-table">
      <thead>
        <tr>
          <th>S.No</th>
          <th>Callback on</th>
          <th>Call type</th>
          <th>Outcome</th>
          <th>Notes</th>
          <th>Logged by</th>
          <th>Logged at</th>
          {onDone && <th />}
        </tr>
      </thead>
      <tbody className={past ? "text-muted" : undefined}>
        {rows.map((r, i) => (
          <tr key={r.id}>
            <td>{i + 1}</td>
            {/* ponytail: any past callback counts as overdue even if a later call resolved it; link follow-ups to their resolving call if staff ask */}
            <td className={past ? "font-bold text-error-700" : "font-bold"}>{formatDate(r.callbackOn as string)}</td>
            <td>{r.callType}</td>
            <td>{r.outcome}</td>
            <td className="whitespace-pre-wrap">{r.notes ?? "—"}</td>
            <td>{r.author ?? "staff"}</td>
            <td>{r.at ? formatDateTime(r.at) : "—"}</td>
            {onDone && (
              <td>
                <button type="button" className="btn btn-sm btn-outline" onClick={onDone}>
                  <Check size={13} /> Done
                </button>
              </td>
            )}
          </tr>
        ))}
      </tbody>
    </table>
  );
}

export function FollowupsTab({ detail, customerId }: TabCtx) {
  const mobile = detail.profile?.mobile ?? null;
  const role = useStaffSession().session?.role;
  // The lead list is TELECALLER + ADMIN only server-side; other roles must not call it.
  const canSeeLeads = role != null && hasPermission(role, "leads:manage");
  const leadQ = useQuery({
    queryKey: ["customer-lead", mobile],
    queryFn: () => leadsApi.list({ q: mobile as string }),
    enabled: !!mobile && canSeeLeads,
  });
  const logsQ = useQuery({
    queryKey: ["customer-call-logs", customerId],
    queryFn: () => customersApi.callLogs(customerId),
  });
  const [form, setForm] = React.useState<{ outcome: string; n: number } | null>(null);

  const lead = leadQ.data?.rows.find((l) => l.mobile === mobile) ?? null;
  const today = ymd(istCalendarToday());
  const withCallback = (logsQ.data ?? []).filter((l) => l.callbackOn != null);
  const upcoming = withCallback
    .filter((l) => (l.callbackOn as string) >= today)
    .sort((a, b) => (a.callbackOn as string).localeCompare(b.callbackOn as string));
  const past = withCallback
    .filter((l) => (l.callbackOn as string) < today)
    .sort((a, b) => (b.callbackOn as string).localeCompare(a.callbackOn as string));
  const open = (outcome: string) => setForm((f) => ({ outcome, n: (f?.n ?? 0) + 1 }));

  return (
    <div className="space-y-3">
      <div className="flex flex-wrap items-center gap-2 text-sm">
        <span className="text-xs font-semibold uppercase tracking-wide text-muted">Telecalling</span>
        {lead ? (
          <>
            <StatusBadge kind="leadCall" value={lead.callStatus} />
            <StatusBadge kind="lead" value={lead.leadOutcome} />
            {lead.createdByStaffName && (
              <span className="text-xs text-muted">Added by {lead.createdByStaffName}</span>
            )}
          </>
        ) : !canSeeLeads ? (
          <span className="text-muted">Telecalling status is visible to telecallers</span>
        ) : leadQ.error ? (
          <span className="text-muted">Telecalling status unavailable</span>
        ) : (
          <span className="text-muted">{leadQ.isLoading || !leadQ.isSuccess ? "Checking…" : "No telecalling lead on file"}</span>
        )}
        <button type="button" className="btn btn-sm btn-navy ml-auto" onClick={() => open("CALLBACK")}>
          <Plus size={13} /> Schedule follow-up
        </button>
      </div>

      {form && (
        <Section title="Log call">
          <LogCallForm
            key={form.n}
            customerId={customerId}
            loans={detail.loans}
            defaultOutcome={form.outcome}
            focusCallback={form.outcome === "CALLBACK"}
            onDone={() => setForm(null)}
          />
        </Section>
      )}

      {logsQ.isLoading ? (
        <Skeleton variant="line" rows={4} />
      ) : logsQ.error ? (
        <ErrorState error={logsQ.error} onRetry={() => void logsQ.refetch()} />
      ) : withCallback.length === 0 ? (
        <EmptyState icon={<CalendarClock size={28} />} title="No follow-up scheduled for this customer." />
      ) : (
        <>
          <Section title="Upcoming" icon={CalendarClock}>
            {upcoming.length === 0 ? (
              <p className="text-sm text-muted">Nothing scheduled from today onwards.</p>
            ) : (
              <FollowupTable rows={upcoming} onDone={() => open("CONNECTED")} />
            )}
          </Section>
          <Section title="Past follow-ups">
            {past.length === 0 ? <p className="text-sm text-muted">None yet.</p> : <FollowupTable rows={past} past />}
          </Section>
        </>
      )}
    </div>
  );
}
